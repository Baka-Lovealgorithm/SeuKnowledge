package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.ai.konwledgerepo.tracing.TokenAccumulator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * LlamaParse 缺页补全：detectMissingPages 得到缺页清单，渲染 PNG 后由识图模型转写为 Markdown
 * （第 1 层 prompt 约束），再经 {@link #normalizeVisionMarkdown} 规范化（第 2 层）后按页码插入逐页 md 流。
 * 未启用识图 / 识图失败时该页保持缺失（仅告警，不阻断）。
 */
@Component
public class VisionPageFiller {

    private static final Logger log = LoggerFactory.getLogger(VisionPageFiller.class);

    private final LlamaParseService llamaParseService;
    private final VisionOcrService visionOcrService;
    private final QaTracing qaTracing;
    private final boolean visionParsing;
    private final int visionDpi;
    private final int visionParallel;
    private final Executor visionExecutor;

    public VisionPageFiller(LlamaParseService llamaParseService,
                            VisionOcrService visionOcrService,
                            QaTracing qaTracing,
                            SeuDocumentProperties docProps,
                            @Qualifier("visionTaskExecutor") Executor visionExecutor) {
        this.llamaParseService = llamaParseService;
        this.visionOcrService = visionOcrService;
        this.qaTracing = qaTracing;
        this.visionParsing = docProps.visionParsing();
        this.visionDpi = docProps.visionDpi();
        this.visionParallel = docProps.visionParallel();
        this.visionExecutor = visionExecutor;
    }

    /**
     * 缺页补全：缺页清单 → 渲染 PNG → 识图转写（并行/串行按配置分流）→ 规范化后并入逐页 md 流。
     * 未启用识图 / 识图失败时该页保持缺失（仅告警，不阻断）。
     */
    public List<LlamaParseService.PageMarkdown> fill(Path path, List<LlamaParseService.PageMarkdown> pages,
                                                     Long workspaceId) {
        List<Integer> missing = llamaParseService.detectMissingPages(path, pages);
        if (missing.isEmpty()) {
            return pages;
        }
        log.info("检测到缺页 {}，启用识图补全（fill-missing-pages=true）", missing);
        if (!visionParsing || !visionOcrService.isConfigured(workspaceId)) {
            log.warn("缺页补全跳过：识图模型未配置或 vision-parsing 关闭，缺页 {} 保持缺失", missing);
            return pages;
        }
        List<LlamaParseService.PageMarkdown> filled = new ArrayList<>(pages);
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            boolean useParallel = visionParallel > 1 && visionExecutor != null;
            for (int page : missing) {
                Optional<String> md = useParallel
                        ? describeMissingPageParallel(renderer, page, workspaceId)
                        : describeMissingPageSequential(renderer, page, workspaceId);
                md.ifPresent(text -> filled.add(new LlamaParseService.PageMarkdown(page, normalizeVisionMarkdown(text))));
            }
        } catch (Exception e) {
            log.warn("缺页识图补全失败（PDF 读取异常），缺页 {} 保持缺失: {}", missing, e.getMessage());
        }
        return filled;
    }

    /** 并行路径：渲染 PNG 在调用线程（PDDocument 非线程安全），识图调用提交线程池 */
    private Optional<String> describeMissingPageParallel(PDFRenderer renderer, int page, Long workspaceId) {
        byte[] png = PdfPages.renderPng(renderer, page, visionDpi);
        if (png == null) {
            return Optional.empty();
        }
        try {
            CompletableFuture<Optional<VisionOcrService.VisionOutcome>> future = CompletableFuture.supplyAsync(() ->
                    visionOcrService.describeParallelMarkdown(png, page, workspaceId), visionExecutor);
            Optional<VisionOcrService.VisionOutcome> outcome = future.join();
            if (outcome.isPresent()) {
                if (outcome.get().usage() != null) {
                    TokenAccumulator.accumulate(TokenAccumulator.TYPE_VISION, outcome.get().usage());
                }
                return Optional.of(outcome.get().text());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("缺页第 {} 页并行识图失败: {}", page, e.getMessage());
            return Optional.empty();
        }
    }

    /** 串行路径：渲染 + 带追踪识图（并行度 ≤1），使用 Markdown 输出 prompt */
    private Optional<String> describeMissingPageSequential(PDFRenderer renderer, int page, Long workspaceId) {
        try {
            byte[] png = PdfPages.renderPng(renderer, page, visionDpi);
            if (png == null) {
                return Optional.empty();
            }
            return visionOcrService.describeImageMarkdown(png, page, workspaceId);
        } catch (Exception e) {
            log.warn("PDF 第 {} 页渲染/识图失败，回退纯文本: {}", page, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 第 2 层后处理：把识图模型的输出规整为可被 RecursiveChunkSplitter 消费的 Markdown。
     * 剥离代码围栏与废话前缀、确保标题行前有换行、压缩连续空行、去除首尾空白。
     * 仅做格式规整，不做内容校验（无第 3 层结构校验/重试）。
     */
    static String normalizeVisionMarkdown(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        // 剥离 ```markdown / ``` 围栏：去掉首行（``` 或 ```markdown）与结尾 ```，保留中间内容
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            String rest = firstNewline >= 0 ? t.substring(firstNewline + 1) : t.substring(3);
            int end = rest.lastIndexOf("```");
            t = end >= 0 ? rest.substring(0, end) : rest;
            t = t.trim();
        }
        // 剥离常见废话前缀行（识别自述/说明）
        String[] prefixes = {"以下是识别结果：", "以下为识别内容：", "图片内容：", "页面内容：", "识别结果：", "转写结果：", "Markdown："};
        for (String p : prefixes) {
            if (t.startsWith(p)) {
                t = t.substring(p.length()).trim();
                break;
            }
        }
        // 确保标题行前有换行（分隔符契约 \n##）：# 序列前既非行首也非换行时补 \n（保留原层级）
        t = t.replaceAll("(?<!^)(?<![\\r\\n])#{1,6}\\s+", "\n$0");
        // 压缩连续空行（含空白行）为单个 \n\n
        t = t.replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n");
        return t.trim();
    }
}
