package com.aliyun.autowonder.aiusage;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentConversationUsageDao {
    void upsert(AgentConversationUsageDO usage);

    List<AgentConversationUsageDO> listByWorkitemId(@Param("tenantId") long tenantId,
                                                    @Param("workitemId") long workitemId,
                                                    @Param("channel") String channel);
}
