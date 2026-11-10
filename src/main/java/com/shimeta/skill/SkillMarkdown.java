package com.shimeta.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读写 SKILL.md：开头 --- 包起来的 YAML 头（name、description 等），后面是 Markdown 正文。
 * 只解析技能需要的几种写法（单行值、引号、> 和 | 多行值、列表），不引入完整 YAML 解析；
 * 其它键（license、metadata、allowed-tools 等）读出来但不使用。
 */
public final class SkillMarkdown {

    /** 平台自己的键：技能需要的工具（函数名） */
    static final String KEY_REQUIRED_TOOLS = "required-tools";

    public record Parsed(String name, String description, String content, List<String> requiredTools,
                         List<String> ignoredKeys) {}

    private SkillMarkdown() {}

    public static Parsed parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("SKILL.md 内容为空");
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        String trimmedStart = normalized.stripLeading();
        if (!trimmedStart.startsWith("---")) {
            throw new IllegalArgumentException("SKILL.md 开头缺少 --- 包起来的 name 和 description");
        }
        String[] lines = trimmedStart.split("\n", -1);
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md 开头的 --- 没有闭合");
        }
        Map<String, Object> front = parseFrontMatter(lines, 1, end);
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1) {
                body.append('\n');
            }
        }
        String name = asText(front.get("name"));
        String description = asText(front.get("description"));
        List<String> tools = asList(front.get(KEY_REQUIRED_TOOLS));
        List<String> ignored = new ArrayList<>();
        for (String key : front.keySet()) {
            if (!key.equals("name") && !key.equals("description") && !key.equals(KEY_REQUIRED_TOOLS)) {
                ignored.add(key);
            }
        }
        return new Parsed(name, description, body.toString().strip(), tools, ignored);
    }

    public static String format(Skill skill) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("name: ").append(quote(skill.getName())).append('\n');
        sb.append("description: ").append(quote(skill.getDescription())).append('\n');
        List<String> tools = skill.getRequiredTools();
        if (tools != null && !tools.isEmpty()) {
            sb.append(KEY_REQUIRED_TOOLS).append(":\n");
            for (String tool : tools) {
                sb.append("  - ").append(quote(tool)).append('\n');
            }
        }
        sb.append("---\n\n");
        String content = skill.getContent() == null ? "" : skill.getContent().strip();
        return sb.append(content).append('\n').toString();
    }

    /** YAML 双引号写法：转义反斜杠、双引号和换行 */
    static String quote(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static Map<String, Object> parseFrontMatter(String[] lines, int from, int to) {
        Map<String, Object> result = new LinkedHashMap<>();
        int i = from;
        while (i < to) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#") || Character.isWhitespace(line.charAt(0))) {
                i++;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                i++;
                continue;
            }
            String key = line.substring(0, colon).trim();
            String rest = stripComment(line.substring(colon + 1).trim());
            i++;
            if (rest.startsWith(">") || rest.startsWith("|")) {
                boolean folded = rest.startsWith(">");
                List<String> block = new ArrayList<>();
                while (i < to && (lines[i].isBlank() || Character.isWhitespace(lines[i].charAt(0)))) {
                    block.add(lines[i].strip());
                    i++;
                }
                result.put(key, joinBlock(block, folded));
            } else if (rest.isEmpty()) {
                List<String> items = new ArrayList<>();
                boolean nested = false;
                while (i < to && (lines[i].isBlank() || Character.isWhitespace(lines[i].charAt(0)))) {
                    String item = lines[i].strip();
                    if (item.startsWith("- ") || item.equals("-")) {
                        items.add(unquote(item.substring(1).trim()));
                    } else if (!item.isEmpty()) {
                        nested = true;
                    }
                    i++;
                }
                result.put(key, nested && items.isEmpty() ? "" : items);
            } else if (rest.startsWith("[") && rest.endsWith("]")) {
                List<String> items = new ArrayList<>();
                for (String part : rest.substring(1, rest.length() - 1).split(",")) {
                    String v = unquote(part.trim());
                    if (!v.isEmpty()) {
                        items.add(v);
                    }
                }
                result.put(key, items);
            } else {
                // 普通值可能折到下一行（缩进续行）
                StringBuilder value = new StringBuilder(rest);
                boolean quoted = rest.startsWith("\"") || rest.startsWith("'");
                while (i < to && !lines[i].isBlank() && Character.isWhitespace(lines[i].charAt(0))) {
                    value.append(' ').append(lines[i].strip());
                    i++;
                }
                result.put(key, quoted ? unquote(value.toString()) : value.toString());
            }
        }
        return result;
    }

    private static String joinBlock(List<String> block, boolean folded) {
        while (!block.isEmpty() && block.get(block.size() - 1).isEmpty()) {
            block.remove(block.size() - 1);
        }
        if (!folded) {
            return String.join("\n", block);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : block) {
            if (part.isEmpty()) {
                sb.append('\n');
            } else {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private static String stripComment(String value) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            return value;
        }
        int hash = value.indexOf(" #");
        return hash >= 0 ? value.substring(0, hash).trim() : value;
    }

    static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            String inner = value.substring(1, value.length() - 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    char n = inner.charAt(++i);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append('\\').append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    private static String asText(Object value) {
        if (value instanceof String s) {
            String v = s.strip();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private static List<String> asList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
            return out;
        }
        if (value instanceof String s && !s.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String part : s.split("[,，\\s]+")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
            return out;
        }
        return List.of();
    }
}
