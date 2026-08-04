package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SquadLineVO {
    private Long squadId;
    private String name;
    private int members;
    private int online;
    private int busy;
    private int runningTasks;
    private int inProgressWorkitems;
    private double load;
}
