package com.aliyun.autowonder.agent;

import lombok.Getter;
import lombok.Setter;

/**
 * 仓库的有效引用来源：只有未删除数字人的当前在线版本或当前编辑版本才算有效引用。
 */
@Getter
@Setter
public class AgentRepoRefDO {
    public static final String TYPE_ONLINE = "ONLINE";
    public static final String TYPE_EDITING = "EDITING";

    private Long agentId;
    private String agentName;
    private Long agentVersionId;
    private Integer versionNo;
    private String refType;
}
