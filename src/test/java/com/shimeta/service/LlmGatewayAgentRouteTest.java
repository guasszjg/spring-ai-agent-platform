package com.shimeta.service;

import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.config.SecretCrypto;
import com.shimeta.model.AgentRouteChoice;
import com.shimeta.model.ChatRouteOptions;
import com.shimeta.model.GatewayPolicy;
import com.shimeta.model.LlmProtocolType;
import com.shimeta.model.LlmProvider;
import com.shimeta.repository.GatewayPolicyRepository;
import com.shimeta.repository.LlmProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 智能体自选主通道、备用通道的路由与校验 */
class LlmGatewayAgentRouteTest {

    private final LlmProviderRepository providerRepository = mock(LlmProviderRepository.class);
    private final GatewayPolicyRepository policyRepository = mock(GatewayPolicyRepository.class);
    private final List<LlmProvider> providers = new ArrayList<>();
    private LlmGatewayService gateway;
    private GatewayPolicy policy;

    @BeforeEach
    void setUp() {
        gateway = new LlmGatewayService(providerRepository, policyRepository, mock(SecretCrypto.class),
                mock(OpenAiCompatibleClient.class), mock(CustomHttpLlmClient.class), mock(OutboundUrlValidator.class));
        policy = new GatewayPolicy();
        policy.setId(LlmGatewayService.POLICY_ID);
        policy.setDefaultProviderId("p-default");
        policy.setFallbackProviderId("p-platform-fb");
        policy.setFailoverEnabled(true);
        when(policyRepository.findById(LlmGatewayService.POLICY_ID)).thenReturn(Optional.of(policy));
        when(providerRepository.findAllByOrderByBuiltinDescCreatedAtAsc()).thenReturn(providers);
        when(providerRepository.findById(anyString())).thenAnswer(inv -> providers.stream()
                .filter(p -> p.getId().equals(inv.getArgument(0))).findFirst());
        providers.add(provider("p-default", "默认通道", "SUCCESS", true));
        providers.add(provider("p-platform-fb", "平台备用", "SUCCESS", true));
        providers.add(provider("p-mine", "我的通道", "SUCCESS", true));
        providers.add(provider("p-mine-fb", "我的备用", "SUCCESS", true));
        providers.add(provider("p-failed", "测试失败的通道", "FAILED", true));
        providers.add(provider("p-off", "停用的通道", "SUCCESS", false));
    }

    private static LlmProvider provider(String id, String name, String probe, boolean enabled) {
        LlmProvider p = new LlmProvider();
        p.setId(id);
        p.setName(name);
        p.setProtocol(LlmProtocolType.OPENAI);
        p.setApiKeyEncrypted("enc");
        p.setEnabled(enabled);
        p.setLastProbeStatus(probe);
        p.setDefaultModel(id + "-model");
        p.setModels(id + "-model," + id + "-large");
        return p;
    }

    @Test
    @DisplayName("选了主通道和备用通道：都用自己选的，模型按选择")
    void usesChosenChannels() {
        var route = gateway.resolveAgentRoute(new AgentRouteChoice("p-mine", "p-mine-fb", "p-mine-large")).orElseThrow();

        assertThat(route.route().primary().getId()).isEqualTo("p-mine");
        assertThat(route.route().fallback().getId()).isEqualTo("p-mine-fb");
        assertThat(route.model()).isEqualTo("p-mine-large");
        assertThat(route.notice()).isNull();
    }

    @Test
    @DisplayName("只选主通道：备用通道跟随平台降级设置")
    void fallbackFollowsPlatformWhenNotChosen() {
        var route = gateway.resolveAgentRoute(new AgentRouteChoice("p-mine", null, null)).orElseThrow();

        assertThat(route.route().primary().getId()).isEqualTo("p-mine");
        assertThat(route.route().fallback().getId()).isEqualTo("p-platform-fb");
    }

    @Test
    @DisplayName("自己选的备用通道不受平台自动降级开关影响")
    void chosenFallbackIgnoresPlatformSwitch() {
        policy.setFailoverEnabled(false);

        var route = gateway.resolveAgentRoute(new AgentRouteChoice(null, "p-mine-fb", null)).orElseThrow();

        assertThat(route.route().primary().getId()).isEqualTo("p-default");
        assertThat(route.route().fallback().getId()).isEqualTo("p-mine-fb");
    }

