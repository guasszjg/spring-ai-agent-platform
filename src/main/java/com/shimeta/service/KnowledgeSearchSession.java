package com.shimeta.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「模型自主检索」时一次对话里的知识库检索：search_knowledge 工具定义、系统提示里的说明，
 * 以及模型每次调用时去检索并返回资料。检索次数和返回的资料总量都有上限。
 */
public class KnowledgeSearchSession {

    public static final String TOOL_NAME = "search_knowledge";
    /** 一次回答最多检索几次 */
    public static final int DEFAULT_MAX_SEARCHES = 3;
    /** 一次回答所有检索结果加起来最多多少 token（没配置上下文窗口时） */
    public static final int DEFAULT_TOTAL_TOKENS = 8000;
    /** 单次检索最多返回多少 token，和「先检索后回答」的默认上限一致 */
    public static final int PER_SEARCH_TOKENS = 3000;
    /** 剩余空间不到这么多时不再检索 */
    static final int MIN_SEARCH_TOKENS = 100;

    /** 实际去检索：知识库 ID、查询词、这次最多返回多少 token */
    @FunctionalInterface
    public interface Searcher {
        KnowledgeBaseService.RetrievalContext search(List<String> knowledgeBaseIds, String query, int maxTokens);
    }

    private final Map<String, String> knowledgeBases;
    private final Searcher searcher;
    private final int maxSearches;
    private int remainingTokens = DEFAULT_TOTAL_TOKENS;
    private final List<String> queries = new ArrayList<>();
    private List<String> faqImages = List.of();

    /** @param knowledgeBases 知识库 ID → 名称，按智能体关联的顺序 */
    public KnowledgeSearchSession(Map<String, String> knowledgeBases, Searcher searcher) {
        this(knowledgeBases, searcher, DEFAULT_MAX_SEARCHES);
    }

    public KnowledgeSearchSession(Map<String, String> knowledgeBases, Searcher searcher, int maxSearches) {
        this.knowledgeBases = new LinkedHashMap<>(knowledgeBases);
        this.searcher = searcher;
        this.maxSearches = Math.max(1, maxSearches);
    }

    public boolean isEmpty() {
        return knowledgeBases.isEmpty();
    }

    /** 检索结果一共最多能占多少 token；按上下文窗口算出来的值比默认小时才收紧 */
    public void setTokenBudget(int tokens) {
        remainingTokens = Math.min(DEFAULT_TOTAL_TOKENS, Math.max(0, tokens));
    }

    /** 本次对话模型用过的查询词，按调用顺序 */
    public List<String> queries() {
        return List.copyOf(queries);
    }

    /** 检索命中的 FAQ 附图（第一次带附图的检索结果），随回答单独返回 */
    public List<String> faqImages() {
        return faqImages;
    }

    /** 放在系统提示末尾的说明：有哪些知识库、什么时候检索、怎么检索 */
    public String guidance() {
        StringBuilder sb = new StringBuilder("【知识库检索】\n")
                .append("本智能体关联了知识库：").append(String.join("、", knowledgeBases.values())).append("。")
                .append("知识库的内容不在你的记忆里，只能通过 `").append(TOOL_NAME).append("` 工具查到。\n")
                .append("- 用户问到产品、设备、软件、业务、流程、规定、参数、故障处理等具体问题时，必须先调用 `")
                .append(TOOL_NAME).append("` 检索，再根据检索结果回答；不要凭自己的知识或猜测作答，")
                .append("因为平台的具体做法、数值和名称很可能和你以为的不一样。\n")
                .append("- 只有寒暄、问你是谁、与业务完全无关的闲聊才直接回答，不要检索。\n")
                .append("- 查询词用简短的关键词或一句话，保留型号、文件名、端口号等原词，不要照搬用户的整段话。")
                .append("第一次没查到可以换个说法再查；问题涉及多个方面时可以分别检索。")
                .append("同一个问题最多检索 ").append(maxSearches).append(" 次。\n")
                .append("- 检索后仍然没有相关资料时，如实告诉用户知识库里没有找到，不要编造。");
        return sb.toString();
    }

    public Map<String, Object> toolDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "要检索的内容：简短的关键词或一句话"));
        if (knowledgeBases.size() > 1) {
            Map<String, Object> kb = new LinkedHashMap<>();
            kb.put("type", "string");
            kb.put("enum", new ArrayList<>(knowledgeBases.values()));
            kb.put("description", "只在某一个知识库里检索时填写；不填则检索全部关联的知识库");
            properties.put("knowledge_base", kb);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("query"));
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", TOOL_NAME);
        fn.put("description", "在智能体关联的知识库里检索资料，返回最相关的若干段原文和来源。"
                + "回答产品、业务、流程、规定等问题前调用；没查到可以换个说法再查。");
        fn.put("parameters", parameters);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    /** 处理一次 search_knowledge 调用 */
    public String search(String query, String knowledgeBaseName) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return "检索失败：查询词为空，请填写要检索的内容。";
        }
        if (queries.size() >= maxSearches) {
            return "本次回答的检索次数已用完（最多 " + maxSearches + " 次），请根据已经检索到的资料回答；资料不足时如实告诉用户。";
        }
        List<String> ids = scope(knowledgeBaseName);
        if (ids == null) {
            return "没有名为「" + knowledgeBaseName + "」的知识库。可选：" + String.join("、", knowledgeBases.values())
                    + "；不填 knowledge_base 则检索全部。";
        }
        int budget = Math.min(PER_SEARCH_TOKENS, remainingTokens);
        if (budget < MIN_SEARCH_TOKENS) {
            return "上下文空间已经用完，不能再检索，请根据已经检索到的资料回答。";
        }
        queries.add(q);
        KnowledgeBaseService.RetrievalContext result = searcher.search(ids, q, budget);
        if (result == null || result.text() == null || result.text().isBlank()) {
            return "知识库里没有检索到与「" + q + "」相关的资料。可以换个说法再查一次；仍然没有就按智能体本身的规则回答，不要编造。";
        }
        remainingTokens -= AiChatService.estimateTokens(result.text());
        if (faqImages.isEmpty() && result.faqImages() != null && !result.faqImages().isEmpty()) {
            faqImages = List.copyOf(result.faqImages());
        }
        return result.text();
    }

    private List<String> scope(String knowledgeBaseName) {
        if (knowledgeBaseName == null || knowledgeBaseName.isBlank()) {
            return new ArrayList<>(knowledgeBases.keySet());
        }
        String name = knowledgeBaseName.trim();
        for (Map.Entry<String, String> e : knowledgeBases.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name) || e.getKey().equals(name)) {
                return List.of(e.getKey());
            }
        }
        return null;
    }
}
