package com.aliyun.autowonder.squad.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class AddMembersRequest {
    private List<Long> agentIds;
}
