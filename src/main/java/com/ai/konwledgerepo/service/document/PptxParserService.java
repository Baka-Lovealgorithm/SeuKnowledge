package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.ai.konwledgerepo.tracing.TokenAccumulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * PPTX 解析服务：仅走 LlamaParse 云端转换（与 DOCX 同模式，无本地兜底）。
 *
 * <p>流程与 PDF 的 LlamaParse 路径对齐：
 * <ol>
 *   <li>LlamaParse 逐页（每页 = 一个幻灯片）转 Markdown，pageNumber = 幻灯片号；
 *       启用 take-screenshot 时每页附带整页截图（图片内容页的正文通常在截图里，md 只有标题/为空）。</li>
 *   <li>缺页/图片内容页补全（fill-text-poor，同 PDF fillMissingPages 思路）：md 为空或文本过短
 *       （&lt; vision-min-text，图片内容页特征）且该页有截图时，渲染好的截图直接交识图模型
 *       （VISION 类型）转写为 Markdown，经 normalizeVisionMarkdown 规整后替换该页 md；
 *       无截图或无 VISION 模型时保持原样（告警，不阻断）。</li>
 *   <li>每页一个 chunk（用户硬需求：每一页作为 chunk，不做 800 字符切分）；
 *       pageNum = 幻灯片号，title = 页 md 首标题行。</li>
 * </ol>
 */
@Service
public class PptxParserService {

    private static final Logger log = LoggerFactory.getLogger(PptxParserService.class);

    private final LlamaParseService llamaParseService;
    private final VisionOcrService visionOcrService;
    private final QaTracing qaTracing;
    private final boolean visionParsing;
    private final boolean fillMissingPages;
    private final int visionMinText;
    private final int visionParallel;
    private final Executor visionExecutor;

    public PptxParserService(LlamaParseService llamaParseService,
                             VisionOcrService visionOcrService,
                             QaTracing qaTracing,
                             SeuDocumentProperties docProps,
                             @Qualifier("visionTaskExecutor") Executor visionExecutor) {
        this.llamaParseService = llamaParseService;
        this.visionOcrService = visionOcrService;
        this.qaTracing = qaTracing;
        this.visionParsing = docProps.visionParsing();
        this.fillMissingPages = docProps.llamaparse().fillMissingPages();
        this.visionMinText = docProps.visionMinText();
        this.visionParallel = docProps.visionParallel();
        this.visionExecutor = visionExecutor;
    }

    /**
     * 解析 PPTX 为分块片段列表（每页一个 chunk）。
     * 由 {@link DocumentParserService#parse} 调用（trace 与工作空间解析在调用方完成）。
     *
     * @param path        落盘文件路径
     * @param fileName    原始文件名（LlamaParse 上传用）
     * @param workspaceId 知识库归属工作空间（识图模型按空间解析）
     */
    public List<ChunkPiece> parse(java.nio.file.Path path, String fileName, Long workspaceId) {
        if (!llamaParseService.isConfigured()) {
            throw new BizException("PPTX 解析需要启用 LlamaParse（seuknowledge.document.llamaparse.enabled 且配置 API Key）");
        }
        List<LlamaParseService.PageMarkdown> pages = llamaParseService.parseToMarkdown(path, fileName);
        if (fillMissingPages) {
            pages = fillTextPoorSlides(pages, workspaceId);
        }
        // 复制为可变列表再按页码排序（调用方可能返回不可变列表）
        List<LlamaParseService.PageMarkdown> ordered = new ArrayList<>(pages);
        ordered.sort(Comparator.comparingInt(LlamaParseService.PageMarkdown::pageNumber));
        // 跨页检测页脚标题（如公司名"臻融科技"在多数页以首个标题出现）：供标题提取跳过页脚
        String footer = detectFooter(ordered);
        // 每页一个 chunk
        List<ChunkPiece> pieces = new ArrayList<>();
        for (LlamaParseService.PageMarkdown page : ordered) {
            String md = page.markdown();
            if (md == null || md.isBlank()) {
                continue; // 空页（无 md 且未补全成功）跳过，与 PDF 空页语义一致
            }
            pieces.add(new ChunkPiece(md, page.pageNumber(), extractTitle(md, footer)));
        }
        if (pieces.isEmpty()) {
            // 与 PDF 空文档行为一致：不抛异常，返回空列表（DocumentParseExecutor 标记 SUCCESS、chunkCount=0）
            log.warn("PPTX {} 解析完成但无可用内容（可能全部为图片页且识图未配置/补全失败）", fileName);
        }
        return pieces;
    }

