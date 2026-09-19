package com.aliyun.autowonder.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AgentRepoPermDao {
    void insert(AgentRepoPermDO perm);
    int update(AgentRepoPermDO perm);
    List<AgentRepoPermDO> listByVersion(@Param("agentVersionId") Long agentVersionId);
    AgentRepoPermDO findByVersionAndRepoForUpdate(@Param("agentVersionId") Long agentVersionId,
                                                  @Param("repoId") Long repoId,
                                                  @Param("tenantId") Long tenantId);
    int deleteByVersionAndRepo(@Param("agentVersionId") Long agentVersionId, @Param("repoId") Long repoId, @Param("tenantId") Long tenantId);
    int deleteByVersion(@Param("agentVersionId") Long agentVersionId);
    List<AgentRepoRefDO> listActiveRefsByRepoId(@Param("repoId") Long repoId, @Param("tenantId") Long tenantId);
}
