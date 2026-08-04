package com.aliyun.autowonder.integration.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class AoneSyncResult {
    private int imported;
    private int updated;
    private int commentsImported;
    private List<Long> workitemIds = new ArrayList<>();
}
