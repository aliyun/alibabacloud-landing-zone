package com.aliyun.autowonder.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiMessageDao {
    void insert(AiMessageDO message);

    List<AiMessageDO> listBySession(@Param("sessionId") Long sessionId);

    int maxSeq(@Param("sessionId") Long sessionId);
}
