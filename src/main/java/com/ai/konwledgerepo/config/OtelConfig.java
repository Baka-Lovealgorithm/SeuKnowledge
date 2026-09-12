package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.config.props.SeuTracingProperties;
import com.ai.konwledgerepo.tracing.QaTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * OpenTelemetry 追踪引导：从配置构建 OTLP SDK / Tracer（Langfuse 可视化）。
 * 未配置 endpoint / Langfuse keys 或初始化失败时自动降级为 no-op，不影响业务。
 * {@link QaTracing} 仅消费 Tracer，span 语义与 token 汇总职责分离（后者见 TokenAccumulator）。
 */
@Configuration
public class OtelConfig {

    private static final Logger log = LoggerFactory.getLogger(OtelConfig.class);

    /** 是否满足 Langfuse 上报条件：开关打开且 endpoint / 公钥 / 私钥齐全 */
    private static boolean langfuseActive(SeuTracingProperties props) {
        return props.enabled() && notBlank(props.endpoint())
                && notBlank(props.langfusePublicKey()) && notBlank(props.langfuseSecretKey());
    }

    @Bean(destroyMethod = "")
    public Tracer qaTracer(SeuTracingProperties tracingProps) {
        if (!langfuseActive(tracingProps)) {
            if (tracingProps.enabled()) {
                log.info("OpenTelemetry 追踪未启用（缺少 endpoint 或 Langfuse keys），保持 no-op");
            }
            return OpenTelemetry.noop().getTracer("seuknowledge-qa");
        }
        try {
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(tracingProps.endpoint())
                    // Langfuse OTel 摄取认证：Basic auth（pk:sk）与 Langfuse 专用头双保险
                    .addHeader("Authorization",
                            "Basic " + Base64.getEncoder().encodeToString(
                                    (tracingProps.langfusePublicKey() + ":" + tracingProps.langfuseSecretKey())
                                            .getBytes(StandardCharsets.UTF_8)))
                    .addHeader("Langfuse-Public-Key", tracingProps.langfusePublicKey())
                    .addHeader("Langfuse-Secret-Key", tracingProps.langfuseSecretKey())
                    .addHeader("Langfuse-SDK-Name", "seuknowledge-java")
                    .addHeader("Langfuse-SDK-Version", "1.0.0")
                    .build();
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(Resource.create(Attributes.of(
                            AttributeKey.stringKey("service.name"), tracingProps.serviceName())))
                    // BatchSpanProcessor 异步批量上报：此前用 SimpleSpanProcessor，每个 span.end() 都同步
                    // HTTPS POST Langfuse（一次问答几十个 span = 几十次串行阻塞外网往返），Langfuse 稍慢
                    // 就拖垮问答与解析链路。批量异步上报后 span 仅入内存队列，由后台线程定时刷出。
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
            log.info("OpenTelemetry 追踪已启用，OTLP 上报端点: {}", tracingProps.endpoint());
            return sdk.getTracer("seuknowledge-qa");
        } catch (Exception e) {
            log.warn("OpenTelemetry 初始化失败，追踪降级为 no-op: {}", e.getMessage());
            return OpenTelemetry.noop().getTracer("seuknowledge-qa");
        }
    }

    @Bean
    public QaTracing qaTracing(Tracer qaTracer, SeuTracingProperties tracingProps) {
        return new QaTracing(qaTracer, langfuseActive(tracingProps));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
