package com.aliyun.autowonder.im;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlatformImChannelConfigDao {
    List<PlatformImChannelConfigDO> listByPurpose(@Param("purpose") String purpose);

    PlatformImChannelConfigDO findByProviderAndPurpose(@Param("provider") String provider,
            @Param("purpose") String purpose);

    PlatformImChannelConfigDO findById(@Param("id") Long id);

    /**
     * Current locking read of the group-chat row. Senders hold it from the final route check until
     * the external call finishes, so an identity change cannot commit mid-send.
     */
    PlatformImChannelConfigDO lockGroupChatConfig(@Param("provider") String provider,
            @Param("purpose") String purpose);

    String selectedProvider();

    ImSelectionDO selection();

    ImSelectionDO lockSelection();

    int selectProvider(@Param("provider") String provider);

    int switchProvider(@Param("provider") String provider);

    int disableOthers(@Param("provider") String provider, @Param("purpose") String purpose,
            @Param("userId") long userId);

    int upsert(PlatformImChannelConfigDO config);
}
