package com.aliyun.autowonder.squad.workflow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

@Mapper
public interface SquadWorkflowDao {
    int insert(SquadWorkflowDO row);

    SquadWorkflowDO findByKey(@Param("workspaceId") long workspaceId,
            @Param("squadId") long squadId,
            @Param("inputHash") String inputHash,
            @Param("analysisVersion") String analysisVersion);

    SquadWorkflowDO findById(@Param("workspaceId") long workspaceId, @Param("id") long id);

    SquadWorkflowDO lockById(@Param("workspaceId") long workspaceId, @Param("id") long id);

    SquadWorkflowDO findLatest(@Param("workspaceId") long workspaceId,
            @Param("squadId") long squadId);

    SquadWorkflowDO findByConversation(@Param("workspaceId") long workspaceId,
            @Param("conversationId") long conversationId);

    int update(SquadWorkflowDO row);

    int claimDispatch(@Param("workspaceId") long workspaceId, @Param("id") long id,
            @Param("attemptId") String attemptId);

    List<Long> lockActiveIds(@Param("workspaceId") long workspaceId,
            @Param("statuses") Collection<String> statuses,
            @Param("now") Date now);

    int countActive(@Param("workspaceId") long workspaceId,
            @Param("statuses") Collection<String> statuses,
            @Param("now") Date now);

    List<SquadWorkflowDO> listExpired(@Param("now") Date now, @Param("limit") int limit);

    List<SquadWorkflowDO> listUndispatched(@Param("cutoff") Date cutoff, @Param("limit") int limit);

    List<SquadWorkflowDO> listCleanup(@Param("failedCutoff") Date failedCutoff,
            @Param("successCutoff") Date successCutoff, @Param("limit") int limit);

    int deleteById(@Param("id") long id);
}
