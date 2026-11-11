package com.aliyun.autowonder.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkspaceCreationRequestDao {

    /** 并发首写撞唯一键时抛 DuplicateKeyException，由调用方按幂等处理。 */
    int insertPending(@Param("userId") Long userId);

    WorkspaceCreationRequestDO findPendingByUser(@Param("userId") Long userId);

    /** 结束待处理申请：只有仍处于待处理（pending_marker = 1）的记录会被更新。 */
    int fulfillPendingByUser(@Param("userId") Long userId);
}
