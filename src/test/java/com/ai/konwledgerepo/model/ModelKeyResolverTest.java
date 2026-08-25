package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.entity.ModelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelKeyResolverTest {

    @Test
    void plainKey_returnsAsIs() {
        assertEquals("sk-123", ModelKeyResolver.resolve(cfg("sk-123")));
    }

    @Test
    void envPrefix_readsEnvironmentVariable() {
        assertEquals(System.getenv("alibaba_api_key"), ModelKeyResolver.resolve(cfg("env:alibaba_api_key")));
    }

    @Test
    void nullKey_returnsNull() {
        assertNull(ModelKeyResolver.resolve(cfg(null)));
    }

    @Test
    void blankKey_returnsBlank() {
        assertEquals("", ModelKeyResolver.resolve(cfg("")));
    }

    private ModelConfig cfg(String apiKey) {
        ModelConfig cfg = new ModelConfig();
        cfg.setApiKey(apiKey);
        return cfg;
    }
}
