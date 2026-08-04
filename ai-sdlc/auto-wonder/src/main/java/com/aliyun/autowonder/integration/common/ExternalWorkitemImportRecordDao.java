package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ExternalWorkitemImportRecordDao {
    void insert(ExternalWorkitemImportRecordDO record);

    List<ExternalWorkitemImportRecordDO> list(@Param("tenantId") Long tenantId,
                                              @Param("sourceSystem") String sourceSystem,
                                              @Param("externalWorkitemId") String externalWorkitemId,
                                              @Param("status") String status,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);
}
