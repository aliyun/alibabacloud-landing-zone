package com.aliyun.autowonder.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentVersionDao {
    List<AgentVersionDO> listByIds(@Param("tenantId") Long tenantId, @Param("ids") Collection<Long> ids);
    void insert(AgentVersionDO v);
    AgentVersionDO findById(@Param("id") Long id);
    AgentVersionDO findByAgentAndNo(@Param("agentId") Long agentId, @Param("versionNo") Integer versionNo);
    List<AgentVersionDO> listByAgent(@Param("agentId") Long agentId);
    List<AgentVersionDO> listApprovedByAgent(@Param("agentId") Long agentId);
    int updateConfig(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("roleName") String roleName, @Param("roleCode") String roleCode,
            @Param("businessBackground") String businessBackground,
            @Param("responsibilities") String responsibilities,
            @Param("sdlcId") Long sdlcId,
            @Param("identityJson") String identityJson,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("status") String status,
            @Param("reviewerId") Long reviewerId, @Param("reviewComment") String reviewComment,
            @Param("identityJson") String identityJson,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDeleteByAgent(@Param("agentId") Long agentId, @Param("tenantId") Long tenantId,
            @Param("modifierId") Long modifierId);
}
