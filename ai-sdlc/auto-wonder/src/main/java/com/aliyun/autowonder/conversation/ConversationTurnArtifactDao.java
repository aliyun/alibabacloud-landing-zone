package com.aliyun.autowonder.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationTurnArtifactDao {

    int insert(ConversationTurnArtifactDO row);

    List<ConversationTurnArtifactDO> listByTurn(@Param("tenantId") Long tenantId,
            @Param("turnId") Long turnId);

    List<ConversationTurnArtifactDO> listByConversation(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("limit") int limit,
            @Param("offset") int offset);
}
