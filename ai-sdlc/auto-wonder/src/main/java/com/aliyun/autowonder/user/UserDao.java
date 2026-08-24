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

    List<UserDO> searchWorkspaceCandidates(@Param("tenantId") Long tenantId,
                                      @Param("keyword") String keyword,
                                      @Param("limit") int limit);

    int updatePasswordHash(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    int updateDeactivation(@Param("id") Long id,
                           @Param("deactivatedAt") java.util.Date deactivatedAt,
                           @Param("coolingOffExpiresAt") java.util.Date coolingOffExpiresAt);

    int revokeDeactivation(@Param("id") Long id,
                           @Param("revokedAt") java.util.Date revokedAt);

    int anonymizeUser(@Param("id") Long id);

    java.util.List<UserDO> listExpiredDeactivations(@Param("limit") int limit);

    boolean hasPendingDeactivation(@Param("id") Long id);
}
