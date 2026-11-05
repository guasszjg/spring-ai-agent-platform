package com.shimeta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleClientCachedTokensTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private int parse(String usage) throws Exception {
        return OpenAiCompatibleClient.cachedPromptTokens(mapper.readTree(usage));
    }

    @Test
    void readsCommonUsageFormats() throws Exception {
        assertThat(parse("{\"prompt_tokens\":100,\"prompt_cache_hit_tokens\":64,\"prompt_cache_miss_tokens\":36}")).isEqualTo(64);
        assertThat(parse("{\"prompt_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":80}}")).isEqualTo(80);
        assertThat(parse("{\"prompt_tokens\":100,\"prompt_cache_hit_tokens\":0,\"prompt_tokens_details\":{\"cached_tokens\":32}}")).isEqualTo(32);
    }

    @Test
    void channelsWithoutCacheDataCountAsZero() throws Exception {
        assertThat(parse("{\"prompt_tokens\":100,\"completion_tokens\":5}")).isZero();
        assertThat(parse("{}")).isZero();
        assertThat(OpenAiCompatibleClient.cachedPromptTokens(mapper.missingNode())).isZero();
    }
}
