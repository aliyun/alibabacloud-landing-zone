package com.aliyun.autowonder.workspace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AccessRequestDao {
    void insert(AccessRequestDO request);

    AccessRequestDO findById(@Param("id") Long id);

    AccessRequestDO findPendingByTenantAndRequester(@Param("tenantId") Long tenantId,
                                                    @Param("requesterId") Long requesterId);

    List<AccessRequestDO> listByTenantAndStatus(@Param("tenantId") Long tenantId,
                                                @Param("status") String status);

    List<AccessRequestDO> listPendingByRequester(@Param("requesterId") Long requesterId);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("reviewerId") Long reviewerId,
                     @Param("rejectReason") String rejectReason);

    int deletePendingById(@Param("id") Long id);
}
