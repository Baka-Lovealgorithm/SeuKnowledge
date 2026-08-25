package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.config.props.SeuAsyncProperties;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.config.props.SeuEsProperties;
import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.config.props.SeuRateLimitProperties;
import com.ai.konwledgerepo.config.props.SeuRecallProperties;
import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.config.props.SeuSecurityProperties;
import com.ai.konwledgerepo.config.props.SeuTracingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 集中注册 seuknowledge.* 的 @ConfigurationProperties 绑定类，
 * 业务类经构造器注入属性对象，不再散落 @Value。
 */
@Configuration
@EnableConfigurationProperties({
        SeuSecurityProperties.class,
        SeuCacheProperties.class,
        SeuRateLimitProperties.class,
        SeuFileProperties.class,
        SeuQaProperties.class,
        SeuDocumentProperties.class,
        SeuRecallProperties.class,
        SeuRerankProperties.class,
        SeuAsyncProperties.class,
        SeuEsProperties.class,
        SeuTracingProperties.class
})
public class SeuPropertiesConfig {
}
