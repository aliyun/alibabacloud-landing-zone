package com.aliyun.autowonder.artifact;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ArtifactShareRequestDao {
    void insert(ArtifactShareRequestDO request);
    ArtifactShareRequestDO find(@Param("tenantId") long tenantId, @Param("dispatchId") long dispatchId,
                                @Param("name") String name);
    ArtifactShareRequestDO lock(@Param("id") long id);
    ArtifactShareRequestDO findById(@Param("tenantId") long tenantId, @Param("workitemId") long workitemId,
                                   @Param("id") long id);
    List<Long> listPending(@Param("dispatchId") Long dispatchId);
    void finish(ArtifactShareRequestDO request);
    void retryLater(@Param("id") long id);
}
