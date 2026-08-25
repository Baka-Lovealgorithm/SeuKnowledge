package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LlamaParse 托管解析客户端（官方 REST API，端点与请求格式按官方 TS SDK 核实，无新增依赖）：
 * <ol>
 *   <li>multipart 上传建任务：POST /api/v1/parsing/upload（file + tier + language + parsing_instruction 扁平字段）→ 返回任务 id</li>
 *   <li>轮询状态：GET /api/v1/parsing/job/{jobId} → PENDING / SUCCESS / PARTIAL_SUCCESS / ERROR / CANCELLED</li>
 *   <li>取结果：GET /api/v1/parsing/job/{jobId}/result/json → {pages:[{page, md, ...}]}（逐页 markdown，保留页码）；
 *       接口异常时回退 GET /api/v1/parsing/job/{jobId}/result/markdown（整篇 markdown，页码置 0）</li>
 * </ol>
 * 输出为逐页 Markdown，可直接保留页码元数据做分块（与现有 ChunkPiece.pageNum 对齐）。
 * 可选将转换结果导出到 output-dir 便于检查转化效果。
 */
@Service
public class LlamaParseService {

    private static final Logger log = LoggerFactory.getLogger(LlamaParseService.class);

    /** LlamaParse 远端任务终态（保留字面量，仅收敛为类内常量） */
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_PARTIAL_SUCCESS = "PARTIAL_SUCCESS";

    /**
     * 单页 Markdown 转换结果。
     *
     * @param pageNumber 页码（PDF/PPTX 均为 1 起始物理页；整篇 markdown 回退时为 0）
     * @param markdown   该页转换结果
     * @param screenshot 整页截图 PNG 字节（take_screenshot=true 时由 LlamaParse 返回；可能为空）
     */
    public record PageMarkdown(int pageNumber, String markdown, byte[] screenshot) {

        public PageMarkdown(int pageNumber, String markdown) {
            this(pageNumber, markdown, null);
        }
    }

