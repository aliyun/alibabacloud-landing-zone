package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.environment.AgentEnvironmentVariableSnapshotRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentEnvironmentVariableRefDao {
    int insert(AgentEnvironmentVariableRefDO ref);

    boolean exists(@Param("tenantId") Long tenantId,
                   @Param("agentVersionId") Long agentVersionId,
                   @Param("environmentVariableId") Long environmentVariableId);

    int delete(@Param("tenantId") Long tenantId,
               @Param("agentVersionId") Long agentVersionId,
               @Param("environmentVariableId") Long environmentVariableId);

    int deleteByVersion(@Param("tenantId") Long tenantId,
                        @Param("agentVersionId") Long agentVersionId);

    List<AgentEnvironmentVariableRefDO> listByVersion(
            @Param("tenantId") Long tenantId,
            @Param("agentVersionId") Long agentVersionId);

    List<AgentEnvironmentVariableSnapshotRow> listResolutionSnapshot(
            @Param("tenantId") Long tenantId,
            @Param("agentVersionId") Long agentVersionId);

    List<AgentEnvironmentVariableRefVO> listMetadataByVersion(
            @Param("tenantId") Long tenantId,
            @Param("agentVersionId") Long agentVersionId);

    int countInvalidByVersion(@Param("tenantId") Long tenantId,
                              @Param("agentVersionId") Long agentVersionId);
}
