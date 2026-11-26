package com.shimeta.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptVariableTest {

    @Test
    void normalizeListValidatesKeysTypesAndDefaults() {
        PromptVariable store = new PromptVariable("store_name", "门店名称", "text", true, "深圳南山店", null, "请输入门店");
        PromptVariable tier = new PromptVariable("vip_level", "会员等级", "select", false, "不存在的选项", List.of("普通", "VIP", "VIP"), "");

        List<PromptVariable> normalized = PromptVariable.normalizeList(List.of(store, tier));
        assertEquals(2, normalized.size());
        assertEquals("store_name", normalized.get(0).getKey());
        assertEquals("门店名称", normalized.get(0).getLabel());
        assertTrue(Boolean.TRUE.equals(normalized.get(0).getRequired()));
        assertEquals(List.of("普通", "VIP"), normalized.get(1).getOptions());
        // 下拉默认值不在选项里时自动回退第一个选项
        assertEquals("普通", normalized.get(1).getDefaultValue());
    }

    @Test
    void normalizeListRejectsReservedAndInvalidKeys() {
        assertThrows(IllegalArgumentException.class, () ->
                PromptVariable.normalizeList(List.of(new PromptVariable("system_time", "时间", "text", false, "", null, ""))));
        assertThrows(IllegalArgumentException.class, () ->
                PromptVariable.normalizeList(List.of(new PromptVariable("123bad", "非法", "text", false, "", null, ""))));
        assertThrows(IllegalArgumentException.class, () ->
                PromptVariable.normalizeList(List.of(
                        new PromptVariable("city", "城市", "text", false, "", null, ""),
                        new PromptVariable("city", "重复", "text", false, "", null, ""))));
        assertThrows(IllegalArgumentException.class, () ->
                PromptVariable.normalizeList(List.of(new PromptVariable("region", "区域", "select", false, "", List.of(), ""))));
    }

    @Test
    void resolveInputsFallsBackToDefaultsAndEnforcesRequired() {
        PromptVariable store = new PromptVariable("store_name", "门店名称", "text", true, "默认门店", null, "");
        PromptVariable device = new PromptVariable("device_sn", "设备号", "text", true, "", null, "");
        PromptVariable note = new PromptVariable("note", "备注", "text", false, "", null, "");

        Map<String, String> resolved = PromptVariable.resolveInputs(
                List.of(store, device, note),
                Map.of("device_sn", "SN-8801", "extra_tag", "展厅A"),
                true
        );
        assertEquals("默认门店", resolved.get("store_name"));
        assertEquals("SN-8801", resolved.get("device_sn"));
        assertEquals("", resolved.get("note"));
        assertEquals("展厅A", resolved.get("extra_tag"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                PromptVariable.resolveInputs(List.of(store, device), Map.of(), true));
        assertTrue(ex.getMessage().contains("设备号"));
    }

    @Test
    void renderTemplateIsSinglePassAndSafeAgainstNestedPlaceholders() {
        String template = "欢迎来到{{store_name}}，当前时间{{system_time}}，问题：{{input}}，未定义：{{unknown}}";
        String rendered = PromptVariable.renderTemplate(template, Map.of(
                "store_name", "南山旗舰店",
                "system_time", "2026-11-26 16:50:00 (星期四)",
                "input", "帮我查一下 {{store_name}} 的地址"
        ));
        assertEquals("欢迎来到南山旗舰店，当前时间2026-11-26 16:50:00 (星期四)，问题：帮我查一下 {{store_name}} 的地址，未定义：{{unknown}}", rendered);
    }
}
