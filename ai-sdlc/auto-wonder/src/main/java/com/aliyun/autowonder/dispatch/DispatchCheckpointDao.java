package com.aliyun.autowonder.dispatch;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DispatchCheckpointDao {
    void insert(DispatchCheckpointDO checkpoint);

    DispatchCheckpointDO findByDispatchAndSeq(@Param("tenantId") Long tenantId,
                                               @Param("dispatchId") Long dispatchId,
                                               @Param("checkpointSeq") Long checkpointSeq);

    DispatchCheckpointDO findLatestByDispatch(@Param("tenantId") Long tenantId,
                                               @Param("dispatchId") Long dispatchId);

    List<DispatchCheckpointDO> listLatestByDispatch(@Param("tenantId") Long tenantId,
                                                     @Param("dispatchId") Long dispatchId,
                                                     @Param("limit") int limit);

    List<DispatchCheckpointDO> listObsolete(@Param("tenantId") Long tenantId,
                                             @Param("dispatchId") Long dispatchId,
                                             @Param("retain") int retain);

    int deleteById(@Param("tenantId") Long tenantId,
                   @Param("dispatchId") Long dispatchId,
                   @Param("id") Long id);
}
