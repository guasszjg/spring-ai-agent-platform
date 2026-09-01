package com.example.agentplatform.config;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentDailyStat;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.repository.AgentDailyStatRepository;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AgentRepository agentRepository;
    private final AgentDailyStatRepository dailyStatRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository,
                           AgentRepository agentRepository,
                           AgentDailyStatRepository dailyStatRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.agentRepository = agentRepository;
        this.dailyStatRepository = dailyStatRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUsers();
        seedAgents();
        seedDailyStats();
    }

    private void seedUsers() {
        if (userRepository.count() > 0) {
            return;
        }

        AppUser admin = new AppUser();
        admin.setId("user-admin");
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setNickname("超级管理员");
        admin.setRole("System Admin");
        admin.setAvatar("https://api.dicebear.com/7.x/bottts/svg?seed=admin");
        userRepository.save(admin);

        AppUser developer = new AppUser();
        developer.setId("user-developer");
        developer.setUsername("developer");
        developer.setPassword(passwordEncoder.encode("dev123456"));
        developer.setNickname("智能体工程师 (developer)");
        developer.setRole("Agent Developer");
        developer.setAvatar("https://api.dicebear.com/7.x/bottts/svg?seed=developer");
        userRepository.save(developer);
    }

    private void seedAgents() {
        if (agentRepository.count() > 0) {
            return;
        }

        Agent a1 = new Agent();
        a1.setId("agent-001");
        a1.setName("分布式系统架构专家");
        a1.setCode("distributed_arch_expert");
        a1.setAvatar("🚀");
        a1.setCategory("代码研发");
        a1.setDescription("精通分布式高并发架构、微服务治理、核心中间件与高可用系统演进。");
        a1.setModelName("gpt-4o");
        a1.setSystemPrompt("你是一名顶级分布式与微服务架构专家。你的回答需提供严谨的技术方案、架构考量、可落地代码示例及故障排查思路。");
        a1.setTemperature(0.3);
        a1.setTopP(0.85);
        a1.setMaxTokens(4096);
        a1.setTags(List.of("分布式", "微服务", "高并发", "架构治理"));
        a1.setStatus(AgentStatus.RUNNING);
        a1.setCallCount(1582L);
        a1.setAvgResponseTimeMs(420.0);
        a1.setCreatedAt(LocalDateTime.now().minusDays(30));
        a1.setUpdatedAt(LocalDateTime.now().minusHours(3));

        Agent a2 = new Agent();
        a2.setId("agent-002");
        a2.setName("SQL & DB 调优大师");
        a2.setCode("sql_tuning_master");
        a2.setAvatar("⚡");
        a2.setCategory("数据分析");
        a2.setDescription("分析复杂 SQL 执行计划，提供索引优化、分库分表与性能诊断方案。");
        a2.setModelName("deepseek-chat");
        a2.setSystemPrompt("你是一名资深数据库管理员(DBA)和 SQL 调优大师。请对用户提供的 SQL 语句或业务场景进行深度分析，指出性能瓶颈，给出优化后的 SQL 及索引创建建议。");
        a2.setTemperature(0.2);
        a2.setTopP(0.8);
        a2.setMaxTokens(3000);
        a2.setTags(List.of("MySQL", "PostgreSQL", "SQL优化", "索引设计"));
        a2.setStatus(AgentStatus.RUNNING);
        a2.setCallCount(964L);
        a2.setAvgResponseTimeMs(280.0);
        a2.setCreatedAt(LocalDateTime.now().minusDays(25));
        a2.setUpdatedAt(LocalDateTime.now().minusHours(5));

        Agent a3 = new Agent();
        a3.setId("agent-003");
        a3.setName("企业知识库客服助理");
        a3.setCode("corp_knowledge_cs");
        a3.setAvatar("🤖");
        a3.setCategory("知识库客服");
        a3.setDescription("基于 RAG 检索增强技术，提供准确、温馨、专业的企业业务与售后问答服务。");
        a3.setModelName("gpt-4o-mini");
        a3.setSystemPrompt("你是企业的官方智能客服助理。态度温和友好，严格依据企业知识库与事实回答用户疑问。遇到未明确事宜，引导用户联系人工专线。");
        a3.setTemperature(0.5);
        a3.setTopP(0.9);
        a3.setMaxTokens(1500);
        a3.setTags(List.of("RAG问答", "知识库", "客户服务", "7x24h"));
        a3.setStatus(AgentStatus.RUNNING);
        a3.setCallCount(3420L);
        a3.setAvgResponseTimeMs(195.0);
        a3.setCreatedAt(LocalDateTime.now().minusDays(40));
        a3.setUpdatedAt(LocalDateTime.now().minusMinutes(45));

        Agent a4 = new Agent();
        a4.setId("agent-004");
        a4.setName("Kubernetes 运维守护者");
        a4.setCode("k8s_ops_guardian");
        a4.setAvatar("🛡️");
        a4.setCategory("运维架构");
        a4.setDescription("实时分析集群日志与监控告警，快速定位 Pod 崩溃、OOM 及网络故障。");
        a4.setModelName("claude-3-5-sonnet");
        a4.setSystemPrompt("你是一名精通 Kubernetes、Docker、Prometheus 和 Linux 内核的云原生运维专家。请根据用户提供的报错日志或 Pod 状态，快速给出 Root Cause 并提供修复指令。");
        a4.setTemperature(0.2);
        a4.setTopP(0.7);
        a4.setMaxTokens(3500);
        a4.setTags(List.of("K8s", "Docker", "DevOps", "故障排查"));
        a4.setStatus(AgentStatus.IDLE);
        a4.setCallCount(610L);
        a4.setAvgResponseTimeMs(510.0);
        a4.setCreatedAt(LocalDateTime.now().minusDays(18));
        a4.setUpdatedAt(LocalDateTime.now().minusDays(1));

        Agent a5 = new Agent();
        a5.setId("agent-005");
        a5.setName("AI 创意营销策划师");
        a5.setCode("creative_marketing_pro");
        a5.setAvatar("✨");
        a5.setCategory("内容创作");
        a5.setDescription("高效撰写小红书种草、短视频脚本、公关稿件及爆款营销策划文案。");
        a5.setModelName("gpt-4o");
        a5.setSystemPrompt("你是一名拥有10年经验的4A广告公司创意总监。请使用富有感染力、精准抓人眼球的语言为用户输出高质量文案，包含吸引人的标题、金句、痛点激发与转化引导。");
        a5.setTemperature(0.9);
        a5.setTopP(0.95);
        a5.setMaxTokens(3000);
        a5.setTags(List.of("文案营销", "爆款创作", "自媒体", "品牌策划"));
        a5.setStatus(AgentStatus.RUNNING);
        a5.setCallCount(1280L);
        a5.setAvgResponseTimeMs(360.0);
        a5.setCreatedAt(LocalDateTime.now().minusDays(15));
        a5.setUpdatedAt(LocalDateTime.now().minusHours(12));

        Agent a6 = new Agent();
        a6.setId("agent-006");
        a6.setName("现代前端与 UI/UX 专家");
        a6.setCode("frontend_uiux_expert");
        a6.setAvatar("🎨");
        a6.setCategory("代码研发");
        a6.setDescription("提供现代前端（Vue/React/TailwindCSS）组件开发规范与用户体验交互设计。");
        a6.setModelName("deepseek-coder");
        a6.setSystemPrompt("你是一名资深前端全栈工程师与 UI/UX 设计师。专注于构建视觉惊艳、交互流畅的现代化 Web 应用。请输出美观、规范且无外部冗余依赖的代码。");
        a6.setTemperature(0.4);
        a6.setTopP(0.85);
        a6.setMaxTokens(4000);
        a6.setTags(List.of("Vue3", "React", "TailwindCSS", "UI设计"));
        a6.setStatus(AgentStatus.DISABLED);
        a6.setCallCount(430L);
        a6.setAvgResponseTimeMs(410.0);
        a6.setCreatedAt(LocalDateTime.now().minusDays(10));
        a6.setUpdatedAt(LocalDateTime.now().minusDays(2));

        Agent a7 = new Agent();
        a7.setId("agent-007");
        a7.setName("产品总监 PRD 智能助手");
        a7.setCode("product_prd_creator");
        a7.setAvatar("📋");
        a7.setCategory("产品策划");
        a7.setDescription("快速拆解业务需求，梳理用户故事与业务流程，生成结构化的标准 PRD 文档。");
        a7.setModelName("gpt-4o");
        a7.setSystemPrompt("你是一名资深互联网产品总监。根据用户的业务想法，梳理核心目标、用户角色、用例流程图与功能清单，输出清晰规范的敏捷 PRD 文档。");
        a7.setTemperature(0.6);
        a7.setTopP(0.9);
        a7.setMaxTokens(3500);
        a7.setTags(List.of("PRD文档", "用户故事", "敏捷开发", "需求分析"));
        a7.setStatus(AgentStatus.RUNNING);
        a7.setCallCount(880L);
        a7.setAvgResponseTimeMs(380.0);
        a7.setCreatedAt(LocalDateTime.now().minusDays(8));
        a7.setUpdatedAt(LocalDateTime.now().minusHours(8));

        Agent a8 = new Agent();
        a8.setId("agent-008");
        a8.setName("专业学术与商务多语翻译官");
        a8.setCode("polyglot_translator");
        a8.setAvatar("🌐");
        a8.setCategory("内容创作");
        a8.setDescription("支持中英日法德等数十种语言互译，符合信达雅标准与行业专业术语规范。");
        a8.setModelName("gpt-4o-mini");
        a8.setSystemPrompt("你是一名资深联合国同传级翻译专家。翻译时注重上下文意境，遵循信达雅原则，提供自然流畅、符合母语习惯的译文，并在需要时提供词汇解析。");
        a8.setTemperature(0.3);
        a8.setTopP(0.85);
        a8.setMaxTokens(2500);
        a8.setTags(List.of("多语言", "学术翻译", "本地化", "信达雅"));
        a8.setStatus(AgentStatus.RUNNING);
        a8.setCallCount(2150L);
        a8.setAvgResponseTimeMs(220.0);
        a8.setCreatedAt(LocalDateTime.now().minusDays(5));
        a8.setUpdatedAt(LocalDateTime.now().minusHours(1));

        agentRepository.saveAll(List.of(a1, a2, a3, a4, a5, a6, a7, a8));
    }

    private void seedDailyStats() {
        if (dailyStatRepository.count() > 0) {
            return;
        }

        LocalDate today = LocalDate.now();
        int days = 30;
        List<AgentDailyStat> rows = new ArrayList<>();

        for (Agent agent : agentRepository.findAll()) {
            long totalCalls = agent.getCallCount() == null ? 0 : agent.getCallCount();
            if (totalCalls <= 0) {
                continue;
            }
            long avgLatency = Math.round(agent.getAvgResponseTimeMs() == null ? 300.0 : agent.getAvgResponseTimeMs());
            long base = totalCalls / days;
            long remainder = totalCalls % days;

            for (int i = 0; i < days; i++) {
                long calls = base + (i >= days - remainder ? 1 : 0);
                if (calls == 0) {
                    continue;
                }
                LocalDate date = today.minusDays(days - 1L - i);
                long tokens = calls * 2000L;
                long prompt = Math.round(tokens * 0.57);
                long completion = tokens - prompt;

                AgentDailyStat stat = new AgentDailyStat();
                stat.setId(agent.getId() + "-" + date);
                stat.setAgentId(agent.getId());
                stat.setStatDate(date);
                stat.setCallCount(calls);
                stat.setPromptTokens(prompt);
                stat.setCompletionTokens(completion);
                stat.setTotalLatencyMs(calls * avgLatency);
                stat.setSuccessCount(Math.round(calls * 0.994));
                rows.add(stat);
            }
        }

        if (!rows.isEmpty()) {
            dailyStatRepository.saveAll(rows);
        }
    }
}
