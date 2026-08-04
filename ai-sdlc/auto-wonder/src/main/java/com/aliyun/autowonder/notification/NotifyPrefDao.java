package com.aliyun.autowonder.notification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface NotifyPrefDao {
    void insert(NotifyPrefDO pref);

    List<NotifyPrefDO> listByUser(@Param("tenantId") Long tenantId,
                                   @Param("userId") Long userId);

    NotifyPrefDO findByUserAndType(@Param("tenantId") Long tenantId,
                                    @Param("userId") Long userId,
                                    @Param("type") String type);

    int update(@Param("id") Long id, @Param("inApp") Integer inApp,
               @Param("dingtalk") Integer dingtalk);
}
