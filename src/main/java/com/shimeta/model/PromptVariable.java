package com.shimeta.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体自定义提示词变量定义。
 * 支持在系统提示词或开场白中以 {{key}} 引用，并在调试页、分享页表单及开放接口 inputs 中动态传值。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptVariable {

    public static final int MAX_VARIABLES_PER_AGENT = 20;
    public static final int MAX_DEFAULT_VALUE_LENGTH = 500;
    public static final int MAX_INPUT_VALUE_LENGTH = 2000;
    public static final int MAX_SELECT_OPTIONS = 30;

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_PARAGRAPH = "paragraph";
    public static final String TYPE_SELECT = "select";
    public static final String TYPE_NUMBER = "number";

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,31}$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_.]*)\\}\\}");

    public static final Set<String> RESERVED_KEYS = Set.of(
            "input", "system_time", "sys.time", "time", "sys.date", "date", "today"
    );

    private static final Set<String> ALLOWED_TYPES = Set.of(
            TYPE_TEXT, TYPE_PARAGRAPH, TYPE_SELECT, TYPE_NUMBER
    );

    private String key;
    private String label;
    private String type = TYPE_TEXT;
    private Boolean required = false;
    private String defaultValue;
    private List<String> options = new ArrayList<>();
    private String placeholder;

    public PromptVariable() {
    }

    public PromptVariable(String key, String label, String type, Boolean required,
                          String defaultValue, List<String> options, String placeholder) {
        this.key = key;
        this.label = label;
        this.type = type;
        this.required = required;
        this.defaultValue = defaultValue;
        this.options = options != null ? new ArrayList<>(options) : new ArrayList<>();
        this.placeholder = placeholder;
    }

    public PromptVariable copy() {
        return new PromptVariable(
                key,
                label,
                type,
                required,
                defaultValue,
                options != null ? new ArrayList<>(options) : new ArrayList<>(),
                placeholder
        );
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options != null ? new ArrayList<>(options) : new ArrayList<>();
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    /** 校验并规范化变量列表 */
    public static List<PromptVariable> normalizeList(List<PromptVariable> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        if (raw.size() > MAX_VARIABLES_PER_AGENT) {
            throw new IllegalArgumentException("单个智能体最多支持 " + MAX_VARIABLES_PER_AGENT + " 个自定义变量");
        }
        List<PromptVariable> result = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        for (PromptVariable item : raw) {
            if (item == null) {
                continue;
            }
            String k = item.getKey() == null ? "" : item.getKey().trim();
            if (k.isEmpty()) {
                throw new IllegalArgumentException("变量名不能为空");
            }
            if (!KEY_PATTERN.matcher(k).matches()) {
                throw new IllegalArgumentException("变量名 \"" + k + "\" 格式不正确：需以英文字母开头，仅含字母、数字或下划线，最长 32 位");
            }
            if (RESERVED_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("变量名 \"" + k + "\" 为系统内置保留变量，请换一个名称");
            }
            if (!seenKeys.add(k)) {
                throw new IllegalArgumentException("存在重复的变量名: \"" + k + "\"");
            }

            String lbl = item.getLabel() == null ? "" : item.getLabel().trim();
            if (lbl.isEmpty()) {
                lbl = k;
            } else if (lbl.length() > 40) {
                lbl = lbl.substring(0, 40);
            }

            String t = item.getType() == null || item.getType().isBlank()
                    ? TYPE_TEXT
                    : item.getType().trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_TYPES.contains(t)) {
                throw new IllegalArgumentException("变量 \"" + k + "\" 的类型不支持: " + t);
            }

            List<String> opts = new ArrayList<>();
            if (TYPE_SELECT.equals(t)) {
                Set<String> dedup = new LinkedHashSet<>();
                if (item.getOptions() != null) {
                    for (String opt : item.getOptions()) {
                        if (opt == null) continue;
                        String trimmed = opt.trim();
                        if (!trimmed.isEmpty()) {
                            dedup.add(trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed);
                        }
                    }
                }
                if (dedup.isEmpty()) {
                    throw new IllegalArgumentException("下拉变量 \"" + lbl + "\" 至少需要 1 个选项");
                }
                if (dedup.size() > MAX_SELECT_OPTIONS) {
                    throw new IllegalArgumentException("下拉变量 \"" + lbl + "\" 最多支持 " + MAX_SELECT_OPTIONS + " 个选项");
                }
                opts.addAll(dedup);
            }

            String defVal = item.getDefaultValue() == null ? "" : item.getDefaultValue().trim();
            if (defVal.length() > MAX_DEFAULT_VALUE_LENGTH) {
                throw new IllegalArgumentException("变量 \"" + lbl + "\" 默认值不能超过 " + MAX_DEFAULT_VALUE_LENGTH + " 个字符");
            }
            if (TYPE_SELECT.equals(t) && !defVal.isEmpty() && !opts.contains(defVal)) {
                defVal = opts.get(0);
            }

            String ph = item.getPlaceholder() == null ? "" : item.getPlaceholder().trim();
            if (ph.length() > 100) {
                ph = ph.substring(0, 100);
            }

            result.add(new PromptVariable(
                    k,
                    lbl,
                    t,
                    Boolean.TRUE.equals(item.getRequired()),
                    defVal,
                    opts,
                    ph
            ));
        }
        return result;
    }

    /**
     * 解析本轮对话实际生效的自定义变量值：
     * 1. 优先使用请求传入的 inputs 值；
     * 2. 未传或为空时回退到变量定义的 defaultValue；
     * 3. 仍为空且 required=true 时（当 enforceRequired=true）抛出 IllegalArgumentException；
     * 4. 请求中额外传入的合法自定义变量（未在定义中）也会保留，方便草稿调试。
     */
    public static Map<String, String> resolveInputs(List<PromptVariable> definitions,
                                                    Map<String, Object> provided,
                                                    boolean enforceRequired) {
        Map<String, String> resolved = new LinkedHashMap<>();
        Set<String> definedKeys = new LinkedHashSet<>();
        if (definitions != null) {
            for (PromptVariable def : definitions) {
                if (def == null || def.getKey() == null || def.getKey().isBlank()) {
                    continue;
                }
                String k = def.getKey().trim();
                definedKeys.add(k);
                String val = readInputValue(provided, k);
                if (val.isEmpty() && def.getDefaultValue() != null) {
                    val = def.getDefaultValue().trim();
                }
                if (val.isEmpty() && enforceRequired && Boolean.TRUE.equals(def.getRequired())) {
                    String display = def.getLabel() != null && !def.getLabel().isBlank() ? def.getLabel() : k;
                    throw new IllegalArgumentException("缺少必填变量「" + display + "」(" + k + ")");
                }
                resolved.put(k, val);
            }
        }
        if (provided != null && !provided.isEmpty()) {
            for (Map.Entry<String, Object> entry : provided.entrySet()) {
                String k = entry.getKey() == null ? "" : entry.getKey().trim();
                if (k.isEmpty() || definedKeys.contains(k)) {
                    continue;
                }
                if (!KEY_PATTERN.matcher(k).matches() || RESERVED_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                resolved.put(k, stringify(entry.getValue()));
            }
        }
        return resolved;
    }

    /**
     * 单遍正则替换模板中的 {{key}}，避免变量值里含 {{...}} 引发二次替换或注入。
     */
    public static String renderTemplate(String template, Map<String, String> values) {
        if (template == null || template.isEmpty() || values == null || values.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String key = matcher.group(1);
            if (values.containsKey(key)) {
                String replacement = values.get(key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : ""));
                changed = true;
            }
        }
        if (!changed) {
            return template;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String readInputValue(Map<String, Object> provided, String key) {
        if (provided == null || !provided.containsKey(key)) {
            return "";
        }
        return stringify(provided.get(key));
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value).trim();
        return s.length() > MAX_INPUT_VALUE_LENGTH ? s.substring(0, MAX_INPUT_VALUE_LENGTH) : s;
    }
}
