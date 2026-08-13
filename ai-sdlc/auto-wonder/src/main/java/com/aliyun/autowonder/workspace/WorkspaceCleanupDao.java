package com.aliyun.autowonder.workspace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface WorkspaceCleanupDao {
    List<WorkspaceCleanupCandidate> listEligible(@Param("cutoff") Date cutoff,
                                                  @Param("limit") int limit);
}
