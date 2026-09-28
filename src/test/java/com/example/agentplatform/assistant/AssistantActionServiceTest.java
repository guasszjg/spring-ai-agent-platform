package com.example.agentplatform.assistant;

import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.repository.AssistantActionRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantActionServiceTest {

    @Mock AssistantActionRepository actionRepository;
    @Mock AssistantWriteTools writeTools;
    @Mock AuditRecorder auditRecorder;

    private final CurrentActor dev = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
    private AssistantActionService service;
    private AssistantAction action;

    @BeforeEach
    void setUp() {
        service = new AssistantActionService(actionRepository, writeTools, auditRecorder);
        action = new AssistantAction();
        action.setId("act-1");
        action.setUserId("u-dev");
        action.setConversationId("asc-1");
        action.setToolName("create_agent");
        action.setRiskLevel("W1");
        action.setTitle("创建智能体：售后客服");
        action.setPayload("{\"name\":\"售后客服\"}");
        action.setStatus(AssistantAction.PENDING);
        action.setResourceType("AGENT");
        action.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(actionRepository.findByIdAndUserId("act-1", "u-dev")).thenReturn(Optional.of(action));
        when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void confirmExecutesOnceRecordsResultAndAudits() {
        when(actionRepository.claim(eq("act-1"), eq("u-dev"), any())).thenReturn(1);
        when(writeTools.execute("create_agent", "{\"name\":\"售后客服\"}", dev)).thenReturn(
                new AssistantWriteTools.ExecutionResult("AGENT", "agent-9", "已创建智能体「售后客服」", "去调试", "/debug/agent-9"));

        Map<String, Object> card = service.confirm("act-1", dev);

        assertEquals(AssistantAction.EXECUTED, card.get("status"));
        assertEquals("agent-9", action.getResourceId());
        assertTrue(action.getResult().contains("/debug/agent-9"));
        verify(auditRecorder).record(eq("assistant.action.create_agent"), eq("AGENT"), eq("agent-9"), eq("SUCCESS"),
                isNull(), eq("LOW"), contains("via=assistant"));
    }

    @Test
    void secondConfirmDoesNotExecuteAgain() {
        when(actionRepository.claim(eq("act-1"), eq("u-dev"), any())).thenReturn(0);

        service.confirm("act-1", dev);

        verify(writeTools, never()).execute(anyString(), anyString(), any());
    }

    @Test
    void expiredActionIsNotExecuted() {
        action.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(actionRepository.claim(eq("act-1"), eq("u-dev"), any())).thenReturn(0);

        Map<String, Object> card = service.confirm("act-1", dev);

        assertEquals(AssistantAction.EXPIRED, card.get("status"));
        verify(writeTools, never()).execute(anyString(), anyString(), any());
    }

    @Test
    void failedExecutionIsRecordedWithReason() {
        when(actionRepository.claim(eq("act-1"), eq("u-dev"), any())).thenReturn(1);
        when(writeTools.execute(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("权限不足：无权绑定未获得 USE 授权的知识库"));

        Map<String, Object> card = service.confirm("act-1", dev);

        assertEquals(AssistantAction.FAILED, card.get("status"));
        assertTrue(action.getResult().contains("USE 授权"));
        verify(auditRecorder).record(anyString(), anyString(), anyString(), eq("FAILED"), eq("execution_failed"), anyString(), anyString());
    }

    @Test
    void otherUsersActionIsTreatedAsMissing() {
        CurrentActor other = new CurrentActor("u-other", "other", UserRole.DEVELOPER);
        when(actionRepository.findByIdAndUserId("act-1", "u-other")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.confirm("act-1", other));
        assertThrows(IllegalArgumentException.class, () -> service.cancel("act-1", other));
        verify(writeTools, never()).execute(anyString(), anyString(), any());
    }

    @Test
    void auditFailureDoesNotLoseExecutionResult() {
        when(actionRepository.claim(eq("act-1"), eq("u-dev"), any())).thenReturn(1);
        when(writeTools.execute(anyString(), anyString(), any())).thenReturn(
                new AssistantWriteTools.ExecutionResult("AGENT", "agent-9", "ok", null, null));
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(auditRecorder)
                .record(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString());

        Map<String, Object> card = service.confirm("act-1", dev);

        assertEquals(AssistantAction.EXECUTED, card.get("status"));
    }

    @Test
    void cardIsSerializableByPlainObjectMapperUsedForSse() throws Exception {
        action.setPreview("{\"fields\":[{\"label\":\"名称\",\"value\":\"售后客服\"}]}");
        action.setCreatedAt(LocalDateTime.now());

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(service.card(action));

        assertTrue(json.contains("\"expiresAt\":\"20"));
        assertTrue(json.contains("售后客服"));
    }

    @Test
    void statusNotesTellTheModelWhatHappened() {
        AssistantAction done = new AssistantAction();
        done.setTitle("创建智能体：售后客服");
        done.setStatus(AssistantAction.EXECUTED);
        done.setResourceId("agent-9");
        done.setExpiresAt(LocalDateTime.now());
        AssistantAction failed = new AssistantAction();
        failed.setTitle("绑定知识库：售后客服 ← 薪资制度");
        failed.setStatus(AssistantAction.FAILED);
        failed.setResult("{\"error\":\"没有使用权限\"}");
        failed.setExpiresAt(LocalDateTime.now());
        when(actionRepository.findByConversationIdOrderByCreatedAtAsc("asc-1")).thenReturn(List.of(done, failed));

        List<String> notes = service.statusNotes("asc-1");

        assertTrue(notes.get(0).contains("执行成功") && notes.get(0).contains("agent-9"));
        assertTrue(notes.get(1).contains("执行失败") && notes.get(1).contains("没有使用权限"));
    }
}
