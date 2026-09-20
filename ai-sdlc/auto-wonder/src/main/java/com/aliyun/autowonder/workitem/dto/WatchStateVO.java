package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

/** 当前用户对某工单的关注状态，供详情页与列表页统一回填。 */
@Getter
@Setter
public class WatchStateVO {
    private Long workitemId;
    /** 当前用户是否已关注该工单。 */
    private boolean watched;
    /** 该工单当前有效关注人数量。 */
    private int watcherCount;

    public WatchStateVO() {
    }

    public WatchStateVO(Long workitemId, boolean watched, int watcherCount) {
        this.workitemId = workitemId;
        this.watched = watched;
        this.watcherCount = watcherCount;
    }
}