    private final boolean enabled;
    private final String apiKey;
    private final String baseUrl;
    private final String tier;
    private final String version;
    private final List<String> languages;
    private final long pollIntervalSeconds;
    private final long maxPollSeconds;
    private final String parsingInstruction;
    private final boolean takeScreenshot;
    private final int visionMinText;
    private final Path outputDir;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LlamaParseService(SeuDocumentProperties docProps, ObjectMapper objectMapper) {
        SeuDocumentProperties.LlamaParse lp = docProps.llamaparse();
        this.enabled = lp.enabled();
        this.apiKey = lp.apiKey() == null ? "" : lp.apiKey().trim();
        this.baseUrl = (lp.baseUrl() == null || lp.baseUrl().isBlank())
                ? "https://api.cloud.llamaindex.ai" : lp.baseUrl().trim();
        this.tier = (lp.tier() == null || lp.tier().isBlank()) ? "cost_effective" : lp.tier().trim();
        this.version = (lp.version() == null || lp.version().isBlank()) ? "latest" : lp.version().trim();
        this.languages = new ArrayList<>();
        String language = lp.language();
        if (language != null) {
            for (String s : language.split(",")) {
                if (!s.isBlank()) {
                    this.languages.add(s.trim());
                }
            }
        }
        if (this.languages.isEmpty()) {
            this.languages.add("ch_sim");
        }
        this.pollIntervalSeconds = Math.max(1, lp.pollIntervalSeconds());
        this.maxPollSeconds = Math.max(30, lp.maxPollSeconds());
        this.parsingInstruction = lp.parsingInstruction();
        this.takeScreenshot = lp.takeScreenshot();
        this.visionMinText = Math.max(1, docProps.visionMinText());
        String outputDir = lp.outputDir();
        this.outputDir = outputDir == null || outputDir.isBlank() ? null : Path.of(outputDir);
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(120_000);
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** LlamaParse 是否启用且已配置 API Key */
    public boolean isConfigured() {
        return enabled && !apiKey.isBlank();
    }

    /**
     * 解析文件为逐页 Markdown（上传 → 轮询 → 取结果）。
     * 等价于 {@link #parseToMarkdown(Path, String, boolean) parseToMarkdown(file, fileName, true)}：
     * 需要整页截图（PPTX 图片内容页识图补全依赖）。
     *
     * @throws BizException 未配置 / 任务失败 / 超时 / 网络异常
     */
    public List<PageMarkdown> parseToMarkdown(Path file, String fileName) {
        return parseToMarkdown(file, fileName, true);
    }

    /**
     * 解析文件为逐页 Markdown（上传 → 轮询 → 取结果）。
     *
     * @param needScreenshots 是否下载整页截图。仅当 true 时下载"文字识别数较少"页
     *                        （md 为空或 &lt; vision-min-text）的截图（PPTX 识图补全用）；
     *                        PDF 路径传 false 不下载任何截图（PDF 缺页补全用 PDFBox 渲染，不读截图）。
     * @throws BizException 未配置 / 任务失败 / 超时 / 网络异常
     */
    public List<PageMarkdown> parseToMarkdown(Path file, String fileName, boolean needScreenshots) {
        if (!isConfigured()) {
            throw new BizException("LlamaParse 未启用或未配置 API Key");
        }
        try {
            String jobId = uploadAndCreateJob(file, fileName);
            log.info("LlamaParse 任务已创建 jobId={} file={}", jobId, fileName);
            String status = pollJob(jobId);
            log.info("LlamaParse 任务完成 jobId={} status={} file={}", jobId, status, fileName);
            return fetchMarkdown(jobId, fileName, needScreenshots);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("LlamaParse 解析失败: " + e.getMessage());
        }
    }

    /**
     * 检测 LlamaParse 逐页结果中缺失的物理页：用 PDFBox 读取 PDF 实际总页数，
     * 与返回结果中「md 非空」的页码集合做差集。
     *
     * <p>语义：既覆盖「服务端根本没返回该页」，也覆盖「页存在但 md 为空被 fetchMarkdown 过滤」——
     * 后者不在集合里，自然落入差集。返回页码按升序。
     *
     * <p>pages 含 pageNum==0（整篇 markdown 回退）时无法定位页码，放弃页级检测返回空；
     * PDF 读取异常时告警并返回空（不阻断主流程，保持缺页静默的旧行为为兜底）。
     */
    public List<Integer> detectMissingPages(Path pdfFile, List<PageMarkdown> pages) {
        boolean wholeMarkdownFallback = pages.stream().anyMatch(p -> p.pageNumber() <= 0);
        if (wholeMarkdownFallback) {
            log.warn("LlamaParse 结果为整篇 markdown（无页码），跳过缺页检测");
            return List.of();
        }
        try (PDDocument document = Loader.loadPDF(pdfFile.toFile())) {
            int total = document.getNumberOfPages();
            Set<Integer> returned = pages.stream()
                    .filter(p -> p.markdown() != null && !p.markdown().isBlank())
                    .map(PageMarkdown::pageNumber)
                    .collect(Collectors.toSet());
            List<Integer> missing = new ArrayList<>();
            for (int page = 1; page <= total; page++) {
                if (!returned.contains(page)) {
                    missing.add(page);
                }
            }
            if (!missing.isEmpty()) {
                log.warn("LlamaParse 缺页检测：PDF 共 {} 页，返回 {} 页，缺失 {}", total, returned.size(), missing);
            }
            return missing;
        } catch (Exception e) {
            log.warn("LlamaParse 缺页检测失败（PDF 读取异常），跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /** multipart 上传文件并创建解析任务，返回任务 id（字段扁平化，与官方 SDK 一致） */
    private String uploadAndCreateJob(Path file, String fileName) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        form.add("tier", tier);
        form.add("version", version);
        // 官方 SDK 以单个字符串传语言（如 "ch_sim"），多语言用逗号分隔
        form.add("language", String.join(",", languages));
        if (takeScreenshot) {
            // 返回每页整页截图（type=full_page_screenshot），供 PPTX 图片内容页识图补全使用
            form.add("take_screenshot", "true");
        }
        if (parsingInstruction != null && !parsingInstruction.isBlank()) {
            form.add("parsing_instruction", parsingInstruction);
        }
        String body = getUtf8String(restClient.post()
                .uri("/api/v1/parsing/upload")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(byte[].class));
        if (body == null || body.isBlank()) {
            throw new BizException("LlamaParse 上传无响应");
        }
        JsonNode node = objectMapper.readTree(body);
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new BizException("LlamaParse 上传失败: " + body);
        }
        return id;
    }

    /** 轮询任务状态直到终态；返回 SUCCESS 或 PARTIAL_SUCCESS，其余抛异常 */
    private String pollJob(String jobId) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < maxPollSeconds * 1000) {
            try {
                Thread.sleep(pollIntervalSeconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException("LlamaParse 轮询被中断");
            }
            JsonNode node = getJson("/api/v1/parsing/job/" + jobId);
            String status = node.path("status").asText("");
            if (STATUS_SUCCESS.equals(status) || STATUS_PARTIAL_SUCCESS.equals(status)) {
                return status;
            }
            if ("ERROR".equals(status) || "CANCELLED".equals(status)) {
                throw new BizException("LlamaParse 任务失败 job=" + jobId + " status=" + status
                        + " error_code=" + node.path("error_code").asText("")
                        + " error_message=" + node.path("error_message").asText(""));
            }
            log.info("LlamaParse job {} 状态 {}（已等待 {}s）", jobId, status,
                    (System.currentTimeMillis() - start) / 1000);
        }
        throw new BizException("LlamaParse 任务超时 job=" + jobId
                + "（超过 " + maxPollSeconds + "s）");
    }

    /**
     * 取逐页 Markdown（JSON 结果优先，保留页码）；失败回退整篇 markdown；可选导出到 output-dir。
     * 整页截图按需下载：仅下载 needScreenshots 且文字识别数较少的页（md 为空或 &lt; vision-min-text），
     * 其余页 screenshot 保持 null（避免全量下载浪费；PDF 路径 needScreenshots=false 时不下载任何截图）。
     */
    private List<PageMarkdown> fetchMarkdown(String jobId, String fileName, boolean needScreenshots) throws IOException {
        List<PageMarkdown> pages = new ArrayList<>();
        try {
            JsonNode root = getJson("/api/v1/parsing/job/" + jobId + "/result/json");
            JsonNode pagesNode = root.path("pages");
            if (pagesNode.isArray()) {
                // 先解析 md，收集需要下载截图的页（截图下载走并行，且仅文字较少页需要）
                List<PageScreenshotTask> pendingShots = new ArrayList<>();
                for (JsonNode p : pagesNode) {
                    int pageNum = p.path("page").asInt(0);
                    String md = p.path("md").asText("");
                    String shotName = takeScreenshot ? extractScreenshotName(p) : null;
                    boolean needShot = needScreenshots && shotName != null && needScreenshot(md, visionMinText);
                    if (!md.isBlank() || needShot) {
                        pages.add(new PageMarkdown(pageNum, md, null));
                        if (needShot) {
                            pendingShots.add(new PageScreenshotTask(pageNum, shotName));
                        }
                    }
                }
                // 并行下载整页截图（仅文字较少页；每张 ~5s，按需通常 1~2 张，避免全量 81 张约 1 分钟）
                Map<Integer, byte[]> shots = downloadScreenshotsParallel(jobId, pendingShots);
                for (int i = 0; i < pages.size(); i++) {
                    PageMarkdown pg = pages.get(i);
                    if (shots.containsKey(pg.pageNumber())) {
                        pages.set(i, new PageMarkdown(pg.pageNumber(), pg.markdown(), shots.get(pg.pageNumber())));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("LlamaParse 取 JSON 逐页结果失败（回退整篇 markdown）: {}", e.getMessage());
        }
        if (pages.isEmpty()) {
            JsonNode markdown = getJson("/api/v1/parsing/job/" + jobId + "/result/markdown");
            String whole = markdown.path("markdown").asText("");
            if (!whole.isBlank()) {
                pages.add(new PageMarkdown(0, whole));
            }
        }
        if (pages.isEmpty()) {
            throw new BizException("LlamaParse 结果无可用页面（请检查 tier 是否支持 markdown 输出）");
        }
        exportIfNeeded(fileName, pages);
        return pages;
    }

    /** 待下载截图任务：页码 + 图片名 */
    private record PageScreenshotTask(int pageNumber, String name) {
    }

    /**
     * 是否需要下载该页整页截图：文字识别数较少（md 为空或字符数 &lt; vision-min-text）的页面
     * 判定为图片内容页，截图供识图模型转写补全（与 PptxParserService.needsVision 阈值同源）。
     */
    static boolean needScreenshot(String md, int visionMinText) {
        if (md == null || md.isBlank()) {
            return true;
        }
        return md.trim().length() < visionMinText;
    }

    /**
     * 从单页结果中提取整页截图（take_screenshot=true 时返回）的图片名：
     * pages[].images[] 中 type=full_page_screenshot 的条目 name（如 page_1.jpg）。
     * 图片字节需从独立端点 GET /api/v1/parsing/job/{jobId}/result/image/{name} 下载。
     *
     * @return 截图图片名；无截图返回 null
     */
    private String extractScreenshotName(JsonNode page) {
        if (page == null || !page.has("images")) {
            return null;
        }
        try {
            JsonNode images = page.path("images");
            for (JsonNode img : images) {
                if ("full_page_screenshot".equals(img.path("type").asText(""))) {
                    String name = img.path("name").asText("");
                    return (name == null || name.isBlank()) ? null : name;
                }
            }
        } catch (Exception e) {
            log.warn("LlamaParse 提取整页截图名称失败: {}", e.getMessage());
        }
        return null;
    }

    /** 并行下载整页截图：8 并发固定线程池，避免大文档串行拉取过慢；单张失败不影响其余页 */
    private Map<Integer, byte[]> downloadScreenshotsParallel(String jobId, List<PageScreenshotTask> pending) {
        Map<Integer, byte[]> result = new java.util.concurrent.ConcurrentHashMap<>();
        if (pending.isEmpty()) {
            return result;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(8, pending.size()));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (PageScreenshotTask task : pending) {
                futures.add(pool.submit(() -> {
                    byte[] bytes = downloadScreenshot(jobId, task.name());
                    if (bytes != null) {
                        result.put(task.pageNumber(), bytes);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.warn("LlamaParse 截图下载任务失败: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        log.info("LlamaParse 整页截图下载完成：{}/{} 张成功", result.size(), pending.size());
        return result;
    }

    /** 单张整页截图下载（独立图片端点，与官方 SDK 一致：/result/image/{name}） */
    private byte[] downloadScreenshot(String jobId, String name) {
        try {
            byte[] bytes = restClient.get()
                    .uri("/api/v1/parsing/job/" + jobId + "/result/image/" + name)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                log.warn("LlamaParse 整页截图下载为空（{}）", name);
                return null;
            }
            return bytes;
        } catch (Exception e) {
            log.warn("LlamaParse 整页截图下载失败（{}）: {}", name, e.getMessage());
            return null;
        }
    }

    private void exportIfNeeded(String fileName, List<PageMarkdown> pages) {
        if (outputDir == null) {
            return;
        }
        try {
            Files.createDirectories(outputDir);
            String safe = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            StringBuilder sb = new StringBuilder();
            for (PageMarkdown page : pages) {
                sb.append("\n<!-- PAGE ").append(page.pageNumber()).append(" -->\n").append(page.markdown());
            }
            Files.writeString(outputDir.resolve(safe + ".md"), sb.toString(), StandardCharsets.UTF_8);
            log.info("LlamaParse 转换结果已导出: {}", outputDir.resolve(safe + ".md"));
        } catch (IOException e) {
            log.warn("LlamaParse 结果导出失败: {}", e.getMessage());
        }
    }

    private JsonNode getJson(String path) {
        try {
            String body = getString(path);
            if (body == null || body.isBlank()) {
                throw new BizException("LlamaParse 接口无响应: " + path);
            }
            return objectMapper.readTree(body);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("LlamaParse 接口调用失败 " + path + ": " + e.getMessage());
        }
    }

    private String getString(String path) {
        byte[] bytes = restClient.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .retrieve()
                .body(byte[].class);
        return getUtf8String(bytes);
    }

    /** 响应字节按 UTF-8 解码（响应头无 charset 时 StringHttpMessageConverter 默认 ISO-8859-1 会导致中文乱码） */
    private static String getUtf8String(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
