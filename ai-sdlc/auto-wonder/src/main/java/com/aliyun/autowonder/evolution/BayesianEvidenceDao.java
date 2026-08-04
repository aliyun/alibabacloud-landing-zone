package com.aliyun.autowonder.evolution;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BayesianEvidenceDao {
    void insert(BayesianEvidenceDO evidence);

    BayesianEvidenceDO findLatest(@Param("tenantId") Long tenantId,
                                  @Param("assetType") String assetType,
                                  @Param("assetId") Long assetId,
                                  @Param("posteriorType") String posteriorType,
                                  @Param("contextKey") String contextKey);

    BayesianEvidenceDO findByIdempotencyKey(@Param("tenantId") Long tenantId,
                                            @Param("idempotencyKey") String idempotencyKey);

    List<BayesianEvidenceDO> listRecentByAsset(@Param("tenantId") Long tenantId,
                                               @Param("assetType") String assetType,
                                               @Param("assetId") Long assetId,
                                               @Param("posteriorType") String posteriorType,
                                               @Param("limit") int limit);

    List<BayesianEvidenceDO> listRecent(@Param("tenantId") Long tenantId,
                                        @Param("limit") int limit);

	List<BayesianEvidenceDO> listRecentCohortSamples(@Param("tenantId") Long tenantId,
												@Param("posteriorType") String posteriorType,
												@Param("contextKey") String contextKey,
												@Param("limit") int limit);
}
