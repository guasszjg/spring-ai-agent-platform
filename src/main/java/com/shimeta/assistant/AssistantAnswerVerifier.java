package com.shimeta.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台 AI 助手回答后置校验器（4.6 零延迟防编造检查）。
 *
 * <p>在 {@code turn.finish()} 之后仅基于本轮上下文做确定性检查，不额外调用模型，不增加延迟：
 * <ol>
 *   <li><b>无依据报数据（UNGROUNDED_DATA）</b>：执行模式下命中数据查询类问题但未成功调用任何工具，回答却报出具体数据或资源名 → 附加提示并记日志；</li>
 *   <li><b>菜单名不存在（UNKNOWN_MENU）</b>：回答中出现的菜单路径或菜单名称不在能力清单中 → 记日志并标记待复盘；</li>
 *   <li><b>链接是编造的（FABRICATED_LINK）</b>：Markdown 链接不在本轮工具返回的合法链接集合中 → 去掉链接仅保留文字；</li>
 *   <li><b>提前宣布完成（PREMATURE_COMPLETION / CARD_CLAIM）</b>：没调写工具却声称生成卡片，或卡片未确认却声称已创建/已修改完成 → 附加提示并记日志。</li>
 * </ol>
 */
@Component
public class AssistantAnswerVerifier {

    private static final Logger log = LoggerFactory.getLogger(AssistantAnswerVerifier.class);

    public static final String FLAG_UNGROUNDED_DATA = "UNGROUNDED_DATA";
    public static final String FLAG_UNKNOWN_MENU = "UNKNOWN_MENU";
    public static final String FLAG_FABRICATED_LINK = "FABRICATED_LINK";
    public static final String FLAG_CARD_CLAIM = "CARD_CLAIM";
    public static final String FLAG_PREMATURE_COMPLETION = "PREMATURE_COMPLETION";

    public static final String UNGROUNDED_DATA_NOTICE = "本次没有查询平台数据，以上内容可能不准确。";
    public static final String PREMATURE_COMPLETION_NOTICE = "注意：上述操作尚未执行，请点击上方操作卡片中的「确认」按钮后才会生效。";

    /** 匹配 Markdown 链接 [label](url)，排除图片 ![alt](url) */
    private static final Pattern MARKDOWN_LINK = Pattern.compile("(?<!!)\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)");

    /** 匹配带导航箭头的菜单路径，如 「管理 → 模型网关」「构建 › 智能体」或 **监控 → 概览** */
    private static final Pattern MENU_PATH_QUOTED = Pattern.compile("[「\"“]([^」\"”\\n]{2,40}[→›][^」\"”\\n]{1,40})[」\"”]");
    private static final Pattern MENU_PATH_BOLD = Pattern.compile("\\*\\*([^*\\n]{2,40}[→›][^*\\n]{1,40})\\*\\*");

    /** 明确在指引菜单但说出了平台不存在的常见幻觉菜单名 */
    private static final Pattern HALLUCINATED_MENU = Pattern.compile(
            "(?:在|到|进入|打开|点击|前往|左侧|导航|菜单)[^。\\n]{0,8}[「\"“*]{1,2}(工作台|控制面板|系统设置|模型管理|权限中心|应用管理|知识管理|仪表盘|设置中心)[」\"”*]{1,2}");

    /** 问句明显属于查询平台实际数据（READ 组） */
    private static final Pattern READ_DATA_QUESTION = Pattern.compile(
            "(?i)(我有哪些|平台有哪些|平台上有哪些|有哪些.*(智能体|知识库|文档|mcp|技能|模板|通道)|"
                    + "多少\\s*(个|条|次|token)|用了多少|花了多少|成本多少|消耗了多少|"
                    + "使用最多|调用最多|排行|统计|现在.*(正常吗|可用吗|通不通|激活的是哪个)|"
                    + "绑定了哪些|用的是什么模型|索引失败|里有哪些文档)");
    /** 纯概念/操作问法（非查询实时数据） */
    private static final Pattern PURE_HOWTO_QUESTION = Pattern.compile(
            "(?i)(有哪几种|有什么区别|是什么意思|怎么用|如何配置|怎么创建|怎么停用|怎么办|为什么会)");

