package com.shimeta.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.security.CurrentActor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 平台 AI 助手能力清单的单一来源（{@code assistant/capabilities.json}）。
 *
 * <p>同时服务三个场景：
 * <ol>
 *   <li>启动/对话时按当前用户角色动态拼装系统提示词中的菜单总览（不再在 {@link AssistantPrompts} 里手写）；</li>
 *   <li>回答后置校验（{@link AssistantAnswerVerifier}）核对菜单名是否真实存在；</li>
 *   <li>单元测试守门：校验与前端菜单、工具注册表、平台文档锚点的一致性。</li>
 * </ol>
 */
@Component
public class AssistantCapabilities {

    static final String RESOURCE_PATH = "assistant/capabilities.json";
    private static final AssistantCapabilities DEFAULT_INSTANCE = loadDefault();

    private final String version;
    private final List<MenuGroup> menus;
    private final List<String> extraValidUiTerms;
    private final List<CapabilityEntry> capabilities;
    private final Set<String> validMenuSegments;

    public AssistantCapabilities() {
        this(DEFAULT_INSTANCE);
    }

    private AssistantCapabilities(AssistantCapabilities source) {
        this.version = source.version;
        this.menus = source.menus;
        this.extraValidUiTerms = source.extraValidUiTerms;
        this.capabilities = source.capabilities;
        this.validMenuSegments = source.validMenuSegments;
    }

    private AssistantCapabilities(String version, List<MenuGroup> menus, List<String> extraValidUiTerms,
                                  List<CapabilityEntry> capabilities) {
        this.version = version;
        this.menus = Collections.unmodifiableList(menus);
        this.extraValidUiTerms = Collections.unmodifiableList(extraValidUiTerms);
        this.capabilities = Collections.unmodifiableList(capabilities);
        this.validMenuSegments = Collections.unmodifiableSet(collectValidSegments(menus, extraValidUiTerms, capabilities));
    }

    public static AssistantCapabilities defaultInstance() {
        return DEFAULT_INSTANCE;
    }

    static AssistantCapabilities loadDefault() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            String version = root.path("version").asText("1.0.0");

            List<MenuGroup> menus = new ArrayList<>();
            for (JsonNode g : root.path("menus")) {
                List<MenuItem> items = new ArrayList<>();
                for (JsonNode it : g.path("items")) {
                    List<MenuChild> children = new ArrayList<>();
                    for (JsonNode ch : it.path("children")) {
                        children.add(new MenuChild(
                                ch.path("id").asText(""),
                                ch.path("name").asText(""),
                                stringList(ch.path("roles"))
                        ));
                    }
                    items.add(new MenuItem(
                            it.path("id").asText(""),
                            it.path("name").asText(""),
                            it.path("summary").asText(""),
                            it.path("superAdminOnly").asBoolean(false),
                            stringList(it.path("roles")),
                            stringList(it.path("subpages")),
                            Collections.unmodifiableList(children)
                    ));
                }
                menus.add(new MenuGroup(
                        g.path("id").asText(""),
                        g.path("title").asText(""),
                        g.hasNonNull("nonAdminTitle") ? g.path("nonAdminTitle").asText() : null,
                        Collections.unmodifiableList(items)
                ));
            }

            List<String> extraTerms = stringList(root.path("extraValidUiTerms"));

