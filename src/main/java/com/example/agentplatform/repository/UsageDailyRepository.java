package com.example.agentplatform.repository;

import com.example.agentplatform.model.UsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UsageDailyRepository extends JpaRepository<UsageDaily, String> {

    Optional<UsageDaily> findByStatDateAndOwnerIdAndAgentIdAndApiKeyIdAndClientCredentialId(
            LocalDate statDate, String ownerId, String agentId, String apiKeyId, String clientCredentialId);

    List<UsageDaily> findByOwnerIdAndStatDateBetweenOrderByStatDateDesc(
            String ownerId, LocalDate from, LocalDate to);

    List<UsageDaily> findByStatDateBetweenOrderByStatDateDesc(
            LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(u.calls), 0) as totalCalls, " +
           "COALESCE(SUM(u.chatCalls), 0) as chatCalls, " +
           "COALESCE(SUM(u.messages), 0) as messages, " +
           "COALESCE(SUM(u.promptTokens), 0) as promptTokens, " +
           "COALESCE(SUM(u.completionTokens), 0) as completionTokens, " +
           "COALESCE(SUM(u.denied), 0) as denied, " +
           "COALESCE(SUM(u.errors), 0) as errors, " +
           "COALESCE(SUM(u.latencySumMs), 0) as latencySumMs " +
           "FROM UsageDaily u WHERE u.ownerId = :ownerId AND u.statDate BETWEEN :from AND :to")
    Object[] getAggregatedSummary(@Param("ownerId") String ownerId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(u.calls), 0) as totalCalls, " +
           "COALESCE(SUM(u.chatCalls), 0) as chatCalls, " +
           "COALESCE(SUM(u.messages), 0) as messages, " +
           "COALESCE(SUM(u.promptTokens), 0) as promptTokens, " +
           "COALESCE(SUM(u.completionTokens), 0) as completionTokens, " +
           "COALESCE(SUM(u.denied), 0) as denied, " +
           "COALESCE(SUM(u.errors), 0) as errors, " +
           "COALESCE(SUM(u.latencySumMs), 0) as latencySumMs " +
           "FROM UsageDaily u WHERE u.statDate BETWEEN :from AND :to")
    Object[] getAggregatedSummaryAll(@Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(u.promptTokens + u.completionTokens), 0) FROM UsageDaily u WHERE u.ownerId = :ownerId AND u.statDate = :today")
    long getTodayTokensByOwner(@Param("ownerId") String ownerId, @Param("today") LocalDate today);

    @Query("SELECT COALESCE(SUM(u.promptTokens + u.completionTokens), 0) FROM UsageDaily u WHERE u.clientCredentialId = :clientId AND u.statDate = :today")
    long getTodayTokensByClient(@Param("clientId") String clientId, @Param("today") LocalDate today);
}
