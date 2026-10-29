package com.aliyun.autowonder.taskdata.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class TaskDataPageVO {
    private List<TaskDataRecordVO> items = new ArrayList<>();
    private Long nextAfterId;
}
