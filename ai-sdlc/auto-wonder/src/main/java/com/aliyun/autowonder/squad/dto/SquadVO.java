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
    private Date gmtCreate;
    private List<Long> memberAgentIds;
    private int memberCount;
    private int roleCount;
    private int executorOnlineCount;
    private int executorTotalCount;
    private int sdlcCount;
}
