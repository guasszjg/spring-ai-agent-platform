package com.example.agentplatform.security;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.config.ToolConfigSanitizer;
import com.example.agentplatform.model.*;
import com.example.agentplatform.rag.KnowledgeBaseProvider;
import com.example.agentplatform.rag.dto.CreateFaqRequest;
import com.example.agentplatform.rag.dto.CreateKnowledgeBaseRequest;
import com.example.agentplatform.rag.dto.UpdateFaqRequest;
import com.example.agentplatform.rag.dto.UpdateKnowledgeBaseRequest;
import com.example.agentplatform.repository.*;
import com.example.agentplatform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultiTenantSecurityRegressionTest {

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentDailyStatRepository dailyStatRepository;
    @Mock
    private AgentConversationService conversationService;
    @Mock
    private AgentToolSecretRepository toolSecretRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private KnowledgeDocumentRepository documentRepository;
    @Mock
    private KnowledgeFaqRepository faqRepository;
    @Mock
    private AgentTemplateRepository templateRepository;
    @Mock
    private ResourceGrantRepository resourceGrantRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LlmGatewayService gatewayService;
    @Mock
    private KnowledgeBaseProvider difyProvider;

    private ResourceAuthorizationService resourceAuthService;
    private AgentToolSecretService toolSecretService;
    private AgentService agentService;
    private KnowledgeBaseService knowledgeBaseService;
    private AgentTemplateService templateService;
    private AiChatService aiChatService;

    private final CurrentActor devA = new CurrentActor("dev-a-id", "dev_a", UserRole.DEVELOPER);
    private final CurrentActor devB = new CurrentActor("dev-b-id", "dev_b", UserRole.DEVELOPER);
    private final CurrentActor viewer = new CurrentActor("viewer-id", "viewer_user", UserRole.VIEWER);
    private final CurrentActor admin = new CurrentActor("admin-id", "admin_user", UserRole.SUPER_ADMIN);

    @BeforeEach
    void setUp() {
        SecretCrypto secretCrypto = new SecretCrypto("test-aes-master-key-32-bytes!!");

        resourceAuthService = new ResourceAuthorizationService(
                resourceGrantRepository,
                userRepository,
                agentRepository,
                knowledgeBaseRepository,
                templateRepository
        );

        toolSecretService = new AgentToolSecretService(
                toolSecretRepository,
                agentRepository,
                secretCrypto,
                resourceAuthService
        );

        agentService = new AgentService(
                agentRepository,
                dailyStatRepository,
                conversationService,
                new ToolConfigSanitizer(),
                toolSecretService,
                resourceAuthService,
                knowledgeBaseRepository,
                resourceGrantRepository,
                new OwnerNameResolver(userRepository)
        );

        lenient().when(difyProvider.getProviderType()).thenReturn("DIFY");
        knowledgeBaseService = new KnowledgeBaseService(
                knowledgeBaseRepository,
                documentRepository,
                faqRepository,
                resourceAuthService,
                resourceGrantRepository,
                List.of(difyProvider),
                new ObjectMapper(),
                new OwnerNameResolver(userRepository)
        );

        templateService = new AgentTemplateService(
                templateRepository,
                resourceAuthService,
                resourceGrantRepository,
                new OwnerNameResolver(userRepository)
        );

        aiChatService = new AiChatService(
                agentService,
                conversationService,
                gatewayService,
                null,
                null,
                null,
                toolSecretService,
                knowledgeBaseService,
                resourceAuthService,
                null,
                false
        );
    }

    @Test
    @DisplayName("两个开发者不能互相访问、修改、删除或克隆对方未授权的私有智能体")
    void developerCannotAccessOtherDeveloperPrivateAgent() {
        Agent agentA = new Agent();
        agentA.setId("agent-a");
        agentA.setName("Dev A 智能体");
        agentA.setOwnerId(devA.getUserId());
        agentA.setOwnerUsername(devA.getUsername());
        agentA.setIsSystem(false);

        when(agentRepository.findById("agent-a")).thenReturn(Optional.of(agentA));

        // 1. Dev B 尝试查看 Dev A 的私有智能体元数据被拒绝
        assertThatThrownBy(() -> agentService.getById("agent-a", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 2. Dev B 尝试修改 Dev A 的智能体配置被拒绝
        Agent updatePayload = new Agent();
        updatePayload.setName("恶意修改名称");
        assertThatThrownBy(() -> agentService.update("agent-a", updatePayload, devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 3. Dev B 尝试删除 Dev A 的智能体被拒绝
        assertThatThrownBy(() -> agentService.delete("agent-a", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 4. Dev B 尝试克隆 Dev A 的未授权私有智能体被拒绝
        assertThatThrownBy(() -> agentService.copyAgent("agent-a", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 验证数据库无任何变动
        verify(agentRepository, never()).save(any());
        verify(agentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("两个开发者不能互相配置、查看或清空对方智能体的工具密钥")
    void developerCannotAccessOtherDeveloperToolSecrets() {
        Agent agentA = new Agent();
        agentA.setId("agent-a");
        agentA.setOwnerId(devA.getUserId());
        agentA.setOwnerUsername(devA.getUsername());
        agentA.setIsSystem(false);

        when(agentRepository.findById("agent-a")).thenReturn(Optional.of(agentA));

        // Dev B 尝试为 Dev A 的智能体配置密钥被拒绝
        assertThatThrownBy(() -> toolSecretService.saveBochaApiKey("agent-a", "bocha-key", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // Dev B 无法探测 Dev A 的密钥配置状态（返回 false）
        assertThat(toolSecretService.isBochaConfiguredSpecific("agent-a", devB)).isFalse();

        // Dev B 尝试清除 Dev A 的密钥被拒绝
        assertThatThrownBy(() -> toolSecretService.clearBochaApiKey("agent-a", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        verify(toolSecretRepository, never()).save(any());
        verify(toolSecretRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("两个开发者不能互相访问、修改、上传文档或管理对方私有知识库及FAQ")
    void developerCannotAccessOtherDeveloperKnowledgeBase() {
        KnowledgeBase kbA = new KnowledgeBase();
        kbA.setId("kb-a");
        kbA.setName("Dev A 研发知识库");
        kbA.setOwnerId(devA.getUserId());
        kbA.setOwnerUsername(devA.getUsername());
        kbA.setIsSystem(false);

        when(knowledgeBaseRepository.findById("kb-a")).thenReturn(Optional.of(kbA));

        // 1. Dev B 查看知识库详情被拒绝
        assertThatThrownBy(() -> knowledgeBaseService.getKnowledgeBaseById("kb-a", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 2. Dev B 更新知识库配置被拒绝
        UpdateKnowledgeBaseRequest updateReq = new UpdateKnowledgeBaseRequest();
        updateReq.setName("Dev B 恶意篡改");
        assertThatThrownBy(() -> knowledgeBaseService.updateKnowledgeBase("kb-a", updateReq, devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 3. Dev B 删除知识库被拒绝
        assertThatThrownBy(() -> knowledgeBaseService.deleteKnowledgeBase("kb-a", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 4. Dev B 上传文档被拒绝
        MockMultipartFile file = new MockMultipartFile("files", "test.md", "text/markdown", "content".getBytes());
        assertThatThrownBy(() -> knowledgeBaseService.uploadDocuments("kb-a", List.of(file), devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 5. Dev B 创建 FAQ 被拒绝
        CreateFaqRequest faqReq = new CreateFaqRequest();
        faqReq.setQuestion("测试问题");
        faqReq.setAnswer("测试回答");
        assertThatThrownBy(() -> knowledgeBaseService.createFaq("kb-a", faqReq, devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 6. Dev B 修改 FAQ 被拒绝
        UpdateFaqRequest updateFaqReq = new UpdateFaqRequest();
        updateFaqReq.setAnswer("修改回答");
        assertThatThrownBy(() -> knowledgeBaseService.updateFaq("kb-a", "faq-1", updateFaqReq, devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 7. Dev B 删除 FAQ 被拒绝
        assertThatThrownBy(() -> knowledgeBaseService.deleteFaq("kb-a", "faq-1", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        verify(documentRepository, never()).save(any());
        verify(faqRepository, never()).save(any());
        verify(knowledgeBaseRepository, never()).delete(any());
    }

    @Test
    @DisplayName("两个开发者不能互相访问或修改对方私有场景模板")
    void developerCannotAccessOtherDeveloperTemplate() {
        AgentTemplate tplA = new AgentTemplate();
        tplA.setId("tpl-a");
        tplA.setName("Dev A 定制模板");
        tplA.setOwnerId(devA.getUserId());
        tplA.setOwnerUsername(devA.getUsername());
        tplA.setIsBuiltin(false);

        when(templateRepository.findById("tpl-a")).thenReturn(Optional.of(tplA));

        // 1. Dev B 查看模板被拒绝
        assertThatThrownBy(() -> templateService.getById("tpl-a", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 2. Dev B 修改模板被拒绝
        AgentTemplate updateTpl = new AgentTemplate();
        updateTpl.setName("篡改模板");
        assertThatThrownBy(() -> templateService.update("tpl-a", updateTpl, devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 3. Dev B 删除模板被拒绝
        assertThatThrownBy(() -> templateService.delete("tpl-a", devB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        verify(templateRepository, never()).delete(any());
    }

    @Test
    @DisplayName("只读观察员 (VIEWER) 严禁执行任何写入、创建、复制或修改操作")
    void viewerCannotPerformAnyWriteAction() {
        Agent agentA = new Agent();
        agentA.setId("agent-a");
        agentA.setOwnerId(devA.getUserId());
        agentA.setIsSystem(false);

        KnowledgeBase kbA = new KnowledgeBase();
        kbA.setId("kb-a");
        kbA.setOwnerId(devA.getUserId());
        kbA.setIsSystem(false);

        AgentTemplate tplA = new AgentTemplate();
        tplA.setId("tpl-a");
        tplA.setOwnerId(devA.getUserId());
        tplA.setIsBuiltin(false);

        // 1. 创建智能体拒绝
        assertThatThrownBy(() -> agentService.create(new Agent(), viewer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只读");

        // 2. 复制智能体拒绝
        when(agentRepository.findById("agent-a")).thenReturn(Optional.of(agentA));
        assertThatThrownBy(() -> agentService.copyAgent("agent-a", viewer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 3. 更新智能体拒绝
        assertThatThrownBy(() -> agentService.update("agent-a", new Agent(), viewer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 4. 删除智能体拒绝
        assertThatThrownBy(() -> agentService.delete("agent-a", viewer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 5. 保存工具密钥拒绝
        assertThatThrownBy(() -> toolSecretService.saveBochaApiKey("agent-a", "key", viewer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 6. 创建知识库拒绝
        CreateKnowledgeBaseRequest kbReq = new CreateKnowledgeBaseRequest();
        kbReq.setName("观察员知识库");
        assertThatThrownBy(() -> knowledgeBaseService.createKnowledgeBase(kbReq, viewer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 7. 保存 FAQ 图片拒绝
        MockMultipartFile img = new MockMultipartFile("file", "test.png", "image/png", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> knowledgeBaseService.saveFaqImage(img, viewer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");

        // 8. 创建模板拒绝
        AgentTemplate newTpl = new AgentTemplate();
        newTpl.setName("观察员模板");
        newTpl.setSystemPrompt("prompt");
        assertThatThrownBy(() -> templateService.create(newTpl, viewer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限不足");
    }

    @Test
    @DisplayName("复制智能体严禁携带任何工具密钥，且必须自动过滤未授权的知识库绑定")
    void clonedAgentNeverInheritsSecretsAndFiltersUnauthorizedKnowledgeBases() {
        Agent source = new Agent();
        source.setId("source-agent");
        source.setName("数据挖掘专家");
        source.setCode("data_mining");
        source.setOwnerId(devA.getUserId());
        source.setOwnerUsername(devA.getUsername());
        source.setIsSystem(false);
        source.setToolsConfig("{\"bocha\":{\"enabled\":true,\"apiKey\":\"sk-should-be-stripped\"}}");
        source.setKnowledgeBaseIds(List.of("kb-authorized", "kb-unauthorized"));
        source.setApiKey("sk-agent-old-private-key");
        source.setCallCount(500L);

        when(agentRepository.findById("source-agent")).thenReturn(Optional.of(source));

        // 模拟知识库存在性与授权状态：
        // kb-authorized: 存在，且 Dev A 拥有所有权（可使用）
        KnowledgeBase kbAuth = new KnowledgeBase();
        kbAuth.setId("kb-authorized");
        kbAuth.setIsSystem(false);
        kbAuth.setOwnerId(devA.getUserId());
        when(knowledgeBaseRepository.findById("kb-authorized")).thenReturn(Optional.of(kbAuth));

        // kb-unauthorized: 存在，由第三方拥有，且 Dev A 无权使用
        KnowledgeBase kbUnauth = new KnowledgeBase();
        kbUnauth.setId("kb-unauthorized");
        kbUnauth.setIsSystem(false);
        kbUnauth.setOwnerId("third-party-user");
        when(knowledgeBaseRepository.findById("kb-unauthorized")).thenReturn(Optional.of(kbUnauth));
        when(resourceGrantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                eq(ResourceAuthorizationService.TYPE_KNOWLEDGE_BASE), eq("kb-unauthorized"), eq(devA.getUserId()), eq(Set.of("USE"))
        )).thenReturn(false);

        when(agentRepository.existsByCode("data_mining_copy")).thenReturn(false);
        when(agentRepository.save(any(Agent.class))).thenAnswer(i -> {
            Agent a = i.getArgument(0);
            a.setId("cloned-agent-id");
            return a;
        });

        // Dev A 复制智能体生成新副本
        Agent cloned = agentService.copyAgent("source-agent", devA);

        assertThat(cloned).isNotNull();
        // 归属者正确变更为 Dev A
        assertThat(cloned.getOwnerId()).isEqualTo(devA.getUserId());
        assertThat(cloned.getOwnerUsername()).isEqualTo(devA.getUsername());
        // 密钥与 API Key 彻底清空
        assertThat(cloned.getApiKey()).isNull();
        assertThat(cloned.getCallCount()).isEqualTo(0L);
        // toolsConfig 中密钥被脱敏消除
        assertThat(cloned.getToolsConfig()).doesNotContain("sk-should-be-stripped");
        // 未授权的 kb-unauthorized 彻底剔除，仅保留有 USE 权限的 kb-authorized
        assertThat(cloned.getKnowledgeBaseIds()).containsExactly("kb-authorized");
        assertThat(cloned.getKnowledgeBaseIds()).doesNotContain("kb-unauthorized");

        // 严禁对克隆智能体写入任何密钥
        verify(toolSecretRepository, never()).save(any());
    }

    @Test
    @DisplayName("知识库授权撤销后，调用智能体聊天必须在执行前立即阻断，严禁数据库写入与外部网络调用")
    void revokedKnowledgeBaseBlocksChatExecutionWithZeroDbTurnsAndZeroExternalCalls() {
        Agent agentB = new Agent();
        agentB.setId("agent-b");
        agentB.setName("分析助手");
        agentB.setOwnerId(devB.getUserId());
        agentB.setOwnerUsername(devB.getUsername());
        agentB.setIsSystem(false);
        // 绑定了 Dev A 拥有的私有知识库 kb-a
        agentB.setKnowledgeBaseIds(List.of("kb-a"));

        KnowledgeBase kbA = new KnowledgeBase();
        kbA.setId("kb-a");
        kbA.setName("Dev A 内部知识库");
        kbA.setOwnerId(devA.getUserId());
        kbA.setIsSystem(false);
        kbA.setEnabled(true);

        when(agentRepository.findById("agent-b")).thenReturn(Optional.of(agentB));
        when(knowledgeBaseRepository.findById("kb-a")).thenReturn(Optional.of(kbA));

        // 模拟：Dev A 已经撤销了 Dev B 对 kb-a 的 USE 授权！
        when(resourceGrantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                eq(ResourceAuthorizationService.TYPE_KNOWLEDGE_BASE), eq("kb-a"), eq(devB.getUserId()), eq(Set.of("USE"))
        )).thenReturn(false);

        ChatRequest request = new ChatRequest();
        request.setAgentId("agent-b");
        request.setMessage("请问内部资料第四章内容是什么？");

        // 执行聊天前依赖检查立即抛错阻断
        CurrentActor.set(devB);
        try {
            assertThatThrownBy(() -> aiChatService.chat(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未获得使用授权或授权已被撤销");
        } finally {
            CurrentActor.clear();
        }

        // 重点断言：零数据库会话写入、零网关调用
        verify(conversationService, never()).appendTurn(any(), any(), any(), any(), any(), any(), anyLong(), anyInt());
        verify(gatewayService, never()).resolveRoute(any());
    }

    @Test
    @DisplayName("资源共享与授权撤销生命周期：RUN 权限允许执行但不能管理，撤销后立即禁止执行")
    void shareGrantAndRevocationLifecycle() {
        Agent agentA = new Agent();
        agentA.setId("agent-a");
        agentA.setOwnerId(devA.getUserId());
        agentA.setIsSystem(false);

        // 阶段 1：未授权时，Dev B 既不能管理也不能运行
        assertThat(resourceAuthService.canManageAgent(devB, agentA)).isFalse();
        assertThat(resourceAuthService.canRunAgent(devB, agentA)).isFalse();

        // 阶段 2：Dev A 授予 Dev B RUN 级别权限
        when(resourceGrantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                eq(ResourceAuthorizationService.TYPE_AGENT), eq("agent-a"), eq(devB.getUserId()), eq(Set.of("RUN"))
        )).thenReturn(true);

        // Dev B 现在可以运行，但依然不能管理修改该智能体
        assertThat(resourceAuthService.canRunAgent(devB, agentA)).isTrue();
        assertThat(resourceAuthService.canManageAgent(devB, agentA)).isFalse();

        // 阶段 3：Dev A 撤销授权
        when(resourceGrantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                eq(ResourceAuthorizationService.TYPE_AGENT), eq("agent-a"), eq(devB.getUserId()), eq(Set.of("RUN"))
        )).thenReturn(false);

        // 撤销后 Dev B 立即无法运行
        assertThat(resourceAuthService.canRunAgent(devB, agentA)).isFalse();
    }
}
