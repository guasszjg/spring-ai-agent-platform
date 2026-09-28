package com.example.agentplatform.repository;

import com.example.agentplatform.model.AssistantEvalRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AssistantEvalRunRepository extends JpaRepository<AssistantEvalRun, String> {

    List<AssistantEvalRun> findTop20ByOrderByStartedAtDesc();

    boolean existsByStatus(String status);

    /** 服务重启会中断正在运行的评测：启动时把遗留的 RUNNING 标记为失败 */
    @Transactional
    @Modifying
    @Query(value = "UPDATE assistant_eval_runs SET status = 'FAILED', error = '服务重启，评测被中断', finished_at = NOW() WHERE status = 'RUNNING'",
            nativeQuery = true)
    int failInterrupted();
}