    @Test
    @DisplayName("选的主通道测试失败或被停用：改用平台默认，并说明原因")
    void unavailableChoiceFallsBackToPlatform() {
        var failed = gateway.resolveAgentRoute(new AgentRouteChoice("p-failed", null, "p-failed-model")).orElseThrow();
        assertThat(failed.route().primary().getId()).isEqualTo("p-default");
        assertThat(failed.notice()).contains("测试失败的通道").contains("平台默认");

        var off = gateway.resolveAgentRoute(new AgentRouteChoice("p-off", null, null)).orElseThrow();
        assertThat(off.route().primary().getId()).isEqualTo("p-default");
        assertThat(off.notice()).contains("未启用");

        var deleted = gateway.resolveAgentRoute(new AgentRouteChoice("p-gone", null, null)).orElseThrow();
        assertThat(deleted.route().primary().getId()).isEqualTo("p-default");
        assertThat(deleted.notice()).contains("已被删除");
    }

    @Test
    @DisplayName("选的备用通道和实际主通道相同：不配备用")
    void fallbackSameAsPrimaryIsDropped() {
        var route = gateway.resolveAgentRoute(new AgentRouteChoice(null, "p-default", null)).orElseThrow();

        assertThat(route.route().primary().getId()).isEqualTo("p-default");
        assertThat(route.route().fallback()).isNull();
    }

    @Test
    @DisplayName("保存前校验：主备相同、通道不可选、模型不在主通道里都拒绝")
    void validatesChoiceBeforeSaving() {
        assertThatThrownBy(() -> gateway.validateAgentChoice("p-mine", "p-mine", null))
                .hasMessageContaining("不能是同一个");
        assertThatThrownBy(() -> gateway.validateAgentChoice("p-failed", null, null))
                .hasMessageContaining("测试未通过");
        assertThatThrownBy(() -> gateway.validateAgentChoice(null, "p-off", null))
                .hasMessageContaining("未启用");
        assertThatThrownBy(() -> gateway.validateAgentChoice("p-mine", null, "other-model"))
                .hasMessageContaining("不在通道");

        gateway.validateAgentChoice("p-mine", "p-mine-fb", "P-MINE-LARGE");
        gateway.validateAgentChoice(null, null, "anything");
    }

    @Test
    @DisplayName("选项列表：只列已启用的通道，测试失败的标为不可选；给出平台默认路由")
    void listsEnabledChannelsWithSelectableFlag() {
        ChatRouteOptions options = gateway.chatRouteOptions();

        assertThat(options.channels()).extracting(ChatRouteOptions.Channel::id)
                .containsExactly("p-default", "p-platform-fb", "p-mine", "p-mine-fb", "p-failed");
        ChatRouteOptions.Channel failed = options.channels().stream().filter(c -> c.id().equals("p-failed")).findFirst().orElseThrow();
        assertThat(failed.selectable()).isFalse();
        assertThat(failed.unavailableReason()).contains("未通过");
        assertThat(options.platform().channelId()).isEqualTo("p-default");
        assertThat(options.platform().fallbackChannelId()).isEqualTo("p-platform-fb");
    }

    @Test
    @DisplayName("测试不可选的通道直接拒绝；连续测试被限频")
    void testChannelRejectsUnavailableAndThrottles() {
        assertThatThrownBy(() -> gateway.testChannel("p-failed", null, "u1"))
                .isInstanceOf(IllegalArgumentException.class);

        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        gateway = new LlmGatewayService(providerRepository, policyRepository, mock(SecretCrypto.class),
                client, mock(CustomHttpLlmClient.class), mock(OutboundUrlValidator.class));
        when(client.chatWithTools(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(),
                any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new OpenAiCompatibleClient.ChatResult("OK", 1, 1, 2, null, 0));

        ChatRouteOptions.TestResult result = gateway.testChannel("p-mine", "p-mine-large", "u2");
        assertThat(result.success()).isTrue();
        assertThat(result.model()).isEqualTo("p-mine-large");
        assertThat(result.reply()).isEqualTo("OK");
        assertThatThrownBy(() -> gateway.testChannel("p-mine", null, "u2"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("频繁");
    }
}
