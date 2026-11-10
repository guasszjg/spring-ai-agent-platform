package com.shimeta.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 一次对话里的技能：系统提示里的技能目录、load_skill 工具定义，以及模型调用 load_skill 时返回的内容。
 * 同一技能在一次对话里只返回一次完整步骤；正文按上下文窗口留出的预算截断。
 */
public class SkillSession {

    public static final String TOOL_NAME = "load_skill";
    /** 目录里每个技能描述最多显示多少字，完整描述在技能库里看 */
    static final int CATALOG_DESCRIPTION_CHARS = 300;
    /** 没有配置上下文窗口时，单个技能正文最多返回多少字 */
    static final int DEFAULT_MAX_CONTENT_CHARS = 20000;

    private final Map<String, SkillSpec> skills = new LinkedHashMap<>();
    private final Set<String> availableTools;
    private final Set<String> loaded = new LinkedHashSet<>();
    private final Consumer<String> loadRecorder;
    private Integer tokenBudget;

    public SkillSession(List<SkillSpec> specs, Set<String> availableTools, Consumer<String> loadRecorder) {
        for (SkillSpec spec : specs) {
            skills.putIfAbsent(spec.name(), spec);
        }
        Set<String> lower = new LinkedHashSet<>();
        if (availableTools != null) {
            availableTools.forEach(t -> lower.add(t.toLowerCase(Locale.ROOT)));
        }
        this.availableTools = lower;
        this.loadRecorder = loadRecorder;
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    /** 本次对话加载的技能正文一共最多能占多少 token；为空时不限（只按默认字数上限） */
    public void setTokenBudget(Integer tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    /** 最长的那个技能正文估算多少 token，用来决定给技能留多少上下文 */
    public int maxContentTokens() {
        return skills.values().stream().mapToInt(s -> estimateTokens(s.content()) + 60).max().orElse(0);
    }

    /** 和 AiChatService.estimateTokens 一样的粗算：中日韩字符每字约 1 个，其余每 4 个字符约 1 个 */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = text.codePoints().filter(Character::isIdeographic).count();
        long others = text.codePoints().count() - cjk;
        return (int) (cjk + (others + 3) / 4);
    }

    public List<String> loadedNames() {
        return List.copyOf(loaded);
    }

    /** 放在系统提示里的技能目录：只有名称和适用场景 */
    public String catalog() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【可用技能】\n")
                .append("以下每个技能对应一类问题的处理步骤。用户的问题符合某个技能的适用场景时，先调用 `")
                .append(TOOL_NAME).append("` 读取该技能的完整步骤，再按步骤处理；不符合任何技能时不要加载，直接回答。\n");
        for (SkillSpec spec : skills.values()) {
            sb.append("- ").append(spec.name()).append("：").append(shorten(oneLine(spec.description()))).append('\n');
        }
        return sb.toString().trim();
    }

    public Map<String, Object> toolDefinition() {
        Map<String, Object> nameParam = new LinkedHashMap<>();
        nameParam.put("type", "string");
        nameParam.put("enum", new ArrayList<>(skills.keySet()));
        nameParam.put("description", "要加载的技能名称，必须是【可用技能】里列出的名称之一");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of("name", nameParam));
        parameters.put("required", List.of("name"));
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", TOOL_NAME);
        fn.put("description", "读取一个技能的完整处理步骤。系统提示的【可用技能】列出了技能名称和适用场景，"
                + "判断某个技能适用于当前问题时调用本工具，再按返回的步骤处理。");
        fn.put("parameters", parameters);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    /** 处理一次 load_skill 调用；name 是模型传来的技能名 */
    public String load(String name) {
        String key = name == null ? "" : name.trim();
        SkillSpec spec = skills.get(key);
        if (spec == null) {
            spec = skills.values().stream().filter(s -> s.name().equalsIgnoreCase(key)).findFirst().orElse(null);
        }
        if (spec == null) {
            return "没有名为「" + key + "」的技能。可用技能：" + String.join("、", skills.keySet())
                    + "。如果都不适用，请直接回答用户。";
        }
        if (!loaded.add(spec.name())) {
            return "技能「" + spec.name() + "」已经加载过，请直接按前面返回的步骤继续处理。";
        }
        if (loadRecorder != null) {
            try {
                loadRecorder.accept(spec.id());
            } catch (RuntimeException ignored) {
                // 统计失败不影响对话
            }
        }
        StringBuilder sb = new StringBuilder("【技能：").append(spec.name()).append("】\n");
        List<String> missing = missingTools(spec);
        if (!missing.isEmpty()) {
            sb.append("注意：本技能需要的工具 ").append(String.join("、", missing))
                    .append(" 当前没有启用，用到这些工具的步骤无法执行，请如实告诉用户并给出其它可行的处理方式。\n");
        }
        sb.append('\n').append(truncate(spec.content() == null ? "" : spec.content().strip()));
        return sb.toString();
    }

    List<String> missingTools(SkillSpec spec) {
        List<String> missing = new ArrayList<>();
        if (spec.requiredTools() == null) {
            return missing;
        }
        for (String tool : spec.requiredTools()) {
            if (tool != null && !tool.isBlank() && !availableTools.contains(tool.trim().toLowerCase(Locale.ROOT))) {
                missing.add(tool.trim());
            }
        }
        return missing;
    }

    /** 按剩余预算截断正文；多个技能共用一份预算，加载一个扣一份 */
    private String truncate(String content) {
        String text = content.length() > DEFAULT_MAX_CONTENT_CHARS ? content.substring(0, DEFAULT_MAX_CONTENT_CHARS) : content;
        if (tokenBudget == null) {
            return text;
        }
        int tokens = estimateTokens(text);
        if (tokens <= tokenBudget) {
            tokenBudget -= tokens;
            return text;
        }
        int maxChars = (int) ((long) text.length() * tokenBudget / Math.max(1, tokens));
        if (maxChars < 100) {
            return "（当前模型通道的上下文窗口放不下这个技能的步骤，请直接根据已有信息回答用户。）";
        }
        tokenBudget = 0;
        return text.substring(0, maxChars) + "\n\n（技能步骤较长，超出当前上下文窗口的部分已省略。）";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String text) {
        return text.length() > CATALOG_DESCRIPTION_CHARS ? text.substring(0, CATALOG_DESCRIPTION_CHARS) + "…" : text;
    }
}
