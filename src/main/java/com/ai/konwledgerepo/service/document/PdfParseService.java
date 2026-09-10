package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.ai.konwledgerepo.tracing.TokenAccumulator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * PDF 解析（PDFBox 纯文本 + 按需识图）：逐页提取文本，auto 模式按「含图片 / 文本过少」判定识图，
 * 支持串行（带追踪）与并行（识图进线程池，主线程合并 token）两条路径，跨页 carry 延续分块。
 * LlamaParse 优先路径见 {@link DocumentParserService}；缺页补全见 {@link VisionPageFiller}。
 */
@Component
public class PdfParseService {

    private static final Logger log = LoggerFactory.getLogger(PdfParseService.class);

    private final VisionOcrService visionOcrService;
    private final QaTracing qaTracing;
    private final boolean visionParsing;
    private final boolean visionAuto;
    private final int visionMinText;
    private final int visionDpi;
    private final int visionParallel;
    private final Executor visionExecutor;
    private final int chunkSize;
    private final int chunkOverlap;

    public PdfParseService(VisionOcrService visionOcrService,
                           QaTracing qaTracing,
                           SeuDocumentProperties docProps,
                           @Qualifier("visionTaskExecutor") Executor visionExecutor) {
        this.visionOcrService = visionOcrService;
        this.qaTracing = qaTracing;
        this.visionParsing = docProps.visionParsing();
        this.visionAuto = docProps.visionAuto();
        this.visionMinText = docProps.visionMinText();
        this.visionDpi = docProps.visionDpi();
        this.visionParallel = docProps.visionParallel();
        this.visionExecutor = visionExecutor;
        this.chunkSize = docProps.chunkSize();
        this.chunkOverlap = docProps.chunkOverlap();
    }

    /** PDFBox 解析 PDF：串行 / 并行按配置分流 */
    public List<ChunkPiece> parse(Path path, Long workspaceId) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            boolean visionEnabled = visionParsing && visionOcrService.isConfigured(workspaceId);
            PDFRenderer renderer = visionEnabled ? new PDFRenderer(document) : null;
            // 并行度 >1 且有线程池时走并行识图；否则串行（保留带追踪的既有行为）
            boolean useParallel = visionEnabled && visionParallel > 1 && visionExecutor != null;
            if (useParallel) {
                return parseParallel(document, stripper, totalPages, renderer, workspaceId);
            }
            return parseSequential(document, stripper, totalPages, renderer, workspaceId);
        }
    }

    /** 串行解析（并行度 ≤1）：每页渲染 + 带追踪识图，跨页 carry 延续 */
    private List<ChunkPiece> parseSequential(PDDocument document, PDFTextStripper stripper,
                                             int totalPages, PDFRenderer renderer, Long workspaceId) throws IOException {
        List<ChunkPiece> pieces = new ArrayList<>();
        String[] carry = new String[1];
        for (int page = 1; page <= totalPages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(document);
            StringBuilder pageText = new StringBuilder(text == null ? "" : text);
            if (renderer != null && needVision(document, page, text)) {
                Optional<String> description = describePage(renderer, page, workspaceId);
                if (description.isPresent()) {
                    pageText.append("\n【图片/图表识别】").append(description.get());
                }
            }
            chunkPage(pageText, page, pieces, carry);
        }
        return pieces;
    }

    /**
     * 并行解析：主线程逐页提取文本 + 判定识图 + 渲染 PNG（PDDocument 非线程安全，渲染不进线程池）；
     * 识图 LLM 调用提交线程池并行执行；主线程按页码顺序收集结果、合并 vision token 统计后分块。
     */
    private List<ChunkPiece> parseParallel(PDDocument document, PDFTextStripper stripper,
                                           int totalPages, PDFRenderer renderer, Long workspaceId) throws IOException {
        List<ChunkPiece> pieces = new ArrayList<>();
        List<PageText> pageTexts = new ArrayList<>();
        List<PendingVision> pending = new ArrayList<>();
        for (int page = 1; page <= totalPages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(document);
            pageTexts.add(new PageText(page, text == null ? "" : text));
            if (renderer != null && needVision(document, page, text)) {
                pending.add(new PendingVision(page, PdfPages.renderPng(renderer, page, visionDpi)));
            }
        }
        Map<Integer, Optional<VisionOcrService.VisionOutcome>> visionResults = runVisionParallel(pending, workspaceId);
        String[] carry = new String[1];
        for (PageText pt : pageTexts) {
            StringBuilder pageText = new StringBuilder(pt.text());
            Optional<VisionOcrService.VisionOutcome> outcome = visionResults.get(pt.page());
            if (outcome != null && outcome.isPresent()) {
                pageText.append("\n【图片/图表识别】").append(outcome.get().text());
                if (outcome.get().usage() != null) {
                    // 线程池内不做追踪累加（ThreadLocal 随执行线程），统一在主线程合并 vision token
                    TokenAccumulator.accumulate(TokenAccumulator.TYPE_VISION, outcome.get().usage());
                }
            }
            chunkPage(pageText, pt.page(), pieces, carry);
        }
        return pieces;
    }

    private record PageText(int page, String text) {
    }

    private record PendingVision(int page, byte[] png) {
    }

    /** 一页分块：跨页 carry 延续（空页保持 carry 不打断上下文流） */
    private void chunkPage(StringBuilder pageText, int page, List<ChunkPiece> pieces, String[] carry) {
        if (!pageText.toString().isBlank()) {
            ChunkSplitter.SplitResult sr = ChunkSplitter.splitWithCarry(
                    pageText.toString(), page, chunkSize, chunkOverlap, carry[0]);
            pieces.addAll(sr.pieces());
            carry[0] = sr.carryOut().isEmpty() ? null : sr.carryOut();
        }
    }

    /** 渲染页面为 PNG 并交识图模型；渲染或调用失败回退纯文本 */
    private Optional<String> describePage(PDFRenderer renderer, int page, Long workspaceId) {
        try {
            byte[] png = PdfPages.renderPng(renderer, page, visionDpi);
            if (png == null) {
                return Optional.empty();
            }
            return visionOcrService.describeImage(png, page, workspaceId);
        } catch (Exception e) {
            log.warn("PDF 第 {} 页渲染/识图失败，回退纯文本: {}", page, e.getMessage());
            return Optional.empty();
        }
    }

    /** 并行识图：识图调用进线程池（无追踪），主线程按页码顺序收集；渲染失败页返回空 */
    private Map<Integer, Optional<VisionOcrService.VisionOutcome>> runVisionParallel(
            List<PendingVision> pending, Long workspaceId) {
        List<VisionOcrService.VisionTask> tasks = pending.stream()
                .map(pv -> new VisionOcrService.VisionTask(pv.page(), pv.png()))
                .toList();
        return VisionOcrService.VisionTask.runParallel(tasks, visionExecutor,
                (png, page) -> visionOcrService.describeParallel(png, page, workspaceId));
    }

    /**
     * 按需识图判定：auto 模式下仅当页面含图片或文本过少（疑似扫描页）才渲染识图；
     * auto 关闭时每页识图（旧行为）。
     */
    private boolean needVision(PDDocument document, int page, String text) {
        if (!visionAuto) {
            return true;
        }
        int textLen = text == null ? 0 : text.trim().length();
        boolean hasImages = PdfPages.hasEmbeddedImages(document, page);
        if (hasImages || textLen < visionMinText) {
            return true;
        }
        log.debug("PDF 第 {} 页跳过识图：纯文字页（{} 字符，无图片）", page, textLen);
        return false;
    }
}
