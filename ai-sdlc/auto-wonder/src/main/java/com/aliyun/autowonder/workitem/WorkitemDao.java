package com.aliyun.autowonder.workitem;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface WorkitemDao {
    List<WorkitemDO> listByIds(@Param("tenantId") Long tenantId, @Param("ids") Collection<Long> ids);
    void insert(WorkitemDO w);
    WorkitemDO findById(@Param("id") Long id);
    WorkitemDO findByIdForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);
    List<WorkitemDO> list(@Param("tenantId") Long tenantId,
            @Param("workType") String workType,
            @Param("statusNodeId") Long statusNodeId,
            @Param("assigneeType") String assigneeType,
            @Param("assigneeRef") Long assigneeRef,
            @Param("pendingDecisionOnly") boolean pendingDecisionOnly,
            @Param("mineScope") String mineScope,
            @Param("currentUserId") Long currentUserId,
            @Param("keyword") String keyword, @Param("keywordId") Long keywordId,
            @Param("offset") int offset, @Param("limit") int limit);
    long count(@Param("tenantId") Long tenantId,
            @Param("workType") String workType,
            @Param("statusNodeId") Long statusNodeId,
            @Param("assigneeType") String assigneeType,
            @Param("assigneeRef") Long assigneeRef,
            @Param("pendingDecisionOnly") boolean pendingDecisionOnly,
            @Param("mineScope") String mineScope,
            @Param("currentUserId") Long currentUserId,
            @Param("keyword") String keyword, @Param("keywordId") Long keywordId);
    int updateContent(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("title") String title, @Param("contentMd") String contentMd,
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

    int countActiveByAssignee(@Param("assigneeType") String assigneeType,
                              @Param("assigneeRef") Long assigneeRef);
    int updateCurrentStep(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("currentStepId") Long currentStepId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateSdlcAndStep(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("sdlcId") Long sdlcId, @Param("currentStepId") Long currentStepId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
}
