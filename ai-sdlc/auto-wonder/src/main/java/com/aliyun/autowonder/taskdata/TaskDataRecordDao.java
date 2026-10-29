package com.aliyun.autowonder.taskdata;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskDataRecordDao {
    void insert(TaskDataRecordDO record);

    TaskDataRecordDO findByKey(@Param("workspaceId") Long workspaceId,
                               @Param("namespace") String namespace,
                               @Param("recordKey") String recordKey);

    List<TaskDataRecordDO> list(@Param("workspaceId") Long workspaceId,
                                @Param("namespace") String namespace,
                                @Param("afterId") Long afterId,
                                @Param("limit") int limit);

    int update(@Param("workspaceId") Long workspaceId,
               @Param("namespace") String namespace,
               @Param("recordKey") String recordKey,
               @Param("ownerWorkitemId") Long ownerWorkitemId,
               @Param("valueJson") String valueJson,
               @Param("expectedVersion") Integer expectedVersion,
               @Param("modifierId") Long modifierId);
}
