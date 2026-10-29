package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.insights.dto.InsightAuditItemVO;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightWorkerVO;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationDispatchRow;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationRawEventRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface InsightsDao {

    long countTotalTokens(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    BigDecimal countTotalCredits(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countWorkitems(@Param("tenantId") long tenantId, @Param("since") Date since);

    int countCompletedWorkitems(@Param("tenantId") long tenantId, @Param("since") Date since);

    int countUsageWorkitems(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countCreditUsageWorkitems(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    Integer avgDispatchDurationMinutes(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countFirstPassDispatches(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countTotalDispatches(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countRetryDispatches(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countBlockedDispatches(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countHighRiskAuditLogs(@Param("tenantId") long tenantId, @Param("since") Date since);

    int countTotalAuditLogs(@Param("tenantId") long tenantId, @Param("since") Date since);

    int countAuditBlocks(@Param("tenantId") long tenantId, @Param("since") Date since);

    List<Map<String, Object>> dailyTokenTrend(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    List<Map<String, Object>> dailyCreditsTrend(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    List<InsightMetricsVO.WorkerCost> listWorkerCredits(@Param("tenantId") long tenantId,
                                                        @Param("since") Date since,
                                                        @Param("agentId") Long agentId);

    List<InsightMetricsVO.CreditSource> listCreditsBySource(@Param("tenantId") long tenantId,
                                                            @Param("since") Date since,
                                                            @Param("agentId") Long agentId);

    List<InsightAuditItemVO> listAuditItems(@Param("tenantId") long tenantId,
                                            @Param("riskLevel") String riskLevel,
                                            @Param("workerId") Long workerId,
                                            @Param("timeRange") String timeRange,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    int countAuditItems(@Param("tenantId") long tenantId,
                        @Param("riskLevel") String riskLevel,
                        @Param("workerId") Long workerId,
                        @Param("timeRange") String timeRange);

    List<InsightWorkerVO> listActiveWorkers(@Param("tenantId") long tenantId);

    List<HumanAgentParticipationRawEventRow> listParticipationLifecycleEvents(
            @Param("tenantId") long tenantId, @Param("cutoffExclusive") Date cutoffExclusive,
            @Param("offset") int offset, @Param("limit") int limit);

    List<Long> listWorkitemIdsCompletedBetween(@Param("tenantId") long tenantId,
                                               @Param("startInclusive") Date startInclusive,
                                               @Param("endExclusive") Date endExclusive);

    List<HumanAgentParticipationRawEventRow> listParticipationLifecycleEventsByWorkitemIds(
            @Param("tenantId") long tenantId, @Param("workitemIds") List<Long> workitemIds,
            @Param("cutoffExclusive") Date cutoffExclusive);

    List<HumanAgentParticipationDispatchRow> listParticipationDispatchStarts(
            @Param("tenantId") long tenantId, @Param("workitemIds") List<Long> workitemIds,
            @Param("cutoffExclusive") Date cutoffExclusive);
}
