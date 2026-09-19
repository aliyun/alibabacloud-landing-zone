package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;

/** 平台数字人 Chief of Staff 的执行器配置/在线状态快照,供前端对管理员做醒目提醒。 */
@Getter
@Setter
public class PlatformAgentStatusVO {

    /** 平台数字人已创建且至少有一个执行器在线。 */
    public static final String STATE_OK = "OK";
    /** 平台数字人已创建执行器,但没有任何执行器在线。 */
    public static final String STATE_OFFLINE = "OFFLINE";
    /** 平台数字人不存在或从未配置执行器。 */
    public static final String STATE_NOT_CONFIGURED = "NOT_CONFIGURED";

    private String state;
    private Long agentId;
    private Integer executorCount;
    private Integer onlineExecutorCount;
}
