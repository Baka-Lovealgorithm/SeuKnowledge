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

import java.util.Optional;

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
