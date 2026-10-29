package com.aliyun.autowonder.taskdata;

public class TaskDataConflictException extends RuntimeException {
    private final TaskDataRecordDO current;

    public TaskDataConflictException(String message, TaskDataRecordDO current) {
        super(message);
        this.current = current;
    }

    TaskDataRecordDO current() {
        return current;
    }
}
