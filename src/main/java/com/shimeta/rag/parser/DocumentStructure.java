package com.shimeta.rag.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从没有结构信息的文本里恢复文档结构（主要用于 PDF）：
 * 去掉每页重复的页眉页脚、去掉目录，把带编号的章节标题标成 Markdown 标题（# / ## / ###），
 * 切片时据此给每个切片加上「文档名 › 章 › 节」的上下文标题。
 */
public final class DocumentStructure {

    private DocumentStructure() {}

    /** 目录行：标题后面是省略号（PDF 解析时点状引导线已换成 …）和页码，或 Word 目录里失效的书签 */
    private static final Pattern TOC_LINE = Pattern.compile("^\\S.{0,80}?\\s…\\s*\\d{1,4}$|错误[!！]未定义书签");
    /** 编号标题：1 / 2.1 / 2.2.1 / 2.2.1.1 后面跟空格和不太长的标题文字 */
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,3}){0,4})[ \\t\\u3000]+(\\S.{0,48})$");
    /** 标题结尾不会是句末标点；以这些结尾的多半是正文里的编号句子 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。；;，,：:、！!？?]$");
    /** 标题中间也不会有逗号、句号、分号 */
    private static final Pattern SENTENCE_PUNCT = Pattern.compile("[，。；]");
    private static final Pattern TOC_NUMBER = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,3}){0,4})[ \\t\\u3000]+\\S");
    private static final int MIN_TOC_LINES = 5;

    /**
     * 去掉每页开头、结尾重复出现的行（页眉页脚、页码）。数字统一看作同一个字符再比较，
     * 「Page 3 of 67」「Page 4 of 67」算同一行；至少一半的页、且不少于 3 页出现才算重复。
     */
    public static List<String> stripRepeatedPageLines(List<String> pages) {
        if (pages.size() < 3) {
            return pages;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String page : pages) {
            for (String line : edgeLines(page)) {
                counts.merge(normalizeEdge(line), 1, Integer::sum);
            }
        }
        int threshold = Math.max(3, (pages.size() + 1) / 2);
        // 重复行占比太高时多半不是页眉页脚，而是每页内容本身就相似，不处理
        int totalLines = 0;
        int repeatedLines = 0;
        for (String page : pages) {
            for (String line : page.split("\n")) {
                if (!line.isBlank()) {
                    totalLines++;
                }
            }
            for (String line : edgeLines(page)) {
                if (counts.getOrDefault(normalizeEdge(line), 0) >= threshold) {
                    repeatedLines++;
                }
            }
        }
        if (repeatedLines == 0 || repeatedLines * 10 > totalLines * 3) {
            return pages;
        }
        List<String> result = new ArrayList<>(pages.size());
        for (String page : pages) {
            List<String> lines = new ArrayList<>(List.of(page.split("\n", -1)));
            removeRepeatedEdge(lines, counts, threshold, true);
            removeRepeatedEdge(lines, counts, threshold, false);
            result.add(String.join("\n", lines).trim());
        }
        return result;
    }

    private static List<String> edgeLines(String page) {
        List<String> nonEmpty = new ArrayList<>();
        for (String line : page.split("\n")) {
            if (!line.isBlank()) {
                nonEmpty.add(line.trim());
            }
        }
        List<String> edges = new ArrayList<>();
        for (int i = 0; i < Math.min(2, nonEmpty.size()); i++) {
            edges.add(nonEmpty.get(i));
        }
        for (int i = Math.max(2, nonEmpty.size() - 2); i < nonEmpty.size(); i++) {
            edges.add(nonEmpty.get(i));
        }
        return edges;
    }

    private static void removeRepeatedEdge(List<String> lines, Map<String, Integer> counts, int threshold, boolean fromTop) {
        int checked = 0;
        int i = fromTop ? 0 : lines.size() - 1;
        while (i >= 0 && i < lines.size() && checked < 2) {
            String line = lines.get(i);
            if (line.isBlank()) {
                if (fromTop) {
                    i++;
                } else {
                    i--;
                }
                continue;
            }
            checked++;
            if (counts.getOrDefault(normalizeEdge(line.trim()), 0) >= threshold) {
                lines.remove(i);
                if (!fromTop) {
                    i--;
                }
            } else {
                break;
            }
        }
    }

    private static String normalizeEdge(String line) {
        return line.replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
    }

    /** 去掉目录：只有目录行足够多（{@value #MIN_TOC_LINES} 行以上）才认为是目录，避免误删正文 */
    public static String removeTableOfContents(String text) {
        String[] lines = text.split("\n", -1);
        int tocLines = 0;
        for (String line : lines) {
            if (TOC_LINE.matcher(line.trim()).find()) {
                tocLines++;
            }
        }
        if (tocLines < MIN_TOC_LINES) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!TOC_LINE.matcher(line.trim()).find()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /**
     * 把带编号的章节标题标成 Markdown 标题。为避免把正文里的编号句子当成标题，编号必须和前面的标题接得上：
     * 同级编号递增（允许跳过一个），下级编号的上级必须是当前所在的章节，例如 2.2 之后才认 2.2.1。
     * 已经是 Markdown 标题的文本（Markdown、HTML、带标题样式的 Word）原样返回。
     */
    public static String markNumberedHeadings(String text) {
        return markNumberedHeadings(text, Map.of());
    }

    /**
     * 先读出目录，再去掉目录、标出标题。目录覆盖到的级别只认目录里列出的「编号 + 标题」，
     * 更深的级别（目录通常只列到三级）按编号顺序判断
     */
    public static String structure(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return markNumberedHeadings(removeTableOfContents(text), tableOfContents(text));
    }

    /** 目录里列出的章节编号 */
    public static java.util.Set<String> tableOfContentsNumbers(String text) {
        return tableOfContents(text).keySet();
    }

    /** 目录：章节编号 → 去掉空白的标题；目录行太少时视为没有目录，返回空 */
    static Map<String, String> tableOfContents(String text) {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        int tocLines = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (TOC_LINE.matcher(trimmed).find()) {
                tocLines++;
                Matcher m = TOC_NUMBER.matcher(trimmed);
                if (m.find()) {
                    String title = trimmed.substring(m.group(1).length())
                            .replaceAll("….*$", "")
                            .replaceAll("\\s+", "");
                    entries.putIfAbsent(m.group(1), title);
                }
            }
        }
        return tocLines >= MIN_TOC_LINES ? entries : Map.of();
    }

    static String markNumberedHeadings(String text, Map<String, String> toc) {
        if (text == null || text.isBlank() || hasMarkdownHeadings(text)) {
            return text;
        }
        int tocDepth = 0;
        for (String n : toc.keySet()) {
            tocDepth = Math.max(tocDepth, n.split("\\.").length);
        }
        String[] lines = text.split("\n", -1);
        List<Integer> current = new ArrayList<>();
        int marked = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher m = NUMBERED_HEADING.matcher(line);
            if (!m.matches() || SENTENCE_END.matcher(line).find() || SENTENCE_PUNCT.matcher(m.group(2)).find()
                    || line.contains(" … ")) {
                continue;
            }
            List<Integer> number = parseNumber(m.group(1));
            if (number == null || !followsOn(current, number)) {
                continue;
            }
            if (number.size() <= tocDepth && !matchesToc(toc, m.group(1), m.group(2))) {
                continue;
            }
            current = number;
            lines[i] = "#".repeat(Math.min(6, number.size())) + " " + line;
            marked++;
        }
        // 只认出一两个时多半是巧合，不改原文
        return marked >= 2 ? String.join("\n", lines) : text;
    }

    /**
     * 编号在目录里，且标题和目录里的相近：较短一方至少一半的字在另一方出现。
     * 目录没随正文更新时标题会有出入（如「U 盘导入单屏节目使用说明」与正文「U 盘导入节目使用说明」），不要求完全一致
     */
    static boolean matchesToc(Map<String, String> toc, String number, String title) {
        String tocTitle = toc.get(number);
        if (tocTitle == null) {
            return false;
        }
        String normalized = title.replaceAll("\\s+", "");
        if (tocTitle.isEmpty() || normalized.startsWith(tocTitle) || tocTitle.startsWith(normalized)) {
            return true;
        }
        String shorter = normalized.length() <= tocTitle.length() ? normalized : tocTitle;
        String longer = shorter == normalized ? tocTitle : normalized;
        long distinct = shorter.codePoints().distinct().count();
        long shared = shorter.codePoints().distinct().filter(c -> longer.indexOf(c) >= 0).count();
        return distinct > 0 && shared * 2 >= distinct;
    }

    static boolean hasMarkdownHeadings(String text) {
        return Pattern.compile("(?m)^#{1,6} \\S").matcher(text).find();
    }

    private static List<Integer> parseNumber(String number) {
        List<Integer> parts = new ArrayList<>();
        for (String part : number.split("\\.")) {
            try {
                parts.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return parts;
    }

    /** 编号能不能接在当前章节后面 */
    static boolean followsOn(List<Integer> current, List<Integer> next) {
        int level = next.size();
        int last = next.get(level - 1);
        if (last < 1) {
            return false;
        }
        if (current.isEmpty()) {
            // 文档里第一个标题：从 1（或 2，前面那个漏认了）开始，且只能是一级
            return level == 1 && last <= 2;
        }
        if (level > current.size() + 1) {
            return false;
        }
        // 上级编号必须和当前所在章节一致
        for (int i = 0; i < level - 1; i++) {
            if (!next.get(i).equals(current.get(i))) {
                return false;
            }
        }
        if (level == current.size() + 1) {
            return last <= 2;
        }
        int previous = current.get(level - 1);
        return last > previous && last <= previous + 2;
    }
}
