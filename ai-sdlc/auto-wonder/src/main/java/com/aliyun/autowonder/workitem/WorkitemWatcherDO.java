package com.aliyun.autowonder.workitem;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 工单真人关注关系。非负责人可订阅工单进展，独立于负责人/创建人/@/数字员工指派。
 */
@Getter
@Setter
public class WorkitemWatcherDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long userId;
    private Date gmtCreate;
}
