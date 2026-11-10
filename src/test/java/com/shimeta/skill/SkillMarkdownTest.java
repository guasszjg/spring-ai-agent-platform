package com.shimeta.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillMarkdownTest {

    @Test
    void parsesCommunityStyleSkill() {
        String md = """
                ---
                name: pdf-processing
                description: Extract text and tables from PDF files. Use when the user mentions PDFs.
                license: Apache-2.0
                allowed-tools: Read, Bash
                metadata:
                  author: someone
                  version: "1.0"
                ---

                # PDF Processing

                1. Read the file
                2. Extract tables
                """;
        SkillMarkdown.Parsed parsed = SkillMarkdown.parse(md);
        assertThat(parsed.name()).isEqualTo("pdf-processing");
        assertThat(parsed.description()).startsWith("Extract text").endsWith("mentions PDFs.");
        assertThat(parsed.content()).startsWith("# PDF Processing").contains("2. Extract tables");
        assertThat(parsed.requiredTools()).isEmpty();
        assertThat(parsed.ignoredKeys()).containsExactly("license", "allowed-tools", "metadata");
    }

    @Test
    void parsesFoldedDescriptionQuotesAndToolList() {
        String md = "﻿---\r\n"
                + "name: \"退款处理\"\r\n"
                + "description: >\r\n"
                + "  用户要求退款时使用。\r\n"
                + "  先核对订单。\r\n"
                + "required-tools:\r\n"
                + "  - bocha_web_search\r\n"
                + "  - mcp_amap__maps_weather\r\n"
                + "---\r\n"
                + "步骤一\r\n";
        SkillMarkdown.Parsed parsed = SkillMarkdown.parse(md);
        assertThat(parsed.name()).isEqualTo("退款处理");
        assertThat(parsed.description()).isEqualTo("用户要求退款时使用。 先核对订单。");
        assertThat(parsed.requiredTools()).containsExactly("bocha_web_search", "mcp_amap__maps_weather");
        assertThat(parsed.content()).isEqualTo("步骤一");
    }

    @Test
    void inlineListAndSingleQuotes() {
        SkillMarkdown.Parsed parsed = SkillMarkdown.parse("---\nname: 'it''s'\ndescription: d # 注释\nrequired-tools: [a, \"b\"]\n---\nbody");
        assertThat(parsed.name()).isEqualTo("it's");
        assertThat(parsed.description()).isEqualTo("d");
        assertThat(parsed.requiredTools()).containsExactly("a", "b");
    }

    @Test
    void rejectsMissingFrontMatter() {
        assertThatThrownBy(() -> SkillMarkdown.parse("# 没有头部")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillMarkdown.parse("---\nname: x\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatRoundTrips() {
        Skill skill = new Skill();
        skill.setName("客服-退款");
        skill.setDescription("包含 \"引号\" 和\n换行: 冒号");
        skill.setContent("## 步骤\n\n1. 核对订单\n");
        skill.setRequiredTools(List.of("bocha_web_search"));
        String text = SkillMarkdown.format(skill);
        SkillMarkdown.Parsed parsed = SkillMarkdown.parse(text);
        assertThat(parsed.name()).isEqualTo("客服-退款");
        assertThat(parsed.description()).isEqualTo("包含 \"引号\" 和\n换行: 冒号");
        assertThat(parsed.content()).isEqualTo("## 步骤\n\n1. 核对订单");
        assertThat(parsed.requiredTools()).containsExactly("bocha_web_search");
        assertThat(parsed.ignoredKeys()).isEmpty();
    }
}
