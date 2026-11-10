package com.shimeta.skill;

import java.util.List;

/** 对话时用到的技能内容，脱离数据库实体，可以在工具调用线程里直接用 */
public record SkillSpec(String id, String name, String description, String content, List<String> requiredTools) {

    public static SkillSpec of(Skill skill) {
        return new SkillSpec(skill.getId(), skill.getName(), skill.getDescription(), skill.getContent(),
                skill.getRequiredTools() == null ? List.of() : List.copyOf(skill.getRequiredTools()));
    }
}
