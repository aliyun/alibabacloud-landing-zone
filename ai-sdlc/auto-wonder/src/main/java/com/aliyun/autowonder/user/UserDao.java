package com.aliyun.autowonder.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface UserDao {
    int insert(UserDO user);

    UserDO findByUsername(@Param("username") String username);

    UserDO findById(@Param("id") Long id);

    List<UserDO> listByIds(@Param("ids") Collection<Long> ids);

    Long findFirstActiveUserId();

    List<UserDO> searchOrgCandidates(@Param("tenantId") Long tenantId,
                                      @Param("keyword") String keyword,
                                      @Param("limit") int limit);
}
