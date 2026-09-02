package com.aliyun.autowonder.workitem;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

@Mapper
public interface WorkitemDao {
    List<WorkitemDO> listByIds(@Param("tenantId") Long tenantId, @Param("ids") Collection<Long> ids);
    void insert(WorkitemDO w);
    WorkitemDO findById(@Param("id") Long id);
    WorkitemDO findByIdForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);
    List<WorkitemDO> listByOrigin(@Param("tenantId") Long tenantId,
                                  @Param("originType") String originType,
                                  @Param("originId") Long originId);
    List<WorkitemDO> list(@Param("tenantId") Long tenantId,
            @Param("workType") String workType,
            @Param("statusNodeId") Long statusNodeId,
            @Param("statusCategory") String statusCategory,
            @Param("assigneeType") String assigneeType,
            @Param("assigneeRef") Long assigneeRef,
            @Param("pendingDecisionOnly") boolean pendingDecisionOnly,
            @Param("mineScope") String mineScope,
            @Param("currentUserId") Long currentUserId,
            @Param("keyword") String keyword, @Param("keywordId") Long keywordId,
            @Param("tag") String tag,
            @Param("offset") int offset, @Param("limit") int limit);
    long count(@Param("tenantId") Long tenantId,
            @Param("workType") String workType,
            @Param("statusNodeId") Long statusNodeId,
            @Param("statusCategory") String statusCategory,
            @Param("assigneeType") String assigneeType,
            @Param("assigneeRef") Long assigneeRef,
            @Param("pendingDecisionOnly") boolean pendingDecisionOnly,
            @Param("mineScope") String mineScope,
            @Param("currentUserId") Long currentUserId,
            @Param("keyword") String keyword, @Param("keywordId") Long keywordId,
            @Param("tag") String tag);
    int updateContent(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("title") String title, @Param("contentMd") String contentMd,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateExternalContent(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("title") String title, @Param("contentMd") String contentMd,
            @Param("priority") Integer priority,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("statusNodeId") Long statusNodeId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateTemplateAndStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("templateId") Long templateId, @Param("statusNodeId") Long statusNodeId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateAssignee(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("assigneeType") String assigneeType, @Param("assigneeRef") Long assigneeRef,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateAssignOperator(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("assignOperatorId") Long assignOperatorId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int countBySdlcId(@Param("sdlcId") Long sdlcId);
    List<Long> listIdsBySdlcId(@Param("sdlcId") Long sdlcId, @Param("limit") int limit);

    int countActiveByAssignee(@Param("assigneeType") String assigneeType,
                              @Param("assigneeRef") Long assigneeRef);
    int updateCurrentStep(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("currentStepId") Long currentStepId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateSdlcAndStep(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("sdlcId") Long sdlcId, @Param("currentStepId") Long currentStepId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);

    /** Due agent-assigned workitems whose scheduled_start_at has arrived, oldest first. */
    List<WorkitemDO> findScheduledDue(@Param("now") Date now, @Param("limit") int limit);
    /** CAS-clear a due scheduled_start_at; rows already cleared or updated return 0. */
    int clearScheduledStartAt(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("version") Integer version);
    /** CAS-clear a scheduled_start_at that actually fired, stamping scheduled_start_triggered_at. */
    int fireScheduledStartAt(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("version") Integer version);
    int updateScheduledStartAt(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("scheduledStartAt") Date scheduledStartAt,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateTags(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("tags") String tagsJson,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
}