    /** 回答中出现带单位的具体数据或引号包裹的资源名 */
    private static final Pattern CONCRETE_DATA_CLAIM = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?\\s*(?:个|条|次|篇|元|%|tokens?|ms|毫秒|秒)|[¥￥]\\s*\\d|「[^」\\n]{2,20}」)");
    /** 回答已明确声明未查到/无法查询/无权限时，不误报 */
    private static final Pattern DISCLAIMER_PHRASE = Pattern.compile(
            "(无法查询|没有查询|无法获取|未能获取|无权|没有权限|只读观察员|暂不支持|请到|切换到.*执行)");

    /** 存在未确认待执行卡片时，模型却宣称操作已经完成 */
    private static final Pattern PREMATURE_EXECUTION = Pattern.compile(
            "(已|已经)(为你|为您|帮你|帮您)?(成功)?(创建|新建|修改|更新|停用|启用|绑定|解绑|签发|添加)(成功|完成|好了|完毕|生效)");
    /** 同一子句中的否定或条件限定，避免把「确认后才会创建成功」误判为提前宣布完成 */
    private static final Pattern NEGATION_OR_CONDITIONAL = Pattern.compile(
            "(尚未|还未|还没|未|不会|并没有|确认后|点击确认|待确认)");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AssistantCapabilities capabilities;

    @Autowired
    public AssistantAnswerVerifier(AssistantCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public AssistantAnswerVerifier() {
        this(AssistantCapabilities.defaultInstance());
    }

    /** 从工具返回的 JSON 中递归收集所有 link 字段，作为本轮合法跳转链接白名单。 */
    public static Set<String> extractLinksFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        Set<String> links = new LinkedHashSet<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            collectLinks(root, links);
        } catch (Exception ignored) {
            // 截断或非 JSON 内容忽略
        }
        return links;
    }

    private static void collectLinks(JsonNode node, Set<String> links) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (("link".equals(key) || "url".equals(key)) && value.isTextual()) {
                    String url = value.asText("").trim();
                    if (!url.isEmpty()) {
                        links.add(url);
                    }
                } else if (value.isContainerNode()) {
                    collectLinks(value, links);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectLinks(item, links);
            }
        }
    }

    /** 执行全部 4 项零延迟事后校验。 */
    public VerificationResult verify(VerificationInput input) {
        String content = input.content() == null ? "" : input.content();
        List<String> flags = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        // 1. 链接校验：去掉不在白名单里的编造 Markdown 链接，保留显示文字
        LinkCheckResult linkCheck = stripFabricatedLinks(content, input.allowedLinks());
        String cleaned = linkCheck.cleanedContent();
        if (!linkCheck.strippedUrls().isEmpty()) {
            flags.add(FLAG_FABRICATED_LINK);
            details.add("stripped_links=" + linkCheck.strippedUrls());
            log.warn("Assistant fabricated markdown link(s) stripped (conversation {}): {}",
                    input.conversationId(), linkCheck.strippedUrls());
        }

        // 2. 菜单名校验：检查带箭头的菜单路径与常见幻觉菜单名
        List<String> unknownMenus = findUnknownMenus(cleaned);
        if (!unknownMenus.isEmpty()) {
            flags.add(FLAG_UNKNOWN_MENU);
            details.add("unknown_menus=" + unknownMenus);
            log.warn("Assistant referenced unknown menu(s) (conversation {}): {}",
                    input.conversationId(), unknownMenus);
        }

        // 3. 无依据报数据校验（仅执行模式、未降级/未阻断/支持工具调用）
        if (input.agentMode() && !input.degraded() && !input.blocked() && !input.toolsUnsupported()
                && input.successfulToolCalls() == 0
                && looksLikeReadDataQuestion(input.question())
                && looksLikeUngroundedDataClaim(cleaned)) {
            flags.add(FLAG_UNGROUNDED_DATA);
            details.add("question=" + abbreviate(input.question(), 60));
            notices.add(UNGROUNDED_DATA_NOTICE);
            log.warn("Assistant reported data without successful tool call (conversation {}): {}",
                    input.conversationId(), abbreviate(input.question(), 80));
        }

        // 4. 提前宣布完成 / 没调写工具却声称生成卡片
        if (input.agentMode() && !input.degraded() && !input.blocked()) {
            if (input.createdActionsCount() == 0 && AssistantService.claimsCardGenerated(cleaned)) {
                flags.add(FLAG_CARD_CLAIM);
                notices.add(AssistantService.CARD_CLAIM_NOTICE);
                log.warn("Assistant claimed an action card without calling a write tool (conversation {})",
                        input.conversationId());
            } else if (input.createdActionsCount() > 0 && claimsAlreadyExecuted(cleaned)) {
                flags.add(FLAG_PREMATURE_COMPLETION);
                notices.add(PREMATURE_COMPLETION_NOTICE);
                log.warn("Assistant claimed completion before card confirmation (conversation {})",
                        input.conversationId());
            }
        }

        String mergedNotice = notices.isEmpty() ? null : String.join(" ", notices);
        return new VerificationResult(cleaned, mergedNotice, List.copyOf(flags), List.copyOf(details));
    }

    LinkCheckResult stripFabricatedLinks(String content, Set<String> allowedLinks) {
        if (content == null || content.isEmpty() || !content.contains("](")) {
            return new LinkCheckResult(content == null ? "" : content, List.of());
        }
        Set<String> whitelist = allowedLinks != null ? allowedLinks : Set.of();
        Matcher matcher = MARKDOWN_LINK.matcher(content);
        StringBuilder sb = new StringBuilder();
        List<String> stripped = new ArrayList<>();
        while (matcher.find()) {
            String label = matcher.group(1);
            String url = matcher.group(2).trim();
            if (whitelist.contains(url)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                stripped.add(url);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(label));
            }
        }
        matcher.appendTail(sb);
        return new LinkCheckResult(sb.toString(), stripped);
    }

    List<String> findUnknownMenus(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Set<String> valid = capabilities.validMenuSegments();
        Set<String> unknown = new LinkedHashSet<>();

        checkMenuPathPattern(MENU_PATH_QUOTED, content, valid, unknown);
        checkMenuPathPattern(MENU_PATH_BOLD, content, valid, unknown);

        Matcher hallucinated = HALLUCINATED_MENU.matcher(content);
        while (hallucinated.find()) {
            String name = hallucinated.group(1).trim();
            if (!valid.contains(name)) {
                unknown.add(name);
            }
        }
        return new ArrayList<>(unknown);
    }

    private void checkMenuPathPattern(Pattern pattern, String content, Set<String> valid, Set<String> unknown) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String path = matcher.group(1);
            // 排除明显不是菜单路径的流程/诊断/报错表达（如「结论 → 依据 → 建议操作」「报错 → 原因 → 处理」）
            if (path.contains("结论") || path.contains("依据") || path.contains("原因")
                    || path.contains("处理") || path.contains("建议") || path.contains("步骤")) {
                continue;
            }
            String[] segments = path.split("[→›]");
            if (segments.length < 2) {
                continue;
            }
            for (String raw : segments) {
                String seg = normalizeMenuSegment(raw);
                if (seg.isEmpty()) {
                    continue;
                }
                if (!valid.contains(seg)) {
                    unknown.add(seg);
                }
            }
        }
    }

    private static String normalizeMenuSegment(String raw) {
        if (raw == null) {
            return "";
        }
        // 去掉括号内的补充说明，例如「模型网关（仅超管）」→「模型网关」
        String cleaned = raw.replaceAll("[（(][^）)]*[）)]", "").trim();
        return cleaned.replaceAll("\\s+", " ");
    }

    static boolean looksLikeReadDataQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        if (PURE_HOWTO_QUESTION.matcher(question).find() && !question.contains("我") && !question.contains("现在")) {
            return false;
        }
        return READ_DATA_QUESTION.matcher(question).find();
    }

    static boolean looksLikeUngroundedDataClaim(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        if (DISCLAIMER_PHRASE.matcher(content).find()) {
            return false;
        }
        return CONCRETE_DATA_CLAIM.matcher(content).find();
    }

    static boolean claimsAlreadyExecuted(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        // 按句拆分，避免另一句的「确认后才会」掩盖或误伤
        for (String sentence : content.split("[。！？!?\n]+")) {
            if (PREMATURE_EXECUTION.matcher(sentence).find()
                    && !NEGATION_OR_CONDITIONAL.matcher(sentence).find()) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public record VerificationInput(
            String conversationId,
            boolean agentMode,
            boolean degraded,
            boolean blocked,
            boolean toolsUnsupported,
            String question,
            String content,
            int successfulToolCalls,
            int createdActionsCount,
            Set<String> allowedLinks
    ) {
    }

    public record VerificationResult(
            String cleanedContent,
            String extraNotice,
            List<String> flags,
            List<String> details
    ) {
        public boolean hasFlags() {
            return flags != null && !flags.isEmpty();
        }
    }

    record LinkCheckResult(String cleanedContent, List<String> strippedUrls) {
    }
}
