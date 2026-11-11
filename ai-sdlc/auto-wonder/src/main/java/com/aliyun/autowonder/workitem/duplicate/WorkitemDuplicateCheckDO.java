package com.aliyun.autowonder.workitem.duplicate;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** 一次重复工单检测（草稿预检或保存后任务）的持久化记录。 */
@Getter
@Setter
public class WorkitemDuplicateCheckDO {
    private Long id;
    private Long tenantId;
    private Long operatorId;
    /** 保存后任务才有；草稿预检为 null。 */
    private Long workitemId;
    /** 前端表单会话标识，用于同一表单内请求去重；草稿预检才有。 */
    private String formRequestId;
    private String title;
    private String titleFingerprint;
    private Long excludeWorkitemId;
    /** 候选窗口起点，创建时固定，避免分批之间窗口漂移漏掉候选。 */
    private Date candidateSince;
    private Integer candidateTotal;
    /** 首次查询后持久化的完整候选快照 `[{"id":1,"title":".."}]`；非空后不再重查。 */
    private String candidateSnapshotJson;
    /** 历史分批游标；单次全量分析不再作为行为依据。 */
    private Integer nextBatchIndex;
    /** 创建时固定的稳定 Chief 调用标识；终态保留用于定位与取消。 */
    private String requestId;
    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED / SKIPPED */
    private String status;
    private String matchJson;
    private String error;
    /** 超时后待尽力取消模型会话；取消失败只记 ERROR，不等待 ACK。 */
    private Integer cancelPending;
    private Date deadlineAt;
    private Date leaseUntil;
    private Integer attempt;
    private Date gmtCreate;
    private Date gmtModified;
}
