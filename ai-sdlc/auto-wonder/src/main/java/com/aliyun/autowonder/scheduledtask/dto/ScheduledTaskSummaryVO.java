package com.aliyun.autowonder.scheduledtask.dto;
import lombok.Getter; import lombok.Setter;
@Getter @Setter public class ScheduledTaskSummaryVO { private long running; private long today; private long success30d; private long completed30d; private long attention; }
