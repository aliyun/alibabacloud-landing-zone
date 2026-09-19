package com.aliyun.autowonder.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserSettingDao {

    int insert(UserSettingDO setting);

    /**
     * 唯一键定位，故意不过滤 is_deleted：uk_user_setting(user_id, setting_key) 不含 is_deleted，
     * 软删行仍占着唯一键槽位。upsert 必须能看到它并原地复活，否则重新写入会撞唯一键。
     * 对外读取一律走 {@link #findLive}。
     */
    UserSettingDO findByUk(@Param("userId") Long userId,
                           @Param("settingKey") String settingKey);

    UserSettingDO findLive(@Param("userId") Long userId,
                           @Param("settingKey") String settingKey);

    List<UserSettingDO> listByUser(@Param("userId") Long userId);

    /** 同时把 is_deleted 复位为 0，让软删后的再次写入复活原行而不是插入新行。 */
    int update(@Param("id") Long id,
               @Param("userId") Long userId,
               @Param("valueJson") String valueJson,
               @Param("modifierId") Long modifierId);

    /** 幂等：is_deleted = 0 守卫让重复删除写 0 行。 */
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);
}
