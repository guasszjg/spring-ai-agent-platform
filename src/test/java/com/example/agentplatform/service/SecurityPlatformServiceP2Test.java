package com.example.agentplatform.service;

import com.example.agentplatform.model.ClientCredential;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.repository.ClientCredentialRepository;
import com.example.agentplatform.repository.ExternalIdentityRepository;
import com.example.agentplatform.repository.GuardrailPolicyRepository;
import com.example.agentplatform.repository.OpenApiKeyRepository;
import com.example.agentplatform.security.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityPlatformServiceP2Test {

    @Mock
    private OpenApiKeyRepository keyRepository;
    @Mock
    private ClientCredentialRepository clientRepository;
    @Mock
    private GuardrailPolicyRepository policyRepository;
    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private ExternalIdentityRepository identityRepository;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private OwnerNameResolver ownerNameResolver;
    @Mock
    private GuardrailPolicyService guardrailPolicyService;

    private SecurityPlatformService service;

    @BeforeEach
    void setUp() {
        service = new SecurityPlatformService(
                keyRepository,
                clientRepository,
                policyRepository,
                auditEventRepository,
                identityRepository,
                auditRecorder,
                ownerNameResolver,
                guardrailPolicyService
        );
    }

    @Test
    void batchApprove_shouldApprovePendingClients() {
        CurrentActor actor = new CurrentActor("dev-1", "dev_user", UserRole.DEVELOPER);

        ClientCredential c1 = new ClientCredential();
        c1.setId("cli-1");
        c1.setOwnerId("dev-1");
        c1.setStatus("PENDING");
        c1.setClientId("DEV-001");

        when(clientRepository.findById("cli-1")).thenReturn(Optional.of(c1));

        Map<String, Object> res = service.batchApprove(List.of("cli-1"), actor);

        assertEquals(1, res.get("approvedCount"));
        assertEquals("ACTIVE", c1.getStatus());
        verify(clientRepository).save(c1);
    }

    @Test
    void exportClientsCsv_shouldIncludeUtf8BomAndHeaders() {
        CurrentActor actor = new CurrentActor("dev-1", "dev_user", UserRole.DEVELOPER);

        ClientCredential c = new ClientCredential();
        c.setId("cli-1");
        c.setOwnerId("dev-1");
        c.setClientType("SN");
        c.setClientId("SN12345");
        c.setLabel("Test Device");
        c.setStatus("ACTIVE");
        c.setRateLimitRpm(100);
        c.setDailyTokenQuota(50000L);

        when(clientRepository.findByOwnerIdOrderByUpdatedAtDesc("dev-1")).thenReturn(List.of(c));

        String csv = service.exportClientsCsv(actor);

        assertNotNull(csv);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("终端类型,终端标识,标签名称"));
        assertTrue(csv.contains("SN,SN12345,Test Device"));
        assertTrue(csv.contains("100,50000"));
    }

    @Test
    void importClientsCsv_shouldImportStandardCsv() {
        CurrentActor actor = new CurrentActor("dev-1", "dev_user", UserRole.DEVELOPER);

        when(clientRepository.save(any(ClientCredential.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String csv = "终端类型,终端标识,标签名称,授权智能体,限流RPM,每日Token配额\n" +
                "SN,SN-A001,车间机1,,120,50000\n" +
                "MAC,00:11:22:33:44:55,门禁机,,60,20000\n";

        Map<String, Object> result = service.importClientsCsv(csv, actor);

        assertEquals(2, result.get("total"));
        assertEquals(2, result.get("imported"));
        assertEquals(0, result.get("failed"));
        verify(clientRepository, times(2)).save(any(ClientCredential.class));
    }
}
