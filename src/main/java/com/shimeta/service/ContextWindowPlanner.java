package com.shimeta.service;

import com.shimeta.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 按模型通道的上下文窗口（输入 + 输出的 token 上限）分配一次请求的空间：
 * 先给输出留出 max_tokens，剩下的给输入；输入里系统提示词和本次问题必须保留，
 * 知识库内容其次，历史对话最后，放不下时从最早的一轮开始丢。
 * <p>
 * token 数用 {@link AiChatService#estimateTokens} 粗估，和模型真实分词有出入，所以输入预算再留一成余量。
 */
final class ContextWindowPlanner {

    /** 估算误差的余量：输入只用到剩余空间的 90% */
    static final double SAFETY_RATIO = 0.9;
    /** 没指定 max_tokens 时给输出预留窗口的四分之一，最多 4096 */
    static final int MAX_DEFAULT_OUTPUT_RESERVE = 4096;
    /** 每条消息的角色、分隔符等额外开销 */
    static final int PER_MESSAGE_OVERHEAD = 4;
    /** 知识库预算低于这个值时不再注入，几十个 token 放不下一条有用的资料 */
    static final int MIN_KNOWLEDGE_TOKENS = 100;

    private ContextWindowPlanner() {
    }

    /**
     * @param window       上下文窗口
     * @param outputTokens 实际发给模型的 max_tokens
     * @param inputBudget  输入（全部消息 + 工具定义）可用的估算 token 数
     */
    record Plan(int window, int outputTokens, int inputBudget) {
    }

    /**
     * @param window             通道配置的上下文窗口，为空或不大于 0 时返回 null（不限制）
     * @param requestedMaxTokens 智能体或请求指定的 max_tokens，可为空
     */
    static Plan plan(Integer window, Integer requestedMaxTokens) {
        if (window == null || window <= 0) {
            return null;
        }
        // 输出最多占窗口的四分之一：小窗口下优先保证知识库和问题放得下（智能体默认 max_tokens 2048，4096 窗口里会挤掉一半输入）
        int maxOutput = window / 4;
        int output = requestedMaxTokens != null && requestedMaxTokens > 0
                ? Math.min(requestedMaxTokens, maxOutput)
                : Math.min(maxOutput, MAX_DEFAULT_OUTPUT_RESERVE);
        int input = (int) ((window - output) * SAFETY_RATIO);
        return new Plan(window, output, Math.max(0, input));
    }

    static int messageTokens(String content) {
        return AiChatService.estimateTokens(content) + PER_MESSAGE_OVERHEAD;
    }

    static int historyTokens(List<ChatMessage> history) {
        if (history == null) {
            return 0;
        }
        return history.stream().mapToInt(m -> messageTokens(m.getContent())).sum();
    }

    /**
     * 从最早的消息开始丢，直到历史对话不超过 budget；结果不以助手消息开头，避免模型看到没有提问的回答。
     */
    static List<ChatMessage> fitHistory(List<ChatMessage> history, int budget) {
        if (history == null || history.isEmpty()) {
            return history;
        }
        int start = 0;
        int total = historyTokens(history);
        while (start < history.size() && (total > budget || "assistant".equalsIgnoreCase(history.get(start).getRole()))) {
            total -= messageTokens(history.get(start).getContent());
            start++;
        }
        return start == 0 ? history : new ArrayList<>(history.subList(start, history.size()));
    }
}
