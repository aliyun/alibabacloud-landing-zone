package com.aliyun.autowonder.workspace.guide;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkspaceGuideProgressDao {

    /** 空间创建登记：唯一键保证重复创建事件只留下一条记录，重复登记不报错。 */
    int insertIfAbsent(WorkspaceGuideProgressDO row);

    /** 只看当前用户在当前工作空间的记录；不匹配即视为没有资格。 */
    WorkspaceGuideProgressDO findByWorkspaceAndUser(@Param("workspaceId") Long workspaceId,
                                                    @Param("userId") Long userId);

    /** 版本守卫：并发标签页或迟到响应写入 0 行时由调用方转成版本冲突。 */
    int updateProgress(@Param("id") Long id,
                       @Param("expectedVersion") Integer expectedVersion,
                       @Param("status") String status,
                       @Param("stage") Integer stage,
                       @Param("substep") Integer substep,
                       @Param("conversationId") Long conversationId,
                       @Param("clientMessageId") String clientMessageId,
                       @Param("modifierId") Long modifierId);
}
