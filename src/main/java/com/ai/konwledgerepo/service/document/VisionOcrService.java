package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 识图（多模态）服务：将页面图片/截图交识图模型做 OCR / 内容描述。
 * 未配置识图模型（VISION 类型）或调用失败时返回空，调用方回退纯文本解析。
 * 调用纳入 OpenTelemetry 追踪（generation span + vision 类 token 统计）。
 */
@Service
public class VisionOcrService {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrService.class);

    private static final String PROMPT = "请识别并描述这张图片的内容（可能是扫描件、图表、表格或插图）。"
            + "输出要点式文字，尽量保留原文、数字与专有名词；仅输出图中内容，不要额外解释。";

    /**
     * 补页专用 prompt（第 1 层约束）：要求输出结构化的 Markdown，以便识别结果
     * 按页码并入 LlamaParse 的逐页 md 流后走统一递归分块（RecursiveChunkSplitter）。
     */
    private static final String MARKDOWN_PROMPT = "请将这张页面的内容完整转写为 Markdown 格式，要求："
            + "1) 保留标题层级（用 # / ## / ### 表示，与页面章节结构一致）；"
            + "2) 段落之间用空行分隔；"
            + "3) 列表、表格、代码块按 Markdown 语法输出；"
            + "4) 保留原文中的数字、专有名词与关键术语，不要遗漏正文内容；"
            + "5) 仅输出 Markdown 内容本身：不要用 ```markdown 代码围栏包裹，不要输出任何解释性前后缀。";

    private final ModelFactory modelFactory;
    private final QaTracing qaTracing;

    public VisionOcrService(ModelFactory modelFactory, QaTracing qaTracing) {
        this.modelFactory = modelFactory;
        this.qaTracing = qaTracing;
    }

    /** 是否已配置可用的识图模型（限当前工作空间） */
    public boolean isConfigured(Long workspaceId) {
        return modelFactory.getVisionModel(workspaceId).isPresent();
    }

    /** 识图模型描述/OCR 页面图片（限当前工作空间）；未配置或调用失败返回空 */
    public Optional<String> describeImage(byte[] pngBytes, int pageNum, Long workspaceId) {
        Optional<ChatModel> vision = modelFactory.getVisionModel(workspaceId);
        if (vision.isEmpty()) {
            return Optional.empty();
        }
        try {
            Media media = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(pngBytes)
                    .build();
            String text = LlmTrace.vision(qaTracing, vision.get(), PROMPT, media);
            return (text == null || text.isBlank()) ? Optional.empty() : Optional.of(text.trim());
        } catch (Exception e) {
            log.warn("识图模型解析图片失败（第 {} 页）: {}", pageNum, e.getMessage());
            return Optional.empty();
        }
    }

    /** 一次识图结果：识别文本 + token 用量（供并行路径在主线程合并统计） */
    public record VisionOutcome(String text, Usage usage) {
    }

    /**
     * 并行识图（无追踪）：由线程池 worker 直接调用模型，返回文本与 token 用量；
     * 调用方负责在主线程合并用量并记录（避免 ThreadLocal 累加器跨线程失效）。
     * 未配置模型或调用失败返回空。
     */
    public Optional<VisionOutcome> describeParallel(byte[] pngBytes, int pageNum, Long workspaceId) {
        return describeParallelWithPrompt(pngBytes, pageNum, workspaceId, PROMPT);
    }

    /** 补页专用并行识图：使用 Markdown 输出 prompt（第 1 层约束），其余同 {@link #describeParallel} */
    public Optional<VisionOutcome> describeParallelMarkdown(byte[] pngBytes, int pageNum, Long workspaceId) {
        return describeParallelWithPrompt(pngBytes, pageNum, workspaceId, MARKDOWN_PROMPT);
    }

    /** 补页专用串行识图（带追踪）：使用 Markdown 输出 prompt，供并行度 ≤1 的路径使用 */
    public Optional<String> describeImageMarkdown(byte[] pngBytes, int pageNum, Long workspaceId) {
        Optional<ChatModel> vision = modelFactory.getVisionModel(workspaceId);
        if (vision.isEmpty()) {
            return Optional.empty();
        }
        try {
            Media media = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(pngBytes)
                    .build();
            String text = LlmTrace.vision(qaTracing, vision.get(), MARKDOWN_PROMPT, media);
            return (text == null || text.isBlank()) ? Optional.empty() : Optional.of(text.trim());
        } catch (Exception e) {
            log.warn("识图模型解析图片失败（第 {} 页）: {}", pageNum, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 一页待识图任务：页码 + 该页渲染出的 PNG（可能为 null，表示渲染失败页）。
     * <p>
     * 同时承载并行识图的<b>统一骨架</b> {@link #runParallel}：各解析器（PDF / PPTX）都要
     * 「逐页提交线程池 → 主线程按输入顺序 join → 收成 pageNumber → 结果」，这段顺序约定
     * 散在多处容易抄漏。识图函数本体仍由调用方给出（PDF 用 describeParallel、
     * PPTX 用 describeParallelMarkdown），因此这里不关心 prompt 差异。
     */
    public record VisionTask(int pageNumber, byte[] pngBytes) {

        /**
         * 并行识图统一骨架。
         * <p>
         * <b>刻意做成静态方法</b>：它只是一段调度逻辑，不该挂在 {@link VisionOcrService} 实例上——
         * 那会让 mock 掉 VisionOcrService 的测试意外失去这段能力（识图 stub 全部失效），
         * 也会把「编排」与「调用模型」两件事混在一起。
         * <p>
         * worker 内不带追踪（ThreadLocal 累加器跨线程失效），token 用量由调用方在主线程合并。
         *
         * @param tasks    待识图页（顺序即结果收集顺序）
         * @param executor 识图专用线程池（并行度由池大小控制）
         * @param perPage  单页识图函数（worker 线程执行，须为无追踪版本）
         */
        public static Map<Integer, Optional<VisionOutcome>> runParallel(
                List<VisionTask> tasks, Executor executor,
                java.util.function.BiFunction<byte[], Integer, Optional<VisionOutcome>> perPage) {
            Map<Integer, Optional<VisionOutcome>> results = new HashMap<>();
            if (tasks == null || tasks.isEmpty()) {
                return results;
            }
            List<CompletableFuture<Optional<VisionOutcome>>> futures = new ArrayList<>(tasks.size());
            for (VisionTask task : tasks) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    if (task.pngBytes() == null) {
                        return Optional.<VisionOutcome>empty();
                    }
                    return perPage.apply(task.pngBytes(), task.pageNumber());
                }, executor));
            }
            for (int i = 0; i < tasks.size(); i++) {
                results.put(tasks.get(i).pageNumber(), futures.get(i).join());
            }
            return results;
        }
    }

    private Optional<VisionOutcome> describeParallelWithPrompt(byte[] pngBytes, int pageNum, Long workspaceId, String prompt) {
        Optional<ChatModel> vision = modelFactory.getVisionModel(workspaceId);
        if (vision.isEmpty()) {
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        try {
            Media media = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(pngBytes)
                    .build();
            UserMessage message = UserMessage.builder().text(prompt).media(media).build();
            ChatResponse response = vision.get().call(new Prompt(message));
            Usage usage = response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getUsage();
            String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? "" : response.getResult().getOutput().getText();
            // 并行识图不走 LlmTrace（避免 ThreadLocal token 累加器跨线程失效），但 LLM I/O 日志格式保持一致
            LlmTrace.logDirect("vision", QaTracing.modelName(vision.get()), prompt, text,
                    System.currentTimeMillis() - start, usage);
            return (text == null || text.isBlank())
                    ? Optional.empty()
                    : Optional.of(new VisionOutcome(text.trim(), usage));
        } catch (Exception e) {
            LlmTrace.logDirectError("vision", QaTracing.modelName(vision.get()), prompt, e,
                    System.currentTimeMillis() - start);
            log.warn("识图模型解析图片失败（第 {} 页）: {}", pageNum, e.getMessage());
            return Optional.empty();
        }
    }
}
