package com.ai.konwledgerepo.service.rerank;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.graph.EvidenceReranker;
import com.ai.konwledgerepo.model.ModelKeyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 交叉编码器精排客户端（按模型配置创建，非 Spring 单例）：
 * 输入 query + documents 列表，返回与输入顺序一致的 relevance_score（0~1，越高越相关）。
 * <ul>
 *   <li>供应商 DASHSCOPE：官方 text-rerank REST——POST {baseUrl}，body {model, input:{query, documents[]}, parameters:{top_n, return_documents}}，响应 output.results[{index, relevance_score}]；baseUrl 留空用官方默认端点</li>
 *   <li>供应商 OPENAI_COMPAT：Cohere/Jina 风格——POST {baseUrl}/rerank（baseUrl 如 https://api.siliconflow.cn/v1），body {model, query, documents[], top_n, return_documents}（顶层字段）；响应解析宽松适配 data / results / output.results 三种结构，分数字段兼容 relevance_score 与 score</li>
 *   <li>响应按原始字节 UTF-8 解码（无 charset 头时 StringHttpMessageConverter 默认 ISO-8859-1 会导致中文乱码）</li>
 *   <li>top_n 需覆盖全部文档才返回全量分数；单次输入按 max-docs × max-chars-per-doc 控制（默认 20×1500 字符）</li>
 * </ul>
 */
