package com.aliyun.autowonder.workitem;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工单真人关注关系 DAO。关注/取消关注必须幂等：重复关注不新增行，取消不存在的关注返回 0。
 */
@Mapper
public interface WorkitemWatcherDao {

    /** 幂等写入关注关系：已存在时不做任何变更。 */
    void insertIgnore(WorkitemWatcherDO watcher);

    /** 幂等删除关注关系，返回受影响行数（0 表示本就未关注）。 */
    int delete(@Param("tenantId") Long tenantId,
               @Param("workitemId") Long workitemId,
               @Param("userId") Long userId);

    /** 列出某工单的全部关注人，按关注时间升序。 */
    List<WorkitemWatcherDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                           @Param("workitemId") Long workitemId);

    /** 查询单个用户对某工单的关注关系，未关注返回 null。 */
    WorkitemWatcherDO find(@Param("tenantId") Long tenantId,
                           @Param("workitemId") Long workitemId,
                           @Param("userId") Long userId);

    /** 列出某用户在工作空间内关注的全部工单，供列表页一次性回填关注态。 */
    List<WorkitemWatcherDO> listByUser(@Param("tenantId") Long tenantId,
                                       @Param("userId") Long userId);
}
