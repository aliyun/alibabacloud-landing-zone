package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryVO {
    private ByLifecycle byLifecycle = new ByLifecycle();
    private ByType byType = new ByType();

    @Getter
    @Setter
    public static class ByLifecycle {
        private int init;
        private int inProgress;
        private int done;
        private int canceled;
    }

    @Getter
    @Setter
    public static class ByType {
        private int req;
        private int task;
        private int bug;
    }
}
