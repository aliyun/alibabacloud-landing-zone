package com.aliyun.autowonder.squad.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class CopySquadResultVO {

    private String requestId;
    private List<CopyTargetResultVO> results = new ArrayList<>();
}
