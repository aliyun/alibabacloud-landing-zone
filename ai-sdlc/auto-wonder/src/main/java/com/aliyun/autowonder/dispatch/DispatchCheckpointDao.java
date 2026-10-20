package com.aliyun.autowonder.dispatch;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DispatchCheckpointDao {
    int releaseResolvedPins(@Param("tenantId") long tenantId, @Param("dispatchId") long dispatchId);
    int markGarbage(@Param("tenantId") long tenantId, @Param("dispatchId") long dispatchId, @Param("id") long id);
    DispatchCheckpointDO findGarbageById(@Param("id") long id);
    List<DispatchCheckpointDO> listGarbage(@Param("limit") int limit);
    List<DispatchCheckpointDO> listRetentionSubjects(@Param("retain") int retain, @Param("limit") int limit);

    int pinCompletion(@Param("tenantId") long tenantId, @Param("dispatchId") long dispatchId,
                      @Param("checkpointSeq") long checkpointSeq, @Param("resultId") String resultId);

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
