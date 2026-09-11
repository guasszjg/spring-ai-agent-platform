package com.example.agentplatform.security.audit;

import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.repository.OpenApiCallLogRepository;
import com.example.agentplatform.service.AuditRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionCleanupTaskTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private OpenApiCallLogRepository openApiCallLogRepository;

    @Mock
    private AuditRecorder auditRecorder;

    @Test
    @DisplayName("合规归档清理任务: 定时清理90天前过期日志")
    void testRetentionCleanup() {
        RetentionCleanupTask task = new RetentionCleanupTask(auditEventRepository, openApiCallLogRepository, auditRecorder);
        ReflectionTestUtils.setField(task, "auditDays", 90);
        ReflectionTestUtils.setField(task, "callLogDays", 90);

        when(auditEventRepository.deleteByOccurredAtBefore(any(LocalDateTime.class))).thenReturn(15L);
        when(openApiCallLogRepository.deleteByTsBefore(any(LocalDateTime.class))).thenReturn(42L);

        task.cleanupExpiredLogs();

        ArgumentCaptor<LocalDateTime> auditCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditEventRepository).deleteByOccurredAtBefore(auditCaptor.capture());
        LocalDateTime auditThreshold = auditCaptor.getValue();
        assertThat(auditThreshold).isBefore(LocalDateTime.now().minusDays(89));

        ArgumentCaptor<LocalDateTime> callCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(openApiCallLogRepository).deleteByTsBefore(callCaptor.capture());
        LocalDateTime callThreshold = callCaptor.getValue();
        assertThat(callThreshold).isBefore(LocalDateTime.now().minusDays(89));
    }
}
