package com.ai.konwledgerepo.service.rerank;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重排客户端测试（不触发网络）：模型配置装配、双供应商请求体协议、
 * 宽松响应解析（output.results / data / results + score 兜底）。
 */
class RerankClientTest {

    private static final SeuRerankProperties PROPS = new SeuRerankProperties(4, 4, 20, 1500, 10000);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelConfig cfg(String provider, String baseUrl, String apiKey) {
        ModelConfig c = new ModelConfig();
        c.setProvider(provider);
        c.setModelType("RERANK");
        c.setUsage("RERANK");
        c.setModelName("gte-rerank-v2");
        c.setBaseUrl(baseUrl);
        c.setApiKey(apiKey);
        c.setEnabled(true);
        return c;
    }

    // ===== 静态工厂装配 =====

    @Test
    void from_dashscopeBlankBaseUrl_usesOfficialDefault() {
        RerankClient client = RerankClient.from(cfg("DASHSCOPE", null, "sk-test"), PROPS, MAPPER);
        assertTrue(client.isConfigured());
    }

    @Test
    void from_openAiCompatBlankBaseUrl_throws() {
        BizException e = assertThrows(BizException.class,
                () -> RerankClient.from(cfg("OPENAI_COMPAT", null, "sk-test"), PROPS, MAPPER));
        assertTrue(e.getMessage().contains("baseUrl"), "OpenAI 兼容重排模型必须配置 baseUrl");
    }

    @Test
    void from_blankApiKey_notConfigured() {
        RerankClient client = RerankClient.from(cfg("DASHSCOPE", null, ""), PROPS, MAPPER);
        assertFalse(client.isConfigured(), "apiKey 为空不应视为已配置");
    }

    @Test
    void testConnection_blankApiKey_returnsFalseWithoutNetwork() {
        assertFalse(RerankClient.testConnection(cfg("DASHSCOPE", null, ""), PROPS, MAPPER));
    }

    // ===== 请求体协议 =====

    @Test
    void buildRequestBody_dashscope_usesInputAndParametersWrapping() {
        ObjectNode body = RerankClient.buildRequestBody("DASHSCOPE", "gte-rerank-v2", "查询", List.of("d1", "d2"));
        assertEquals("gte-rerank-v2", body.path("model").asText());
        assertEquals("查询", body.path("input").path("query").asText());
        assertEquals(2, body.path("input").path("documents").size());
        assertEquals(2, body.path("parameters").path("top_n").asInt());
        assertFalse(body.path("parameters").path("return_documents").asBoolean());
    }

    @Test
    void buildRequestBody_openAiCompat_usesTopLevelFields() {
        ObjectNode body = RerankClient.buildRequestBody("OPENAI_COMPAT", "rerank-model", "查询", List.of("d1"));
        assertEquals("rerank-model", body.path("model").asText());
        assertEquals("查询", body.path("query").asText());
        assertEquals(1, body.path("documents").size());
        assertEquals(1, body.path("top_n").asInt());
        assertFalse(body.path("return_documents").asBoolean());
        assertFalse(body.has("input"), "OpenAI 兼容风格不应有 input 包裹");
    }

    // ===== 响应解析（宽松适配三种结构） =====

    @Test
    void parseScores_dashscopeOutputResults() throws Exception {
        String resp = "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.3}]}}";
        List<Double> scores = RerankClient.parseScores(resp, 2, MAPPER);
        assertEquals(List.of(0.3, 0.9), scores, "按 index 回填到输入顺序");
    }

    @Test
    void parseScores_openAiStyleDataArray() throws Exception {
        String resp = "{\"data\":[{\"index\":0,\"relevance_score\":0.8},{\"index\":1,\"relevance_score\":0.2}]}";
        assertEquals(List.of(0.8, 0.2), RerankClient.parseScores(resp, 2, MAPPER));
    }

    @Test
    void parseScores_cohereJinaStyleResults() throws Exception {
        String resp = "{\"results\":[{\"index\":1,\"relevance_score\":0.7},{\"index\":0,\"relevance_score\":0.4}]}";
        assertEquals(List.of(0.4, 0.7), RerankClient.parseScores(resp, 2, MAPPER));
    }

    @Test
    void parseScores_scoreFieldFallbackAndMissingIndexZero() throws Exception {
        // 分数字段兼容 score；缺失 index 按 0 计
        String resp = "{\"results\":[{\"index\":0,\"score\":0.6}]}";
        assertEquals(List.of(0.6, 0.0), RerankClient.parseScores(resp, 2, MAPPER));
    }

    @Test
    void parseScores_noArray_throws() {
        String resp = "{\"foo\":\"bar\"}";
        assertThrows(BizException.class, () -> RerankClient.parseScores(resp, 2, MAPPER));
    }

    @Test
    void buildRequestBody_roundTrip_parseable() throws Exception {
        ObjectNode body = RerankClient.buildRequestBody("DASHSCOPE", "gte-rerank-v2", "q", List.of("a", "b"));
        JsonNode parsed = MAPPER.readTree(MAPPER.writeValueAsBytes(body));
        assertEquals(2, parsed.path("input").path("documents").size());
    }
}
