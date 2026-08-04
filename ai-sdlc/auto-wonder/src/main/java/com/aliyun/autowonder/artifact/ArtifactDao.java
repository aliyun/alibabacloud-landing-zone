package com.aliyun.autowonder.artifact;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ArtifactDao {
    void insert(ArtifactDO artifact);
    ArtifactDO findById(@Param("id") Long id);
    List<ArtifactDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                    @Param("workitemId") Long workitemId);
    List<ArtifactDO> listByWorkitemAndType(@Param("tenantId") Long tenantId,
                                           @Param("workitemId") Long workitemId,
                                           @Param("type") String type);
    List<ArtifactDO> listByDispatch(@Param("tenantId") Long tenantId,
                                    @Param("dispatchId") Long dispatchId);
    int deleteById(@Param("tenantId") Long tenantId,
                   @Param("id") Long id);

    List<ArtifactDO> listUsageArtifacts(@Param("tenantId") Long tenantId,
                                        @Param("usageName") String usageName,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);
}
