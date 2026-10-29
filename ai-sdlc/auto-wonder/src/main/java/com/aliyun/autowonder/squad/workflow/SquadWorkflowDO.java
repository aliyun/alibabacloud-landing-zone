package com.aliyun.autowonder.squad.workflow;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * One row per (workspace, squad, inputHash, analysisVersion). The row is both the shared cache and
 * the state machine for the attempt currently allowed to write it.
 */
@Getter
@Setter
public class SquadWorkflowDO {
    private Long id;
    private Long workspaceId;
    private Long squadId;
    private String inputHash;
    private String analysisVersion;
    private String status;
    private String attemptId;
    private Long conversationId;
    private Long turnId;
    private Long requestedBy;
    private Long chiefVersionId;
    private String inputJson;
    private String graphJson;
    private String submissionReceiptsJson;
    private Integer submissionCount;
    private Integer repairCount;
    private Date deadlineAt;
    private String errorCode;
    private String errorDetail;
    private Date completedAt;
    private Integer rowVersion;
    private Date gmtCreate;
    private Date gmtModified;
}
