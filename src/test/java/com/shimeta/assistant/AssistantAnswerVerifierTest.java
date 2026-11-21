package com.shimeta.assistant;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantAnswerVerifierTest {

    private final AssistantAnswerVerifier verifier = new AssistantAnswerVerifier();

    @Test
    void stripsFabricatedMarkdownLinksWhileKeepingAllowedToolLinksAndImages() {
        String toolJson = """
                {"ok":true,"items":[{"id":"agent-1","name":"售后客服","link":"/debug/agent-1"}]}
                """;
        Set<String> allowed = AssistantAnswerVerifier.extractLinksFromJson(toolJson);
        assertTrue(allowed.contains("/debug/agent-1"));

        String raw = "可跳转 [售后客服](/debug/agent-1)，不要点 [编造智能体](/debug/fake-999)，图示 ![说明](/img/demo.png)。";
        var result = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", true, false, false, false,
                "我有哪些智能体", raw, 1, 0, allowed));

        assertEquals("可跳转 [售后客服](/debug/agent-1)，不要点 编造智能体，图示 ![说明](/img/demo.png)。",
                result.cleanedContent());
        assertTrue(result.flags().contains(AssistantAnswerVerifier.FLAG_FABRICATED_LINK));
    }

    @Test
    void detectsUnknownMenuPathsAndHallucinatedMenuNamesWithoutFalsePositivesOnRealMenus() {
        var bad = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", false, false, false, false,
                "在哪改通道", "请到「系统设置 → 通道管理」里修改，也可以在左侧「工作台」查看。", 0, 0, Set.of()));
        assertTrue(bad.flags().contains(AssistantAnswerVerifier.FLAG_UNKNOWN_MENU));

        var good = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", false, false, false, false,
                "在哪改通道", "请到「管理 → 模型网关 → 大语言模型」修改，或到「构建 → 智能体 › 编排」和「规则与安全 → 开放与安全」查看；诊断按「结论 → 依据 → 建议操作」进行。",
                0, 0, Set.of()));
        assertFalse(good.flags().contains(AssistantAnswerVerifier.FLAG_UNKNOWN_MENU));
    }

    @Test
    void flagsUngroundedDataWhenReadQuestionAnsweredWithNumbersAndNoToolCall() {
        var result = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", true, false, false, false,
                "近 7 天用了多少 token？", "你近 7 天共消耗 12500 token，预估成本 ¥3.5。", 0, 0, Set.of()));

        assertTrue(result.flags().contains(AssistantAnswerVerifier.FLAG_UNGROUNDED_DATA));
        assertNotNull(result.extraNotice());
        assertTrue(result.extraNotice().contains("本次没有查询平台数据"));
    }

    @Test
    void doesNotFlagDataWhenToolSucceededOrInChatModeOrWithDisclaimer() {
        var withTool = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", true, false, false, false,
                "近 7 天用了多少 token？", "你近 7 天共消耗 12500 token。", 1, 0, Set.of()));
        assertFalse(withTool.flags().contains(AssistantAnswerVerifier.FLAG_UNGROUNDED_DATA));
        assertNull(withTool.extraNotice());

        var chatMode = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", false, false, false, false,
                "近 7 天用了多少 token？", "请切换到「执行」模式查询。", 0, 0, Set.of()));
        assertFalse(chatMode.flags().contains(AssistantAnswerVerifier.FLAG_UNGROUNDED_DATA));
    }

    @Test
    void flagsPrematureCompletionWhenPendingCardIsUnconfirmed() {
        var premature = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", true, false, false, false,
                "帮我创建一个客服智能体", "已为你创建成功！你可以直接使用该智能体。", 1, 1, Set.of()));
        assertTrue(premature.flags().contains(AssistantAnswerVerifier.FLAG_PREMATURE_COMPLETION));
        assertNotNull(premature.extraNotice());
        assertTrue(premature.extraNotice().contains("尚未执行"));

        var proper = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", true, false, false, false,
                "帮我创建一个客服智能体", "已生成操作卡片，请核对信息并点击「确认」，确认后才会创建成功。", 1, 1, Set.of()));
        assertFalse(proper.flags().contains(AssistantAnswerVerifier.FLAG_PREMATURE_COMPLETION));
        assertNull(proper.extraNotice());
    }

    @Test
    void flagsCardClaimWhenNoActionWasCreated() {
        var claim = verifier.verify(new AssistantAnswerVerifier.VerificationInput(
                "asc-1", true, false, false, false,
                "帮我改一下温度", "已为你生成操作卡片，请点击确认。", 0, 0, Set.of()));
        assertTrue(claim.flags().contains(AssistantAnswerVerifier.FLAG_CARD_CLAIM));
        assertEquals(AssistantService.CARD_CLAIM_NOTICE, claim.extraNotice());
    }
}
