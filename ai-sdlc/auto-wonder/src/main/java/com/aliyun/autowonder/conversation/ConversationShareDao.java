package com.aliyun.autowonder.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface ConversationShareDao {

    /**
     * uk_conversation_grantee 保证同一被分享人只有一行，重复分享走更新而不是插入，
     * 这样「撤销后再分享」不会撞上唯一键。
     *
     * <p>返回值不要拿去和 1 比：复活一条已撤销的分享走的是更新，MySQL 会报 2 行受影响。
     */
    int upsertReadShare(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("granteeUserId") Long granteeUserId,
            @Param("createdBy") Long createdBy);

    ConversationShareDO findActive(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("granteeUserId") Long granteeUserId);

    List<ConversationShareDO> listActive(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId);

    int revoke(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("granteeUserId") Long granteeUserId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("revokedAt") Date revokedAt);
}