public class RerankClient implements EvidenceReranker {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    /** DashScope text-rerank 官方端点（baseUrl 留空时使用） */
    public static final String DASHSCOPE_DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final String provider;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxDocs;
    private final int maxCharsPerDoc;
    private final long timeoutMs;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RerankClient(String provider, String baseUrl, String apiKey, String model,
                        int maxDocs, int maxCharsPerDoc, long timeoutMs, ObjectMapper objectMapper) {
        this.provider = provider == null ? "" : provider.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = (model == null || model.isBlank()) ? "gte-rerank-v2" : model.trim();
        this.maxDocs = Math.max(1, maxDocs);
        this.maxCharsPerDoc = Math.max(100, maxCharsPerDoc);
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) this.timeoutMs);
        factory.setReadTimeout((int) this.timeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(endpoint())
                .requestFactory(factory)
                .build();
    }

    /**
     * 由模型配置创建精排客户端（重排模型纳入模型配置体系后唯一入口）：
     * DASHSCOPE → 官方协议（baseUrl 留空用默认端点）；OPENAI_COMPAT → Cohere/Jina 风格（baseUrl 必填）。
     * apiKey 经 {@link ModelKeyResolver} 统一解析（支持 env: 环境变量引用，真实 key 不落库）。
     */
    public static RerankClient from(ModelConfig cfg, SeuRerankProperties props, ObjectMapper mapper) {
        boolean dashscope = "DASHSCOPE".equals(cfg.getProvider());
        String baseUrl = (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank())
                ? (dashscope ? DASHSCOPE_DEFAULT_BASE_URL : "")
                : cfg.getBaseUrl().trim();
        if (!dashscope && baseUrl.isBlank()) {
            throw new BizException("OpenAI 兼容重排模型必须配置 baseUrl");
        }
        return new RerankClient(cfg.getProvider(), baseUrl, ModelKeyResolver.resolve(cfg),
                cfg.getModelName(), props.maxDocs(), props.maxCharsPerDoc(), props.timeoutMs(), mapper);
    }

    /** 连通性测试：构造客户端后对 "ping" 打一次分，成功返回 true（未配置 key / 调用失败返回 false） */
    public static boolean testConnection(ModelConfig cfg, SeuRerankProperties props, ObjectMapper mapper) {
        try {
            RerankClient client = from(cfg, props, mapper);
            if (!client.isConfigured()) {
                return false;
            }
            client.rerank("ping", List.of("ping"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 编码器是否已配置 API Key（enabled 语义由 ModelFactory 解析 enabled=true 的模型配置决定） */
    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** 单次请求允许的最大文档条数（超限时调用方自行截断/分批） */
    @Override
    public int maxDocs() {
        return maxDocs;
    }

    /**
     * 对 query 与 documents 逐条打分，返回与输入顺序一致的分数列表。
     *
     * @param query     查询文本（建议用原始用户问题）
     * @param documents 候选文档（按原样返回分数；超长文本在此截断）
     * @return 与 documents 一一对应的分数（0~1）
     * @throws BizException 未配置 / 调用失败 / 响应异常（由调用方决定降级）
     */
    @Override
    public List<Double> rerank(String query, List<String> documents) {
        if (!isConfigured()) {
            throw new BizException("交叉编码器未启用或未配置 API Key");
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<String> docs = documents.stream()
                .limit(maxDocs)
                .map(d -> d == null ? "" : (d.length() > maxCharsPerDoc ? d.substring(0, maxCharsPerDoc) : d))
                .toList();
        try {
            ObjectNode body = buildRequestBody(provider, model, query, docs);
            byte[] respBytes = restClient.post()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(body))
                    .retrieve()
                    .body(byte[].class);
            String resp = respBytes == null ? null : new String(respBytes, StandardCharsets.UTF_8);
            if (resp == null || resp.isBlank()) {
                throw new BizException("交叉编码器无响应");
            }
            return parseScores(resp, docs.size(), objectMapper);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("交叉编码器调用失败: " + e.getMessage());
        }
    }

    /** 完整请求端点：DASHSCOPE 的 baseUrl 即完整端点；OPENAI_COMPAT 追加 /rerank（已带 /rerank 则不重复） */
    private String endpoint() {
        if ("DASHSCOPE".equals(provider)) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/rerank")) {
            return baseUrl;
        }
        return baseUrl.endsWith("/") ? baseUrl + "rerank" : baseUrl + "/rerank";
    }

    /**
     * 请求体构建（纯函数，供单测）：
     * DASHSCOPE → {model, input:{query, documents}, parameters:{top_n, return_documents}}；
     * OPENAI_COMPAT → {model, query, documents, top_n, return_documents}（顶层 Cohere/Jina 风格）。
     */
    static ObjectNode buildRequestBody(String provider, String model, String query, List<String> docs) {
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("model", model);
        int topN = docs.size();
        if ("DASHSCOPE".equals(provider)) {
            ObjectNode input = body.putObject("input");
            input.put("query", query == null ? "" : query);
            ArrayNode docsNode = input.putArray("documents");
            docs.forEach(docsNode::add);
            ObjectNode params = body.putObject("parameters");
            params.put("top_n", topN);
            params.put("return_documents", false);
        } else {
            body.put("query", query == null ? "" : query);
            ArrayNode docsNode = body.putArray("documents");
            docs.forEach(docsNode::add);
            body.put("top_n", topN);
            body.put("return_documents", false);
        }
        return body;
    }

    /**
     * 响应解析（纯函数，供单测）：按 output.results → data → results 优先级取分数数组（覆盖 DashScope /
     * OpenAI 风格 / Cohere-Jina 风格），分数字段兼容 relevance_score 与 score，按 index 回填（缺省记 0）。
     */
    static List<Double> parseScores(String resp, int size, ObjectMapper mapper) throws Exception {
        JsonNode root = mapper.readTree(resp);
        JsonNode arr = root.path("output").path("results");
        if (!arr.isArray()) {
            arr = root.path("data");
        }
        if (!arr.isArray()) {
            arr = root.path("results");
        }
        if (!arr.isArray()) {
            throw new BizException("交叉编码器响应缺少分数数组（output.results / data / results）: " + resp);
        }
        double[] scores = new double[size];
        for (JsonNode r : arr) {
            int index = r.path("index").asInt(-1);
            if (index >= 0 && index < size) {
                JsonNode scoreNode = r.path("relevance_score");
                if (!scoreNode.isNumber()) {
                    scoreNode = r.path("score");
                }
                scores[index] = scoreNode.asDouble(0.0);
            }
        }
        List<Double> list = new ArrayList<>(size);
        for (double s : scores) {
            list.add(s);
        }
        return list;
    }
}