    /**
     * 跨页检测页脚标题：统计各页"首个标题行"的出现频率，最高频且出现次数 ≥ max(3, 页数/10) 的短标题
     * （≤8 字符）视为页脚（如每页底部公司名"臻融科技"被 LlamaParse 识别为标题的情况）。
     * 无页脚返回 null。
     */
    static String detectFooter(List<LlamaParseService.PageMarkdown> pages) {
        if (pages == null || pages.size() < 3) {
            return null;
        }
        Map<String, Integer> firstHeadingFreq = new HashMap<>();
        for (LlamaParseService.PageMarkdown page : pages) {
            String md = page.markdown();
            if (md == null) {
                continue;
            }
            for (String line : md.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.matches("^#{1,6}\\s+.*")) {
                    String heading = trimmed.replaceFirst("^#{1,6}\\s+", "").trim();
                    firstHeadingFreq.merge(heading, 1, Integer::sum);
                    break; // 只统计首个标题
                }
                break; // 首个非空行非标题 → 该页无"首标题页脚"特征
            }
        }
        int threshold = Math.max(3, pages.size() / 10);
        return firstHeadingFreq.entrySet().stream()
                .filter(e -> e.getValue() >= threshold && e.getKey().length() <= 8)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 图片内容页补全：md 为空或文本长度 &lt; vision-min-text 且该页有整页截图时，
     * 用识图模型转写 Markdown 替换该页 md（同 PDF 缺页补全：MARKDOWN_PROMPT + normalizeVisionMarkdown）。
     * 识图未配置 / 无截图 / 调用失败 → 该页保持原样（告警日志，不阻断）。
     */
    private List<LlamaParseService.PageMarkdown> fillTextPoorSlides(
            List<LlamaParseService.PageMarkdown> pages, Long workspaceId) {
        List<LlamaParseService.PageMarkdown> result = new ArrayList<>(pages.size());
        // 待补全页（含截图）→ 并行识图
        Map<Integer, Optional<VisionOcrService.VisionOutcome>> visionResults = new HashMap<>();
        boolean useParallel = visionParallel > 1 && visionExecutor != null;
        if (useParallel) {
            List<LlamaParseService.PageMarkdown> pending = new ArrayList<>();
            for (LlamaParseService.PageMarkdown page : pages) {
                if (needsVision(page)) {
                    pending.add(page);
                }
            }
            visionResults = runVisionParallel(pending, workspaceId);
        }
        for (LlamaParseService.PageMarkdown page : pages) {
            if (!needsVision(page)) {
                result.add(page);
                continue;
            }
            Optional<String> filled = useParallel
                    ? visionResults.getOrDefault(page.pageNumber(), Optional.empty())
                            .map(outcome -> {
                                if (outcome.usage() != null) {
                                    // 线程池内不做追踪累加（ThreadLocal 随执行线程），主线程合并
                                    TokenAccumulator.accumulate(TokenAccumulator.TYPE_VISION, outcome.usage());
                                }
                                return outcome.text();
                            })
                    : describeSequential(page, workspaceId);
            if (filled.isPresent() && !filled.get().isBlank()) {
                result.add(new LlamaParseService.PageMarkdown(page.pageNumber(),
                        VisionPageFiller.normalizeVisionMarkdown(filled.get()), page.screenshot()));
                log.info("PPTX 第 {} 页经识图补全（原 md {} 字符 → {} 字符）",
                        page.pageNumber(), page.markdown() == null ? 0 : page.markdown().trim().length(),
                        filled.get().trim().length());
            } else {
                log.warn("PPTX 第 {} 页识图补全失败/未配置，保持原样", page.pageNumber());
                result.add(page);
            }
        }
        return result;
    }

    /** 是否需要对页执行识图补全：md 为空 或 文本过短（图片内容页特征），且该页有截图 */
    private boolean needsVision(LlamaParseService.PageMarkdown page) {
        if (!visionParsing || page.screenshot() == null) {
            return false;
        }
        String md = page.markdown();
        if (md == null || md.isBlank()) {
            return true;
        }
        return md.trim().length() < visionMinText;
    }

    /** 串行路径：带追踪识图（并行度 ≤1），使用 Markdown 输出 prompt */
    private Optional<String> describeSequential(LlamaParseService.PageMarkdown page, Long workspaceId) {
        return visionOcrService.describeImageMarkdown(page.screenshot(), page.pageNumber(), workspaceId);
    }

