package com.aliyun.autowonder.notification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface NotificationDao {
    void insert(NotificationDO notification);

    List<NotificationDO> listByRecipient(@Param("tenantId") Long tenantId,
                                          @Param("recipientId") Long recipientId,
                                          @Param("status") String status,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    int countUnread(@Param("tenantId") Long tenantId,
                    @Param("recipientId") Long recipientId);

    int markRead(@Param("id") Long id, @Param("tenantId") Long tenantId,
                 @Param("recipientId") Long recipientId);

    int markAllRead(@Param("tenantId") Long tenantId,
                    @Param("recipientId") Long recipientId);

    int updateChannels(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("channelsJson") String channelsJson);
}
