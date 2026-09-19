package com.aliyun.autowonder.executor;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ExecutorDao {
    List<ExecutorDO> listByIds(@Param("tenantId") Long tenantId, @Param("ids") Collection<Long> ids);
    void insert(ExecutorDO executor);
    ExecutorDO findById(@Param("id") Long id);
    List<ExecutorDO> listAll(@Param("tenantId") Long tenantId, @Param("squadIds") Collection<Long> squadIds);
    List<ExecutorDO> listByAgent(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId);
    List<ExecutorDO> listByAgentIds(@Param("tenantId") Long tenantId, @Param("agentIds") Collection<Long> agentIds);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("modifierId") Long modifierId);
    int updateTokenRef(@Param("id") Long id, @Param("tokenRef") String tokenRef);
    int updateLastConnectIp(@Param("id") Long id, @Param("tenantId") Long tenantId,
                            @Param("lastConnectIp") String lastConnectIp, @Param("modifierId") Long modifierId);
    int updateLastStartedAt(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("startedAt") java.util.Date startedAt);
    int updateLastHeartbeat(@Param("id") Long id, @Param("tenantId") Long tenantId);
    int updateLaunchConfig(@Param("id") Long id, @Param("tenantId") Long tenantId,
                           @Param("launchConfig") String launchConfig,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("modifierId") Long modifierId);
    List<ExecutorDO> listByClientKind(@Param("clientKind") String clientKind);
    /**
     * Scan source for automatic upgrades: identity columns only. The reported version is presence
     * state in Redis ({@code exec:version:{id}}), never a column, so it is read per executor instead.
     */
    List<ExecutorDO> listForVersionScan();
}
