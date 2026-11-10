package com.shimeta.skill;

import com.shimeta.model.Agent;
import com.shimeta.repository.AgentRepository;
import com.shimeta.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能库。权限沿用知识库的规则：开发者管理自己的技能，超级管理员管理全部和系统内置技能；
 * 系统内置技能所有人可用，只读观察员只能查看。
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,63}$");
    static final int MAX_DESCRIPTION_CHARS = 1024;
    /** 正文硬上限；编辑页超过 {@link #SOFT_CONTENT_CHARS} 字就提醒 */
    public static final int MAX_CONTENT_CHARS = 20000;
    public static final int SOFT_CONTENT_CHARS = 6000;
    static final int MAX_REQUIRED_TOOLS = 20;
    public static final int MAX_SKILLS_PER_AGENT = 20;
    static final int MAX_VERSIONS = 30;
    static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 500;

    private final SkillRepository skillRepository;
    private final SkillVersionRepository versionRepository;
    private final AgentRepository agentRepository;

    public SkillService(SkillRepository skillRepository, SkillVersionRepository versionRepository,
                        AgentRepository agentRepository) {
        this.skillRepository = skillRepository;
        this.versionRepository = versionRepository;
        this.agentRepository = agentRepository;
    }

    public record ImportResult(SkillView skill, boolean updated, List<String> warnings) {}

    // ==================== 权限 ====================

    public boolean canManage(CurrentActor actor, Skill skill) {
        if (actor == null || skill == null || actor.isViewer()) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(skill.getIsSystem())) {
            return false;
        }
        return actor.getUserId() != null && actor.getUserId().equals(skill.getOwnerId());
    }

    public boolean canUse(CurrentActor actor, Skill skill) {
        if (actor == null || skill == null) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (actor.isViewer()) {
            return false;
        }
        if (Boolean.TRUE.equals(skill.getIsSystem())) {
            return true;
        }
        return actor.getUserId() != null && actor.getUserId().equals(skill.getOwnerId());
    }

    boolean canView(CurrentActor actor, Skill skill) {
        if (actor == null || skill == null) {
            return false;
        }
        return canUse(actor, skill) || Boolean.TRUE.equals(skill.getIsSystem());
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public List<SkillView> list(CurrentActor actor) {
        requireLogin(actor);
        Map<String, Integer> usage = agentUsage();
        return skillRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(s -> canView(actor, s))
                .map(s -> SkillView.of(s, false, canManage(actor, s), usage.getOrDefault(s.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SkillView get(String id, CurrentActor actor) {
        Skill skill = requireViewable(id, actor);
        return SkillView.of(skill, true, canManage(actor, skill), agentUsage().getOrDefault(id, 0));
    }

    @Transactional(readOnly = true)
    public List<SkillVersion> versions(String id, CurrentActor actor) {
        requireViewable(id, actor);
        return versionRepository.findBySkillIdOrderByVersionDesc(id);
    }

    @Transactional(readOnly = true)
    public String export(String id, CurrentActor actor) {
        return SkillMarkdown.format(requireViewable(id, actor));
    }

    // ==================== 修改 ====================

    @Transactional
    public SkillView create(SkillRequest request, CurrentActor actor) {
        return create(request, actor, SkillVersion.SOURCE_CREATE);
    }

    private SkillView create(SkillRequest request, CurrentActor actor, String source) {
        requireWriter(actor);
        Skill skill = new Skill();
        skill.setName(normalizeName(request.getName()));
        if (skillRepository.existsByName(skill.getName())) {
            throw new IllegalArgumentException("已经有名为「" + skill.getName() + "」的技能，请换一个名称");
        }
        skill.setDescription(normalizeDescription(request.getDescription()));
        skill.setContent(normalizeContent(request.getContent()));
        skill.setRequiredTools(normalizeTools(request.getRequiredTools()));
        skill.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        skill.setIsSystem(actor.isSuperAdmin() && Boolean.TRUE.equals(request.getIsSystem()));
        skill.setOwnerId(actor.getUserId());
        skill.setOwnerUsername(actor.getUsername());
        skill.setVersion(1);
        Skill saved = skillRepository.save(skill);
        versionRepository.save(SkillVersion.snapshot(saved, source, request.getChangeNote(), actor.getUsername()));
        return SkillView.of(saved, true, true, 0);
    }

    @Transactional
    public SkillView update(String id, SkillRequest request, CurrentActor actor) {
        Skill skill = requireManageable(id, actor);
        applyChanges(skill, request, actor, SkillVersion.SOURCE_EDIT);
        return SkillView.of(skill, true, true, agentUsage().getOrDefault(id, 0));
    }

    @Transactional
    public SkillView setEnabled(String id, boolean enabled, CurrentActor actor) {
        Skill skill = requireManageable(id, actor);
        skill.setEnabled(enabled);
        Skill saved = skillRepository.save(skill);
        return SkillView.of(saved, false, true, agentUsage().getOrDefault(id, 0));
    }

    /** 删除技能，并从关联了它的智能体里移除 */
    @Transactional
    public void delete(String id, CurrentActor actor) {
        Skill skill = requireManageable(id, actor);
        for (Agent agent : agentRepository.findAll()) {
            List<String> ids = agent.getSkillIds();
            if (ids != null && ids.contains(id)) {
                List<String> next = new ArrayList<>(ids);
                next.removeIf(id::equals);
                agent.setSkillIds(next);
                agentRepository.save(agent);
            }
        }
        versionRepository.deleteBySkillId(id);
        skillRepository.delete(skill);
    }

    @Transactional
    public SkillView restore(String id, int version, CurrentActor actor) {
        Skill skill = requireManageable(id, actor);
        SkillVersion old = versionRepository.findBySkillIdAndVersion(id, version)
                .orElseThrow(() -> new IllegalArgumentException("没有找到第 " + version + " 版"));
        SkillRequest request = new SkillRequest();
        request.setDescription(old.getDescription());
        request.setContent(old.getContent());
        request.setRequiredTools(old.getRequiredTools());
        request.setChangeNote("恢复到第 " + version + " 版");
        applyChanges(skill, request, actor, SkillVersion.SOURCE_RESTORE);
        return SkillView.of(skill, true, true, agentUsage().getOrDefault(id, 0));
    }

    /**
     * 导入 SKILL.md 或技能压缩包（zip 里找 SKILL.md）。同名技能存在且有权管理时，overwrite 为真则更新为新版本。
     * 压缩包里的脚本和其它文件不导入，在 warnings 里列出。
     */
    @Transactional
    public ImportResult importFile(String filename, byte[] data, boolean overwrite, CurrentActor actor) {
        requireWriter(actor);
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        if (data.length > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("文件超过 5 MB");
        }
        List<String> warnings = new ArrayList<>();
        String text;
        String fallbackName = null;
        boolean zip = (filename != null && filename.toLowerCase().endsWith(".zip"))
                || (data.length > 3 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4);
        if (zip) {
            ZipSkill found = readZip(data);
            text = found.text();
            fallbackName = found.directoryName();
            if (found.otherFiles() > 0) {
                warnings.add("压缩包里另有 " + found.otherFiles() + " 个文件（脚本、参考资料等）没有导入，只导入了 SKILL.md 的内容；"
                        + "如果正文里引用了这些文件，请把需要的内容直接写进步骤里。");
            }
        } else {
            text = new String(data, StandardCharsets.UTF_8);
            if (filename != null && !filename.equalsIgnoreCase("SKILL.md") && filename.toLowerCase().endsWith(".md")) {
                fallbackName = filename.substring(0, filename.length() - 3);
            }
        }
        SkillMarkdown.Parsed parsed = SkillMarkdown.parse(text);
        String name = parsed.name() != null ? parsed.name() : fallbackName;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SKILL.md 开头缺少 name");
        }
        if (parsed.description() == null) {
            throw new IllegalArgumentException("SKILL.md 开头缺少 description（什么时候用这个技能）");
        }
        if (!parsed.ignoredKeys().isEmpty()) {
            warnings.add("以下字段平台不使用，已忽略：" + String.join("、", parsed.ignoredKeys()));
        }
        String description = parsed.description();
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS);
            warnings.add("描述超过 " + MAX_DESCRIPTION_CHARS + " 字，已截断");
        }
        if (parsed.content().length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("技能正文 " + parsed.content().length() + " 字，超过上限 " + MAX_CONTENT_CHARS + " 字");
        }
        if (parsed.content().length() > SOFT_CONTENT_CHARS) {
            warnings.add("正文较长（" + parsed.content().length() + " 字），加载时会占用较多上下文");
        }
        String normalizedName = normalizeName(name);
        SkillRequest request = new SkillRequest();
        request.setName(normalizedName);
        request.setDescription(description);
        request.setContent(parsed.content());
        request.setRequiredTools(parsed.requiredTools());
        request.setChangeNote("导入" + (filename != null ? "：" + filename : ""));

        Skill existing = skillRepository.findByName(normalizedName).orElse(null);
        if (existing != null) {
            if (!overwrite) {
                throw new IllegalArgumentException("已经有名为「" + normalizedName + "」的技能。勾选「覆盖同名技能」可以把导入内容保存为它的新版本");
            }
            if (!canManage(actor, existing)) {
                throw new IllegalStateException("权限不足：同名技能「" + normalizedName + "」不是你的，不能覆盖");
            }
            request.setName(null);
            applyChanges(existing, request, actor, SkillVersion.SOURCE_IMPORT);
            return new ImportResult(SkillView.of(existing, true, true, agentUsage().getOrDefault(existing.getId(), 0)), true, warnings);
        }
        return new ImportResult(create(request, actor, SkillVersion.SOURCE_IMPORT), false, warnings);
    }

    private void applyChanges(Skill skill, SkillRequest request, CurrentActor actor, String source) {
        boolean changed = false;
        if (request.getName() != null) {
            String name = normalizeName(request.getName());
            if (!name.equals(skill.getName())) {
                if (skillRepository.existsByName(name)) {
                    throw new IllegalArgumentException("已经有名为「" + name + "」的技能，请换一个名称");
                }
                skill.setName(name);
                changed = true;
            }
        }
        if (request.getDescription() != null) {
            String description = normalizeDescription(request.getDescription());
            changed |= !description.equals(skill.getDescription());
            skill.setDescription(description);
        }
        if (request.getContent() != null) {
            String content = normalizeContent(request.getContent());
            changed |= !content.equals(skill.getContent());
            skill.setContent(content);
        }
        if (request.getRequiredTools() != null) {
            List<String> tools = normalizeTools(request.getRequiredTools());
            changed |= !tools.equals(skill.getRequiredTools());
            skill.setRequiredTools(tools);
        }
        if (request.getEnabled() != null) {
            skill.setEnabled(request.getEnabled());
        }
        if (actor.isSuperAdmin() && request.getIsSystem() != null) {
            skill.setIsSystem(request.getIsSystem());
        }
        if (changed) {
            skill.setVersion((skill.getVersion() == null ? 1 : skill.getVersion()) + 1);
        }
        skillRepository.save(skill);
        if (changed) {
            versionRepository.save(SkillVersion.snapshot(skill, source, request.getChangeNote(), actor.getUsername()));
            pruneVersions(skill.getId());
        }
    }

    private void pruneVersions(String skillId) {
        List<SkillVersion> all = versionRepository.findBySkillIdOrderByVersionDesc(skillId);
        if (all.size() > MAX_VERSIONS) {
            versionRepository.deleteAll(all.subList(MAX_VERSIONS, all.size()));
        }
    }

    // ==================== 智能体关联与对话 ====================

    /** 智能体保存时检查关联的技能：存在、有权使用、数量不超限 */
    @Transactional(readOnly = true)
    public List<String> validateBindings(CurrentActor actor, List<String> skillIds) {
        return validateBindings(actor, skillIds, null);
    }

    /** @param alreadyBound 智能体原来关联的技能：保留它们不再检查使用权限（例如管理员替你关联的技能） */
    @Transactional(readOnly = true)
    public List<String> validateBindings(CurrentActor actor, List<String> skillIds, List<String> alreadyBound) {
        if (skillIds == null) {
            return null;
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(skillIds.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList()));
        if (ids.size() > MAX_SKILLS_PER_AGENT) {
            throw new IllegalArgumentException("一个智能体最多关联 " + MAX_SKILLS_PER_AGENT + " 个技能");
        }
        for (String id : ids) {
            Skill skill = skillRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("关联的技能不存在: " + id));
            boolean kept = alreadyBound != null && alreadyBound.contains(id);
            if (actor != null && !kept && !canUse(actor, skill)) {
                throw new IllegalStateException("权限不足：无权关联技能「" + skill.getName() + "」");
            }
        }
        return ids;
    }

    /** 复制智能体时只保留新拥有者能用的技能 */
    @Transactional(readOnly = true)
    public List<String> filterUsable(CurrentActor actor, List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String id : skillIds) {
            if (id == null) {
                continue;
            }
            skillRepository.findById(id).filter(s -> actor == null || canUse(actor, s)).ifPresent(s -> out.add(id));
        }
        return out;
    }

    /** 对话时用的技能：按智能体里的顺序，跳过已删除和停用的 */
    @Transactional(readOnly = true)
    public List<SkillSpec> runtimeSpecs(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        Map<String, Skill> byId = new HashMap<>();
        skillRepository.findAllById(skillIds).forEach(s -> byId.put(s.getId(), s));
        List<SkillSpec> specs = new ArrayList<>();
        for (String id : skillIds) {
            Skill skill = byId.get(id);
            if (skill != null && !Boolean.FALSE.equals(skill.getEnabled())) {
                specs.add(SkillSpec.of(skill));
            }
        }
        return specs;
    }

    public void recordLoad(String skillId) {
        if (skillId == null) {
            return;
        }
        try {
            skillRepository.recordLoad(skillId, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("记录技能加载次数失败 [{}]: {}", skillId, e.getMessage());
        }
    }

    // ==================== 校验与工具方法 ====================

    private Map<String, Integer> agentUsage() {
        Map<String, Integer> usage = new HashMap<>();
        for (Agent agent : agentRepository.findAll()) {
            if (agent.getSkillIds() != null) {
                for (String id : new LinkedHashSet<>(agent.getSkillIds())) {
                    usage.merge(id, 1, Integer::sum);
                }
            }
        }
        return usage;
    }

    private Skill requireViewable(String id, CurrentActor actor) {
        requireLogin(actor);
        Skill skill = skillRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("技能不存在: " + id));
        if (!canView(actor, skill)) {
            throw new IllegalStateException("权限不足：无权查看该技能");
        }
        return skill;
    }

    private Skill requireManageable(String id, CurrentActor actor) {
        requireLogin(actor);
        Skill skill = skillRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("技能不存在: " + id));
        if (!canManage(actor, skill)) {
            throw new IllegalStateException(Boolean.TRUE.equals(skill.getIsSystem())
                    ? "系统内置技能只有超级管理员可以修改" : "权限不足：只能修改自己的技能");
        }
        return skill;
    }

    private static void requireLogin(CurrentActor actor) {
        if (actor == null) {
            throw new IllegalStateException("请先登录");
        }
    }

    private static void requireWriter(CurrentActor actor) {
        requireLogin(actor);
        if (actor.isViewer()) {
            throw new IllegalStateException("只读观察员不能新建或导入技能");
        }
    }

    static String normalizeName(String name) {
        String v = name == null ? "" : name.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("请填写技能名称");
        }
        if (!NAME_PATTERN.matcher(v).matches()) {
            throw new IllegalArgumentException("技能名称只能用中英文、数字、短横线、下划线和点，不超过 64 个字符，且不能以符号开头");
        }
        return v;
    }

    static String normalizeDescription(String description) {
        String v = description == null ? "" : description.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("请填写什么时候用这个技能（描述）");
        }
        if (v.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("描述不能超过 " + MAX_DESCRIPTION_CHARS + " 字");
        }
        return v;
    }

    static String normalizeContent(String content) {
        String v = content == null ? "" : content.strip();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("请填写技能的详细步骤");
        }
        if (v.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("详细步骤 " + v.length() + " 字，超过上限 " + MAX_CONTENT_CHARS + " 字");
        }
        return v;
    }

    static List<String> normalizeTools(List<String> tools) {
        if (tools == null) {
            return new ArrayList<>();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String tool : tools) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            String v = tool.trim();
            if (v.length() > 64) {
                throw new IllegalArgumentException("工具名过长: " + v);
            }
            out.add(v);
        }
        if (out.size() > MAX_REQUIRED_TOOLS) {
            throw new IllegalArgumentException("需要的工具最多 " + MAX_REQUIRED_TOOLS + " 个");
        }
        return new ArrayList<>(out);
    }

    record ZipSkill(String text, String directoryName, int otherFiles) {}

    static ZipSkill readZip(byte[] data) {
        String bestPath = null;
        byte[] best = null;
        int bestDepth = Integer.MAX_VALUE;
        int entries = 0;
        int files = 0;
        Set<String> skillDirs = new LinkedHashSet<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("压缩包里的文件太多（超过 " + MAX_ZIP_ENTRIES + " 个）");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String path = entry.getName().replace('\\', '/');
                if (path.startsWith("__MACOSX/") || path.contains("/.") || path.startsWith(".")) {
                    continue;
                }
                files++;
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                if (!fileName.equalsIgnoreCase("SKILL.md")) {
                    continue;
                }
                int depth = path.split("/").length;
                skillDirs.add(path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");
                if (depth < bestDepth) {
                    bestDepth = depth;
                    bestPath = path;
                    best = readLimited(zin, 512 * 1024);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("压缩包读取失败：" + e.getMessage());
        }
        if (best == null) {
            throw new IllegalArgumentException("压缩包里没有找到 SKILL.md");
        }
        if (skillDirs.size() > 1) {
            throw new IllegalArgumentException("压缩包里有 " + skillDirs.size() + " 个 SKILL.md，一次只能导入一个技能，请分别打包后导入");
        }
        String dir = bestPath.contains("/") ? bestPath.substring(0, bestPath.lastIndexOf('/')) : "";
        String dirName = dir.isEmpty() ? null : dir.substring(dir.lastIndexOf('/') + 1);
        return new ZipSkill(new String(best, StandardCharsets.UTF_8), dirName, files - 1);
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) {
                throw new IllegalArgumentException("SKILL.md 超过 512 KB");
            }
        }
        return out.toByteArray();
    }
}
