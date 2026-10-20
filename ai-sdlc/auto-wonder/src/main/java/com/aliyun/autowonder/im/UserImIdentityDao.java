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

    /**
     * 软删除该用户全部渠道工号（逻辑删除用户时调用）。幂等：is_deleted = 0 守卫使重复调用为 no-op。
     */
    int softDeleteAllByUser(@Param("userId") long userId, @Param("modifierId") long modifierId);
}
