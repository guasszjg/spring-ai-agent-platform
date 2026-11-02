package com.shimeta.service;

import java.util.regex.Pattern;

/**
 * 流式输出时去掉模型回答里的引用角标（[1]、【2】、¹²），与 {@link AiChatService#cleanAnswer} 的规则一致。
 * 角标可能被拆在两段之间（如 "[" 与 "1]"），所以末尾未闭合的角标先留着，等下一段或结束时再处理。
 */
public class StreamingCitationStripper {

    private static final Pattern MARKERS = Pattern.compile("\\[\\d+\\]|【\\d+】|[¹²³⁴⁵⁶⁷⁸⁹⁰]+");
    /** 末尾可能还没写完的角标：左括号后只跟了数字（最多 4 位） */
    private static final Pattern PENDING_TAIL = Pattern.compile("(\\[|【)\\d{0,4}$");

    private final StringBuilder buffer = new StringBuilder();

    /** 追加一段模型输出，返回可以安全输出的部分（可能为空串） */
    public String push(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        buffer.append(text);
        var tail = PENDING_TAIL.matcher(buffer);
        int keepFrom = tail.find() ? tail.start() : buffer.length();
        String ready = buffer.substring(0, keepFrom);
        buffer.delete(0, keepFrom);
        return MARKERS.matcher(ready).replaceAll("");
    }

    /** 输出结束：返回缓冲区里剩下的内容 */
    public String flush() {
        String rest = MARKERS.matcher(buffer).replaceAll("");
        buffer.setLength(0);
        return rest;
    }
}
