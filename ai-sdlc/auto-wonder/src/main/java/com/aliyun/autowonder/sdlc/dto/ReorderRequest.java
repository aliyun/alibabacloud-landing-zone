package com.aliyun.autowonder.sdlc.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class ReorderRequest {
    private List<Long> stepIds;
}
