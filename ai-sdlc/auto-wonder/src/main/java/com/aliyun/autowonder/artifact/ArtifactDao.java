package com.aliyun.autowonder.artifact;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ArtifactDao {
    void insert(ArtifactDO artifact);
    ArtifactDO findById(@Param("id") Long id);
    ArtifactDO findWorkitemByTenantAndId(@Param("tenantId") Long tenantId,
                                         @Param("id") Long id);
    ArtifactDO findBySourceAndId(@Param("tenantId") Long tenantId,
                                 @Param("sourceType") String sourceType,
                                 @Param("sourceId") Long sourceId,
                                 @Param("id") Long id);
    List<ArtifactDO> listBySource(@Param("tenantId") Long tenantId,
                                  @Param("sourceType") String sourceType,
                                  @Param("sourceId") Long sourceId,
                                  @Param("type") String type);
    List<ArtifactDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                    @Param("workitemId") Long workitemId);
    List<ArtifactDO> listByWorkitemAndType(@Param("tenantId") Long tenantId,
                                           @Param("workitemId") Long workitemId,
                                           @Param("type") String type);
    List<ArtifactDO> listByDispatch(@Param("tenantId") Long tenantId,
                                    @Param("dispatchId") Long dispatchId);
    int deleteById(@Param("tenantId") Long tenantId,
                   @Param("id") Long id);
    int deleteBySourceAndId(@Param("tenantId") Long tenantId,
                            @Param("sourceType") String sourceType,
                            @Param("sourceId") Long sourceId,
                            @Param("id") Long id);

    List<ArtifactDO> listUsageArtifacts(@Param("tenantId") Long tenantId,
                                        @Param("usageName") String usageName,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /**
     * 对外暴露标记只经 workitem 暴露入口写入（入口按 source_type='WORKITEM' 解析产物），
     * 因此以下分享查询无需 source_type 过滤，scheduled-run 产物不可能带标记匹配工单。
     * 快照 ref 只在首次暴露时写入（external_share_ref IS NULL 条件），重复暴露不覆盖。
     */
    int markExternalExposed(@Param("tenantId") Long tenantId,
                            @Param("workitemId") Long workitemId,
                            @Param("id") Long id,
                            @Param("externalShareRef") String externalShareRef);

    List<ArtifactDO> listExternalExposed(@Param("tenantId") Long tenantId,
                                         @Param("workitemId") Long workitemId);

    ArtifactDO findExternalExposed(@Param("tenantId") Long tenantId,
                                   @Param("workitemId") Long workitemId,
                                   @Param("id") Long id);

}
