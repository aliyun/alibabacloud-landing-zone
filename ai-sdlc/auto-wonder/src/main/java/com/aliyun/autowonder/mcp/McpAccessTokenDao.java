package com.aliyun.autowonder.mcp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface McpAccessTokenDao {
    void insert(McpAccessTokenDO token);

    McpAccessTokenDO findByHash(@Param("tokenHash") String tokenHash);

    McpAccessTokenDO findById(@Param("id") Long id, @Param("userId") Long userId);

    List<McpAccessTokenDO> listByUser(@Param("userId") Long userId);

    int revoke(@Param("id") Long id, @Param("userId") Long userId,
               @Param("revokedAt") Date revokedAt, @Param("modifierId") Long modifierId);

    int touchLastUsed(@Param("id") Long id, @Param("lastUsedAt") Date lastUsedAt);
}
