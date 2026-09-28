package com.example.agentplatform.service;

import com.example.agentplatform.config.OutboundUrlValidator;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenAiCompatibleClientStreamTest {

    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient(mock(OutboundUrlValidator.class));

    private OpenAiCompatibleClient.StreamResult parse(String body, List<String> deltas) throws Exception {
        return client.parseStream(new BufferedReader(new StringReader(body)), deltas::add, () -> false);
    }

    @Test
    void streamsContentDeltasAndReadsUsageFromLastChunk() throws Exception {
        String body = """
                data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"delta":{"content":"你有"}}]}

                data: {"choices":[{"delta":{"content":" 3 个智能体"}}]}

                : keep-alive
                data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":8}}

                data: [DONE]
                """;
        List<String> deltas = new ArrayList<>();
        var result = parse(body, deltas);

        assertEquals(List.of("你有", " 3 个智能体"), deltas);
        assertEquals("你有 3 个智能体", result.content());
        assertEquals(120, result.promptTokens());
        assertEquals(8, result.completionTokens());
        assertFalse(result.hasToolCalls());
    }

    @Test
    void assemblesToolCallFragmentsByIndex() throws Exception {
        String body = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"list_agents","arguments":""}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"status\\":"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"get_gateway_status","arguments":"{}"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"RUNNING\\"}"}}]}}]}
                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                data: [DONE]
                """;
        var result = parse(body, new ArrayList<>());

        assertTrue(result.hasToolCalls());
        assertEquals(2, result.toolCalls().size());
        assertEquals(new OpenAiCompatibleClient.ToolCall("call_a", "list_agents", "{\"status\":\"RUNNING\"}"), result.toolCalls().get(0));
        assertEquals(new OpenAiCompatibleClient.ToolCall("call_b", "get_gateway_status", "{}"), result.toolCalls().get(1));
    }

    @Test
    void fallsBackToPlainJsonWhenChannelIgnoresStreaming() throws Exception {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"完整回复"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":4}}
                """;
        List<String> deltas = new ArrayList<>();
        var result = parse(body, deltas);

        assertEquals(List.of("完整回复"), deltas);
        assertEquals("完整回复", result.content());
        assertEquals(10, result.promptTokens());
        assertEquals(4, result.completionTokens());
    }

    @Test
    void stopsReadingWhenCancelled() throws Exception {
        String body = """
                data: {"choices":[{"delta":{"content":"第一段"}}]}
                data: {"choices":[{"delta":{"content":"第二段"}}]}
                """;
        List<String> deltas = new ArrayList<>();
        var result = client.parseStream(new BufferedReader(new StringReader(body)), deltas::add, () -> !deltas.isEmpty());

        assertEquals(List.of("第一段"), deltas);
        assertEquals("第一段", result.content());
    }
}
