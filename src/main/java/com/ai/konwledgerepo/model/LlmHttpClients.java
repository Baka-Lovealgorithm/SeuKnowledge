package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.tracing.LlmTrace;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * LLM 供应商 HTTP 客户端公共构建：为 blocking（RestClient）请求统一补上 connect / read 超时。
 * <p>
 * 此前 OpenAI 兼容与 DashScope 的 {@code XxxApi.builder()} 都不设 request factory 超时，
 * read 默认无限——{@code LlmTrace.awaitWithTimeout} 的 {@code future.cancel(true)} 打不断
 * 阻塞中的 socket I/O，挂死的供应商连接会一直占用线程与连接，反复超时即累积泄漏。
 * <p>
 * 读超时取「单次 LLM 逻辑超时 + 5s 余量」（跟随 {@link LlmTrace#currentTimeoutMillis()}）：
 * socket 读超时按次读生效，流式响应两次 token 间隔超过它才断开，正常流式不受影响；
 * 调大 {@code KB_LLM_TIMEOUT} 时读超时自动跟随，不会反过来先撞读超时。
 * 流式（stream）走 WebClient 路径，本类不覆盖——该路径已有 LlmTrace 的总时长兜底。
 */
public final class LlmHttpClients {

    /** 连接超时：建连失败快速暴露（对不上模型端点不必等系统默认的数十秒） */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** 逻辑超时之上的读超时余量 */
    private static final int READ_TIMEOUT_SLACK_MS = 5_000;

    private LlmHttpClients() {
    }

    /** 带 connect/read 超时的 RestClient.Builder（各 LLM 供应商 Api builder 的 restClientBuilder 入参） */
    public static RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(checkedReadTimeout());
        return RestClient.builder().requestFactory(factory);
    }

    private static int checkedReadTimeout() {
        long read = LlmTrace.currentTimeoutMillis() + READ_TIMEOUT_SLACK_MS;
        return (int) Math.min(read, Integer.MAX_VALUE);
    }
}
