package com.example.agentplatform.service;

import com.example.agentplatform.config.OutboundUrlValidator;
import com.example.agentplatform.model.ChatGeneration;
import com.example.agentplatform.model.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomHttpLlmClientTest {

    private final OutboundUrlValidator validator = new OutboundUrlValidator(true);
    private final CustomHttpLlmClient client = new CustomHttpLlmClient(validator);

    @Test
    void testCustomHttpProbeAndChat() {
        String endpoint = "http://82.157.197.25:9540/api/modelConfig/modelLlmModel";
        String customConfigJson = """
                {
                  "endpointUrl": "http://82.157.197.25:9540/api/modelConfig/modelLlmModel",
                  "httpMethod": "POST",
                  "headers": {
                    "Content-Type": "application/json;charset=utf-8"
                  },
                  "bodyTemplate": "{\\n  \\"appId\\": \\"EM7C8J0FKC\\",\\n  \\"deviceMac\\": \\"8CFCA0288618\\",\\n  \\"prompt\\": \\"{{prompt}}\\"\\n}",
                  "successCode": 200,
                  "resultPath": "data.result",
                  "errorCodePath": "code",
                  "errorMessagePath": "msg",
                  "modelName": "customer-llm"
                }
                """;

        OpenAiCompatibleClient.ProbeResult probeResult = client.probe(endpoint, customConfigJson, 10000);
        System.out.println("Probe result: success=" + probeResult.success() + ", message=" + probeResult.message());

        if (probeResult.success()) {
            assertThat(probeResult.models()).contains("customer-llm");

            LlmProvider provider = new LlmProvider();
            provider.setName("第三方定制大模型");
            provider.setBaseUrl(endpoint);
            provider.setCustomConfig(customConfigJson);
            provider.setDefaultModel("customer-llm");

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "请用一句话介绍深圳")
            );

            OpenAiCompatibleClient.ChatResult chatResult = client.chat(provider, messages, new ChatGeneration(), 15000);
            System.out.println("Chat response: " + chatResult.content());
            assertThat(chatResult.content()).isNotBlank();
            assertThat(chatResult.promptTokens()).isGreaterThan(0);
            assertThat(chatResult.completionTokens()).isGreaterThan(0);
        }
    }

    @Test
    void testCleanAnswerCitations() {
        String raw1 = "明天北京晴，最高31℃[1][2]。";
        String cleaned1 = AiChatService.cleanAnswer(raw1);
        assertThat(cleaned1).isEqualTo("明天北京晴，最高31℃。");

        String raw2 = "深圳气温适宜【1】【23】，湿度75%¹²。";
        String cleaned2 = AiChatService.cleanAnswer(raw2);
        assertThat(cleaned2).isEqualTo("深圳气温适宜，湿度75%。");

        String raw3 = "  [1] 全文开始  ";
        String cleaned3 = AiChatService.cleanAnswer(raw3);
        assertThat(cleaned3).isEqualTo("全文开始");

        assertThat(AiChatService.cleanAnswer(null)).isNull();
        assertThat(AiChatService.cleanAnswer("   ")).isEqualTo("   ");
    }
}
