package com.shimeta.skill;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 返回给前端的技能。列表里不带正文（content 为空），只给长度；详情才带正文。
 */
public record SkillView(
        String id,
        String name,
        String description,
        String content,
        int contentLength,
        List<String> requiredTools,
        String ownerId,
        String ownerUsername,
        boolean isSystem,
        boolean enabled,
        int version,
        long loadCount,
        LocalDateTime lastLoadedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean canManage,
        int agentCount
) {
    static SkillView of(Skill s, boolean withContent, boolean canManage, int agentCount) {
        String content = s.getContent() == null ? "" : s.getContent();
        return new SkillView(s.getId(), s.getName(), s.getDescription(), withContent ? content : null, content.length(),
                s.getRequiredTools() == null ? List.of() : List.copyOf(s.getRequiredTools()),
                s.getOwnerId(), s.getOwnerUsername(), Boolean.TRUE.equals(s.getIsSystem()),
                !Boolean.FALSE.equals(s.getEnabled()), s.getVersion() == null ? 1 : s.getVersion(),
                s.getLoadCount() == null ? 0 : s.getLoadCount(), s.getLastLoadedAt(), s.getCreatedAt(), s.getUpdatedAt(),
                canManage, agentCount);
    }
}
