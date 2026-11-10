package com.shimeta.skill;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSessionTest {

    private static SkillSpec spec(String name, String content, List<String> tools) {
        return new SkillSpec("id-" + name, name, name + " 的适用场景", content, tools);
    }

    @Test
    void catalogListsOnlyNamesAndDescriptions() {
        SkillSession session = new SkillSession(List.of(spec("退款", "很长的步骤正文", List.of())), Set.of(), null);
        String catalog = session.catalog();
        assertThat(catalog).contains("【可用技能】").contains("- 退款：退款 的适用场景").contains("load_skill");
        assertThat(catalog).doesNotContain("很长的步骤正文");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolDefinitionEnumeratesSkillNames() {
        SkillSession session = new SkillSession(List.of(spec("a", "x", List.of()), spec("b", "y", List.of())), Set.of(), null);
        Map<String, Object> fn = (Map<String, Object>) session.toolDefinition().get("function");
        assertThat(fn.get("name")).isEqualTo("load_skill");
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) fn.get("parameters")).get("properties");
        assertThat((List<String>) ((Map<String, Object>) props.get("name")).get("enum")).containsExactly("a", "b");
    }

    @Test
    void loadsOncePerConversationAndRecordsUsage() {
        List<String> recorded = new ArrayList<>();
        SkillSession session = new SkillSession(List.of(spec("退款", "1. 核对订单", List.of())), Set.of(), recorded::add);
        assertThat(session.load("退款")).contains("【技能：退款】").contains("1. 核对订单");
        assertThat(session.load("退款")).contains("已经加载过").doesNotContain("核对订单");
        assertThat(recorded).containsExactly("id-退款");
        assertThat(session.loadedNames()).containsExactly("退款");
    }

    @Test
    void unknownSkillListsAvailableOnes() {
        SkillSession session = new SkillSession(List.of(spec("退款", "x", List.of())), Set.of(), null);
        assertThat(session.load("发票")).contains("没有名为「发票」的技能").contains("退款");
        assertThat(session.loadedNames()).isEmpty();
    }

    @Test
    void warnsAboutMissingTools() {
        SkillSession session = new SkillSession(
                List.of(spec("查天气", "调用天气工具", List.of("mcp_amap__maps_weather", "time_get_current_time"))),
                Set.of("TIME_GET_CURRENT_TIME"), null);
        String result = session.load("查天气");
        assertThat(result).contains("mcp_amap__maps_weather").contains("没有启用").doesNotContain("time_get_current_time 当前");
    }

    @Test
    void sharesTokenBudgetAcrossLoads() {
        String longText = "步".repeat(1000);
        SkillSession session = new SkillSession(List.of(spec("a", longText, List.of()), spec("b", longText, List.of())), Set.of(), null);
        session.setTokenBudget(1500);
        assertThat(session.load("a")).contains(longText).doesNotContain("已省略");
        String second = session.load("b");
        assertThat(second).contains("已省略");
        assertThat(second.length()).isLessThan(700);
    }

    @Test
    void tinyBudgetSkipsContent() {
        SkillSession session = new SkillSession(List.of(spec("a", "步".repeat(1000), List.of())), Set.of(), null);
        session.setTokenBudget(50);
        assertThat(session.load("a")).contains("放不下");
    }
}
