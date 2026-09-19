package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Outcome of 一键全量更新: how many executors were scheduled and which were skipped, with reasons. */
@Getter
@Setter
public class ExecutorUpdateAllResultVO {
    private String targetVersion;
    private int total;
    private int scheduled;
    private int alreadyUpToDate;
    private List<ExecutorUpdateSkipVO> skipped = new ArrayList<>();
}
