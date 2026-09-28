package com.example.agentplatform.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 助手评测用例（{@code resources/assistant/eval/cases.json}）。
 *
 * <p>问题与参数期望中可以使用占位符，运行时按当前环境的数据替换：
 * {@code {{agent}}} 一个运行中的智能体名称、{@code {{kb}}} 一个知识库名称、{@code {{system_agent}}} 一个系统公共智能体名称、
 * {@code {{agent_id}}} / {@code {{kb_id}}} 对应的 ID，{@code {{year}}} 当前年份。占位符无法解析（环境里没有对应数据）时该用例记为跳过。
 *
 * @param role  以哪种角色提问：SUPER_ADMIN / DEVELOPER / VIEWER（评测专用的虚拟身份，走真实的权限校验）
 * @param mode  AGENT（默认）/ CHAT
 * @param setup   在同一会话中先发出、不参与评分的前置问题（用于检查多轮上下文）
 * @param context 模拟的页面上下文（page / resourceType / resourceId），值中可以使用占位符
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantEvalCase(String id,
                                String category,
                                String role,
                                String mode,
                                List<String> setup,
                                String question,
                                Map<String, String> context,
                                Expect expect) {

    /**
     * 期望。所有列出的条件都要满足才算通过；工具名列表中的 {@code @write} 代表全部写工具。
     *
     * @param toolsAll          必须全部调用过
     * @param toolsAny          至少调用过其中一个
     * @param toolsNone         一个都不能调用
     * @param noTools           不能调用任何工具
     * @param args              某个工具的某次调用，其参数字段需匹配正则（不区分大小写）
     * @param cardsMin          至少生成的操作卡片数
     * @param cardsMax          至多生成的操作卡片数
     * @param answerContainsAll 回答必须包含全部
     * @param answerContainsAny 回答至少包含一个
     * @param answerNotContains 回答不能包含任何一个
     * @param answerNotMatches  回答不能匹配任何一个正则
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(List<String> toolsAll,
                         List<String> toolsAny,
                         List<String> toolsNone,
                         Boolean noTools,
                         List<ArgExpect> args,
                         Integer cardsMin,
                         Integer cardsMax,
                         List<String> answerContainsAll,
                         List<String> answerContainsAny,
                         List<String> answerNotContains,
                         List<String> answerNotMatches) {
    }

    /** @param field 参数字段，支持 a.b 形式的嵌套路径；字段是数组时任一元素匹配即可 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArgExpect(String tool, String field, String pattern) {
    }
}
