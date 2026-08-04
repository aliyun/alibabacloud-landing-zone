package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.insights.dto.InsightAuditItemVO;
import com.aliyun.autowonder.insights.dto.InsightWorkerVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface InsightsDao {

    long countTotalTokens(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countWorkitems(@Param("tenantId") long tenantId, @Param("since") Date since);

    int countCompletedWorkitems(@Param("tenantId") long tenantId, @Param("since") Date since);

    int countUsageWorkitems(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    Integer avgDispatchDurationMinutes(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countFirstPassDispatches(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countTotalDispatches(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countRetryDispatches(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countBlockedDispatches(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

    int countHighRiskAuditLogs(@Param("tenantId") long tenantId, @Param("since") Date since);

    int countTotalAuditLogs(@Param("tenantId") long tenantId, @Param("since") Date since);

    int countAuditBlocks(@Param("tenantId") long tenantId, @Param("since") Date since);

    List<Map<String, Object>> dailyTokenTrend(@Param("tenantId") long tenantId, @Param("since") Date since, @Param("agentId") Long agentId);

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
}
