package com.aliyun.autowonder.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface ClarificationAttachmentDao {

    int insert(ClarificationAttachmentDO row);

    ClarificationAttachmentDO findById(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("id") Long id);

    List<ClarificationAttachmentDO> listByIds(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("ids") List<Long> ids);

    List<ClarificationAttachmentDO> listByTurnIds(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("turnIds") List<Long> turnIds);

    int bindTurn(@Param("tenantId") Long tenantId, @Param("conversationId") Long conversationId,
            @Param("uploaderUserId") Long uploaderUserId, @Param("ids") List<Long> ids,
            @Param("turnId") Long turnId);

    int markDeleting(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("uploaderUserId") Long uploaderUserId, @Param("id") Long id);

    int markOrphanDeleting(@Param("tenantId") Long tenantId, @Param("id") Long id);

    int deleteClaimed(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("id") Long id);

    List<ClarificationAttachmentDO> listOrphans(@Param("cutoff") Date cutoff,
            @Param("limit") int limit);

    List<ClarificationAttachmentDO> listByWorkitem(@Param("tenantId") Long tenantId,
            @Param("workitemId") Long workitemId);

    int deleteById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
