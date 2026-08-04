package com.aliyun.autowonder.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AgentSkillDao {
    void insert(AgentSkillDO skill);
    List<AgentSkillDO> listByVersion(@Param("agentVersionId") Long agentVersionId);
    int deleteByVersionAndSkill(@Param("agentVersionId") Long agentVersionId, @Param("skillId") Long skillId, @Param("tenantId") Long tenantId);
    int deleteByVersion(@Param("agentVersionId") Long agentVersionId);
    int countBySkillId(@Param("skillId") Long skillId, @Param("tenantId") Long tenantId);
}