    /** 并行路径：识图 LLM 调用进线程池（无追踪），主线程按页码收集结果 */
    private Map<Integer, Optional<VisionOcrService.VisionOutcome>> runVisionParallel(
            List<LlamaParseService.PageMarkdown> pending, Long workspaceId) {
        Map<Integer, Optional<VisionOcrService.VisionOutcome>> results = new HashMap<>();
        if (pending.isEmpty()) {
            return results;
        }
        List<CompletableFuture<Optional<VisionOcrService.VisionOutcome>>> futures = new ArrayList<>();
        for (LlamaParseService.PageMarkdown page : pending) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    visionOcrService.describeParallelMarkdown(page.screenshot(), page.pageNumber(), workspaceId),
                    visionExecutor));
        }
        for (int i = 0; i < pending.size(); i++) {
            results.put(pending.get(i).pageNumber(), futures.get(i).join());
        }
        return results;
    }

    /**
     * 提取页 md 标题作为 chunk 标题。
     * LlamaParse 的 pptx 输出中，页脚（如公司名"臻融科技"）可能以普通文本或标题形式出现在
     * 每页 md 开头，真实幻灯片标题紧随其后。策略（footer 由 {@link #detectFooter} 跨页检测）：
     * <ul>
     *   <li>首个标题 == 页脚（footer）时：≥2 个标题 → 取第二个（真实标题）；仅 1 个标题 → 取紧随的短纯文本行
     *       （如 `# 臻融科技` + `工具与服务——启动器`），无则取该标题本身；</li>
     *   <li>首个标题 != 页脚 → 取首个标题（真实标题；后续标题为正文子标题）；</li>
     *   <li>无标题 → 回退首个非空行（截断 100 字符）。</li>
     * </ul>
     */
    /**
     * 提取页 md 的标题（祖先链路径）：按行收集页内标题（见 {@link Headings}），
     * 逐级构建标题栈，返回 {@code Headings.path} 路径（如 {@code 概述 > 1.1 背景}）。
     * 首个标题为页脚（detectFooter 结果）时跳过（从第二个标题起构建栈，与旧"跳过页脚取次标题"一致）；
     * 无标题行时回退取首个非空行（截断 100）。
     */
    static String extractTitle(String md, String footer) {
        if (md == null) {
            return null;
        }
        List<String> nonEmpty = new ArrayList<>();
        List<Headings.Heading> headings = new ArrayList<>();
        for (String line : md.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nonEmpty.add(trimmed);
            Headings.Heading h = Headings.parse(trimmed);
            if (h != null) {
                headings.add(h);
            }
        }
        if (nonEmpty.isEmpty()) {
            return null;
        }
        if (headings.isEmpty()) {
            return Texts.truncate(nonEmpty.get(0), 100);
        }
        // 页脚跳过：仅当首个标题为页脚时忽略它，从第二个标题开始构建路径
        int start = 0;
        if (footer != null && headings.get(0).text().equals(footer)) {
            start = 1;
        }
        if (start >= headings.size()) {
            // 仅页脚一个标题：真实标题可能是紧随的短纯文本行
            String after = firstShortPlainLine(nonEmpty);
            if (after != null) {
                return Texts.truncate(after, 100);
            }
            return Texts.truncate(headings.get(0).text(), 100);
        }
        List<String> stack = new ArrayList<>();
        for (int i = start; i < headings.size(); i++) {
            stack = Headings.apply(stack, headings.get(i));
        }
        return Headings.path(stack);
    }

    /** 若首行是标题，返回其后第一条"短纯文本"行（≤30 字符且非列表/表格/引用/代码标记）；否则返回 null */
    private static String firstShortPlainLine(List<String> nonEmpty) {
        boolean pastHeading = false;
        for (String line : nonEmpty) {
            if (!pastHeading) {
                if (line.matches("^#{1,6}\\s+.*")) {
                    pastHeading = true;
                }
                continue;
            }
            if (line.matches("^#{1,6}\\s+.*")) {
                return null; // 又是标题：不属于"短纯文本"场景
            }
            if (line.length() > 30 || startsWithMarkup(line)) {
                return null; // 过长或列表/表格/代码内容
            }
            return line;
        }
        return null;
    }

    /** 列表/表格/引用/代码等 Markdown 结构行前缀 */
    private static boolean startsWithMarkup(String line) {
        return line.startsWith("*") || line.startsWith("-") || line.startsWith("+")
                || line.startsWith(">") || line.startsWith("|") || line.startsWith("```")
                || line.startsWith("`") || line.startsWith("[")
                || line.matches("^[0-9]+[.、．].*") || line.matches("^\\d+\\s+.*");
    }
}
