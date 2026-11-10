package com.shimeta.skill;

import com.shimeta.model.Agent;
import com.shimeta.model.UserRole;
import com.shimeta.repository.AgentRepository;
import com.shimeta.security.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillServiceTest {

    private final SkillRepository skillRepository = mock(SkillRepository.class);
    private final SkillVersionRepository versionRepository = mock(SkillVersionRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final Map<String, Skill> skills = new HashMap<>();
    private final List<SkillVersion> versions = new ArrayList<>();
    private final List<Agent> agents = new ArrayList<>();
    private SkillService service;

    private final CurrentActor admin = new CurrentActor("u-admin", "admin", UserRole.SUPER_ADMIN);
    private final CurrentActor dev = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
    private final CurrentActor other = new CurrentActor("u-other", "other", UserRole.DEVELOPER);
    private final CurrentActor viewer = new CurrentActor("u-view", "view", UserRole.VIEWER);

    @BeforeEach
    void setUp() {
        service = new SkillService(skillRepository, versionRepository, agentRepository);
        when(skillRepository.save(any())).thenAnswer(inv -> {
            Skill s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId("skill-" + (skills.size() + 1));
            }
            skills.put(s.getId(), s);
            return s;
        });
        when(skillRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(skills.get(inv.<String>getArgument(0))));
        when(skillRepository.existsByName(anyString())).thenAnswer(inv ->
                skills.values().stream().anyMatch(s -> s.getName().equals(inv.getArgument(0))));
        when(skillRepository.findByName(anyString())).thenAnswer(inv ->
                skills.values().stream().filter(s -> s.getName().equals(inv.getArgument(0))).findFirst());
        when(skillRepository.findAllByOrderByUpdatedAtDesc()).thenAnswer(inv -> new ArrayList<>(skills.values()));
        when(versionRepository.save(any())).thenAnswer(inv -> {
            versions.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(versionRepository.findBySkillIdOrderByVersionDesc(anyString())).thenAnswer(inv -> versions.stream()
                .filter(v -> v.getSkillId().equals(inv.getArgument(0)))
                .sorted((a, b) -> b.getVersion() - a.getVersion()).toList());
        when(agentRepository.findAll()).thenAnswer(inv -> agents);
    }

    private SkillRequest request(String name, String description, String content) {
        SkillRequest r = new SkillRequest();
        r.setName(name);
        r.setDescription(description);
        r.setContent(content);
        return r;
    }

    @Test
    void createAndEditKeepsVersions() {
        SkillView created = service.create(request("退款", "退款时用", "1. 核对"), dev);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.isSystem()).isFalse();

        SkillRequest edit = new SkillRequest();
        edit.setContent("1. 核对\n2. 退款");
        SkillView edited = service.update(created.id(), edit, dev);
        assertThat(edited.version()).isEqualTo(2);

        SkillRequest same = new SkillRequest();
        same.setContent("1. 核对\n2. 退款");
        assertThat(service.update(created.id(), same, dev).version()).isEqualTo(2);
        assertThat(versions).extracting(SkillVersion::getSource).containsExactly("CREATE", "EDIT");
    }

    @Test
    void developerCannotCreateSystemSkillOrEditOthers() {
        SkillRequest r = request("a", "d", "c");
        r.setIsSystem(true);
        SkillView created = service.create(r, dev);
        assertThat(created.isSystem()).isFalse();

        assertThatThrownBy(() -> service.update(created.id(), request(null, "x", null), other))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.create(request("b", "d", "c"), viewer))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void systemSkillUsableByEveryoneButOnlyAdminManages() {
        SkillRequest r = request("sys", "d", "c");
        r.setIsSystem(true);
        SkillView sys = service.create(r, admin);
        assertThat(sys.isSystem()).isTrue();
        Skill entity = skills.get(sys.id());
        assertThat(service.canUse(dev, entity)).isTrue();
        assertThat(service.canManage(dev, entity)).isFalse();
        assertThat(service.canUse(viewer, entity)).isFalse();
        assertThat(service.list(viewer)).extracting(SkillView::name).containsExactly("sys");
    }

    @Test
    void validatesNamesAndLimits() {
        assertThatThrownBy(() -> service.create(request("-bad", "d", "c"), dev)).hasMessageContaining("名称");
        assertThatThrownBy(() -> service.create(request("has space", "d", "c"), dev)).hasMessageContaining("名称");
        assertThatThrownBy(() -> service.create(request("ok", "d", "x".repeat(SkillService.MAX_CONTENT_CHARS + 1)), dev))
                .hasMessageContaining("上限");
        service.create(request("dup", "d", "c"), dev);
        assertThatThrownBy(() -> service.create(request("dup", "d", "c"), other)).hasMessageContaining("已经有");
    }

    @Test
    void bindingRejectsOthersPrivateSkillButKeepsExisting() {
        SkillView mine = service.create(request("mine", "d", "c"), other);
        assertThatThrownBy(() -> service.validateBindings(dev, List.of(mine.id())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.validateBindings(dev, List.of(mine.id(), mine.id()), List.of(mine.id())))
                .containsExactly(mine.id());
        assertThat(service.filterUsable(dev, List.of(mine.id(), "missing"))).isEmpty();
        assertThatThrownBy(() -> service.validateBindings(dev, List.of("missing"))).hasMessageContaining("不存在");
    }

    @Test
    void runtimeSkipsDisabledAndKeepsOrder() {
        SkillView a = service.create(request("a", "d", "c"), dev);
        SkillView b = service.create(request("b", "d", "c"), dev);
        SkillView c = service.create(request("c", "d", "c"), dev);
        skills.get(b.id()).setEnabled(false);
        when(skillRepository.findAllById(any())).thenAnswer(inv -> new ArrayList<>(skills.values()));
        assertThat(service.runtimeSpecs(List.of(c.id(), b.id(), a.id()))).extracting(SkillSpec::name).containsExactly("c", "a");
    }

    @Test
    void deleteRemovesSkillFromAgents() {
        SkillView s = service.create(request("a", "d", "c"), dev);
        Agent agent = new Agent();
        agent.setSkillIds(new ArrayList<>(List.of(s.id(), "other")));
        agents.add(agent);
        service.delete(s.id(), dev);
        assertThat(agent.getSkillIds()).containsExactly("other");
    }

    @Test
    void importsMarkdownAndOverwritesAsNewVersion() {
        byte[] md = "---\nname: 查天气\ndescription: 问天气时用\nlicense: MIT\n---\n1. 调用天气工具".getBytes(StandardCharsets.UTF_8);
        SkillService.ImportResult first = service.importFile("SKILL.md", md, false, dev);
        assertThat(first.updated()).isFalse();
        assertThat(first.skill().name()).isEqualTo("查天气");
        assertThat(first.warnings()).anyMatch(w -> w.contains("license"));
        assertThat(versions).extracting(SkillVersion::getSource).containsExactly("IMPORT");

        assertThatThrownBy(() -> service.importFile("SKILL.md", md, false, dev)).hasMessageContaining("覆盖");
        assertThatThrownBy(() -> service.importFile("SKILL.md", "---\nname: 查天气\ndescription: x\n---\n改".getBytes(StandardCharsets.UTF_8), true, other))
                .isInstanceOf(IllegalStateException.class);
        SkillService.ImportResult second = service.importFile("SKILL.md",
                "---\nname: 查天气\ndescription: 问天气时用\n---\n1. 新步骤".getBytes(StandardCharsets.UTF_8), true, dev);
        assertThat(second.updated()).isTrue();
        assertThat(second.skill().version()).isEqualTo(2);
        assertThat(second.skill().content()).isEqualTo("1. 新步骤");
    }

    @Test
    void importsZipUsingFolderNameWhenNameMissing() throws Exception {
        byte[] zip = zip(Map.of(
                "weather-skill/SKILL.md", "---\ndescription: 问天气时用\n---\n步骤",
                "weather-skill/scripts/run.py", "print(1)",
                "__MACOSX/weather-skill/._SKILL.md", "junk"));
        SkillService.ImportResult result = service.importFile("weather.zip", zip, false, dev);
        assertThat(result.skill().name()).isEqualTo("weather-skill");
        assertThat(result.warnings()).anyMatch(w -> w.contains("1 个文件"));
    }

    @Test
    void zipWithSeveralSkillsIsRejected() throws Exception {
        byte[] zip = zip(Map.of("a/SKILL.md", "---\nname: a\ndescription: d\n---\nx", "b/SKILL.md", "---\nname: b\ndescription: d\n---\ny"));
        assertThatThrownBy(() -> service.importFile("bundle.zip", zip, false, dev)).hasMessageContaining("分别");
        assertThatThrownBy(() -> service.importFile("empty.zip", zip(Map.of("readme.md", "x")), false, dev))
                .hasMessageContaining("没有找到 SKILL.md");
    }

    @Test
    void restoreCreatesNewVersionWithOldContent() {
        SkillView s = service.create(request("a", "d", "第一版"), dev);
        SkillRequest edit = new SkillRequest();
        edit.setContent("第二版");
        service.update(s.id(), edit, dev);
        when(versionRepository.findBySkillIdAndVersion(s.id(), 1)).thenReturn(versions.stream().filter(v -> v.getVersion() == 1).findFirst());
        SkillView restored = service.restore(s.id(), 1, dev);
        assertThat(restored.version()).isEqualTo(3);
        assertThat(restored.content()).isEqualTo("第一版");
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
