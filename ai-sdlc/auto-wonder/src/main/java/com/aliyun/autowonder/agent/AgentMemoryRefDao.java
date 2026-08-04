package com.aliyun.autowonder.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AgentMemoryRefDao {
    void insert(AgentMemoryRefDO ref);
    List<AgentMemoryRefDO> listByVersion(@Param("agentVersionId") Long agentVersionId);
    boolean existsByVersionAndMemory(@Param("agentVersionId") Long agentVersionId,
                                     @Param("memoryId") Long memoryId,
                                     @Param("tenantId") Long tenantId);
    int deleteByVersionAndMemory(@Param("agentVersionId") Long agentVersionId, @Param("memoryId") Long memoryId, @Param("tenantId") Long tenantId);
    int deleteByVersion(@Param("agentVersionId") Long agentVersionId);
    int countByMemoryId(@Param("memoryId") Long memoryId, @Param("tenantId") Long tenantId);
}
