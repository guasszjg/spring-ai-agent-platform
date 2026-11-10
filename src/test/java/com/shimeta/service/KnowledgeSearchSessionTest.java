package com.shimeta.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSearchSessionTest {

    private final List<String> calls = new ArrayList<>();

    private KnowledgeSearchSession session(Map<String, String> kbs, String text) {
        return new KnowledgeSearchSession(kbs, (ids, query, max) -> {
            calls.add(String.join(",", ids) + "|" + query + "|" + max);
            return new KnowledgeBaseService.RetrievalContext(text, List.of("/img/" + query + ".png"));
        });
    }

    private static Map<String, String> two() {
        Map<String, String> kbs = new LinkedHashMap<>();
        kbs.put("kb1", "售后政策");
        kbs.put("kb2", "产品手册");
        return kbs;
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolDefinitionOffersKnowledgeBaseChoiceOnlyWhenSeveral() {
        Map<String, Object> fn = (Map<String, Object>) session(two(), "x").toolDefinition().get("function");
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) fn.get("parameters")).get("properties");
        assertThat(fn.get("name")).isEqualTo("search_knowledge");
        assertThat((List<String>) ((Map<String, Object>) props.get("knowledge_base")).get("enum")).containsExactly("售后政策", "产品手册");

        Map<String, Object> single = (Map<String, Object>) session(Map.of("kb1", "售后政策"), "x").toolDefinition().get("function");
        Map<String, Object> singleProps = (Map<String, Object>) ((Map<String, Object>) single.get("parameters")).get("properties");
        assertThat(singleProps).containsOnlyKeys("query");
    }

    @Test
    void guidanceListsKnowledgeBases() {
        assertThat(session(two(), "x").guidance()).contains("售后政策、产品手册").contains("search_knowledge").contains("最多检索 3 次");
    }

    @Test
    void searchesAllOrOneKnowledgeBaseAndRecordsQueries() {
        KnowledgeSearchSession s = session(two(), "【知识库检索结果】资料");
        assertThat(s.search("退款时效", "")).contains("资料");
        assertThat(s.search("保修", "产品手册")).contains("资料");
        assertThat(calls).containsExactly("kb1,kb2|退款时效|3000", "kb2|保修|3000");
        assertThat(s.queries()).containsExactly("退款时效", "保修");
        assertThat(s.faqImages()).containsExactly("/img/退款时效.png");
    }

    @Test
    void unknownKnowledgeBaseAndEmptyQueryDoNotSearch() {
        KnowledgeSearchSession s = session(two(), "资料");
        assertThat(s.search("x", "不存在")).contains("没有名为「不存在」的知识库").contains("售后政策");
        assertThat(s.search("  ", null)).contains("查询词为空");
        assertThat(calls).isEmpty();
        assertThat(s.queries()).isEmpty();
    }

    @Test
    void stopsAfterMaxSearches() {
        KnowledgeSearchSession s = session(two(), "资料");
        for (int i = 0; i < 3; i++) {
            s.search("q" + i, null);
        }
        assertThat(s.search("q3", null)).contains("检索次数已用完");
        assertThat(calls).hasSize(3);
    }

    @Test
    void emptyResultSuggestsRephrasing() {
        assertThat(session(two(), "").search("发票", null)).contains("没有检索到").contains("换个说法");
    }

    @Test
    void tokenBudgetShrinksEachSearchAndStopsWhenUsedUp() {
        KnowledgeSearchSession s = session(two(), "资".repeat(900));
        s.setTokenBudget(1000);
        s.search("a", null);
        assertThat(calls.get(0)).endsWith("|1000");
        assertThat(s.search("b", null)).contains("资");
        assertThat(calls.get(1)).endsWith("|100");
        assertThat(s.search("c", null)).contains("上下文空间已经用完");
        assertThat(calls).hasSize(2);
    }
}
