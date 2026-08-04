package com.aliyun.autowonder.squad;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class SquadMemberDO {
    private Long id;
    private Long tenantId;
    private Long squadId;
    private Long agentId;
    private Date gmtCreate;
}