            List<CapabilityEntry> entries = new ArrayList<>();
            for (JsonNode c : root.path("capabilities")) {
                entries.add(new CapabilityEntry(
                        c.path("id").asText(""),
                        c.path("menu").asText(""),
                        c.path("tabId").asText(""),
                        c.path("action").asText(""),
                        c.path("handling").asText(""),
                        c.hasNonNull("risk") ? c.path("risk").asText() : null,
                        c.hasNonNull("tool") ? c.path("tool").asText() : null,
                        stringList(c.path("roles")),
                        c.hasNonNull("docAnchor") ? c.path("docAnchor").asText() : null,
                        c.hasNonNull("plannedHandling") ? c.path("plannedHandling").asText() : null,
                        c.hasNonNull("plannedRisk") ? c.path("plannedRisk").asText() : null
                ));
            }
            return new AssistantCapabilities(version, menus, extraTerms, entries);
        } catch (IOException e) {
            throw new IllegalStateException("加载平台 AI 助手能力清单失败: " + RESOURCE_PATH, e);
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                list.add(text);
            }
        }
        return Collections.unmodifiableList(list);
    }

    private static Set<String> collectValidSegments(List<MenuGroup> menus, List<String> extraTerms,
                                                    List<CapabilityEntry> capabilities) {
        Set<String> set = new LinkedHashSet<>();
        for (MenuGroup g : menus) {
            addNonBlank(set, g.title());
            addNonBlank(set, g.nonAdminTitle());
            for (MenuItem item : g.items()) {
                addNonBlank(set, item.name());
                for (String sub : item.subpages()) {
                    addNonBlank(set, sub);
                }
                for (MenuChild ch : item.children()) {
                    addNonBlank(set, ch.name());
                }
            }
        }
        for (String term : extraTerms) {
            addNonBlank(set, term);
        }
        for (CapabilityEntry entry : capabilities) {
            if (entry.menu() != null) {
                for (String seg : entry.menu().split("[›→>/]")) {
                    addNonBlank(set, seg);
                }
            }
        }
        return set;
    }

    private static void addNonBlank(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value.trim());
        }
    }

    public String version() {
        return version;
    }

    public List<MenuGroup> menus() {
        return menus;
    }

    public List<String> extraValidUiTerms() {
        return extraValidUiTerms;
    }

    public List<CapabilityEntry> capabilities() {
        return capabilities;
    }

    public Set<String> validMenuSegments() {
        return validMenuSegments;
    }

    /**
     * 按角色动态拼装系统提示词里的菜单总览。
     * {@code actor == null} 或超级管理员时展示全量菜单；非超管时展示「规则与安全」分组并隐藏仅超管可见入口。
     */
    public String buildPlatformOverview(CurrentActor actor) {
        boolean isSuperAdmin = actor == null || actor.isSuperAdmin();
        StringBuilder sb = new StringBuilder("平台核心模块与左侧菜单（仅以下菜单，勿编造）：\n");
        for (MenuGroup group : menus) {
            List<MenuItem> visibleItems = group.items().stream()
                    .filter(it -> isSuperAdmin || !it.superAdminOnly())
                    .toList();
            if (visibleItems.isEmpty()) {
                continue;
            }
            String groupTitle = isSuperAdmin
                    ? group.title() + (group.nonAdminTitle() != null ? "（非超管为「" + group.nonAdminTitle() + "」）" : "")
                    : (group.nonAdminTitle() != null ? group.nonAdminTitle() : group.title());
            sb.append("- ").append(groupTitle).append("：");
            List<String> parts = new ArrayList<>();
            for (MenuItem item : visibleItems) {
                if (!item.children().isEmpty()) {
                    String childNames = String.join("、", item.children().stream().map(MenuChild::name).toList());
                    parts.add(item.name() + "（" + childNames + "）");
                } else if (item.summary() != null && !item.summary().isBlank()) {
                    parts.add(item.name() + "（" + item.summary() + "）");
                } else {
                    parts.add(item.name());
                }
            }
            sb.append(String.join("、", parts));
            if (isSuperAdmin && "governance".equals(group.id())) {
                sb.append("（其中模型网关、用户、角色与权限、助手评测仅超管可见）");
            }
            sb.append('\n');
        }
        sb.append("角色：超级管理员、开发者、只读观察员。智能体状态只有：运行中、空闲中、已停用（无草稿/已发布/已下线）。\n");
        return sb.toString();
    }

    public record MenuGroup(String id, String title, String nonAdminTitle, List<MenuItem> items) {
    }

    public record MenuItem(String id, String name, String summary, boolean superAdminOnly,
                           List<String> roles, List<String> subpages, List<MenuChild> children) {
    }

    public record MenuChild(String id, String name, List<String> roles) {
    }

    public record CapabilityEntry(String id, String menu, String tabId, String action, String handling,
                                  String risk, String tool, List<String> roles, String docAnchor,
                                  String plannedHandling, String plannedRisk) {
    }
}
