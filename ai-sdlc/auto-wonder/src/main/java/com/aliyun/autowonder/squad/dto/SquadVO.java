package com.aliyun.autowonder.squad.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class SquadVO {
    private Long id;
    private String name;
    private String description;
    private Long ownerId;
    private Integer version;
    private boolean debugLogEnabled;
    private Date gmtCreate;
    private List<Long> memberAgentIds;
    private int memberCount;
    private int roleCount;
    private int executorOnlineCount;
    private int executorTotalCount;
    private int sdlcCount;
    /** Populated by the detail lookup only; null in list results, like memberAgentIds. */
    private List<SdlcSummaryVO> sdlcs;
    private List<ExecutorSummaryVO> executors;

    @Getter
    @Setter
    public static class SdlcSummaryVO {
        private Long id;
        private String name;
        private String workType;
        private String status;
    }

    @Getter
    @Setter
    public static class ExecutorSummaryVO {
        private Long id;
        private Long agentId;
        private String agentName;
        private String name;
        private String status;
        private String clientKind;
        private Date lastHeartbeat;
    }
}
