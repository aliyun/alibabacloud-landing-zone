package com.aliyun.autowonder.audit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AuditLogDao {
    void insert(AuditLogDO log);

    List<AuditLogDO> search(@Param("tenantId") Long tenantId,
                            @Param("module") String module,
                            @Param("action") String action,
                            @Param("actorId") Long actorId,
                            @Param("targetType") String targetType,
                            @Param("targetId") Long targetId,
                            @Param("startTime") String startTime,
                            @Param("endTime") String endTime,
                            @Param("keyword") String keyword,
                            @Param("offset") int offset,
                            @Param("limit") int limit);

    int countSearch(@Param("tenantId") Long tenantId,
                    @Param("module") String module,
                    @Param("action") String action,
                    @Param("actorId") Long actorId,
                    @Param("targetType") String targetType,
                    @Param("targetId") Long targetId,
                    @Param("startTime") String startTime,
                    @Param("endTime") String endTime,
                    @Param("keyword") String keyword);
}
