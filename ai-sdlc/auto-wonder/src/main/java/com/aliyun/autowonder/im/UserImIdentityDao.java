package com.aliyun.autowonder.im;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserImIdentityDao {
    List<UserImIdentityDO> listByUserId(@Param("userId") long userId);

    UserImIdentityDO find(@Param("userId") long userId, @Param("provider") String provider);

    int upsert(UserImIdentityDO identity);

    int softDelete(@Param("userId") long userId,
                   @Param("provider") String provider,
                   @Param("modifierId") long modifierId);
}
