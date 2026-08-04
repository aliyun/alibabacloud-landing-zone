package com.aliyun.autowonder.squad.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateSquadRequest {
    private String name;
    private String description;
    private Long ownerId;
}
