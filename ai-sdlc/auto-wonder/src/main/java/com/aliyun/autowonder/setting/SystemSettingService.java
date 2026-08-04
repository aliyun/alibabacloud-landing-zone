package com.aliyun.autowonder.setting;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.setting.dto.SettingVO;
import com.aliyun.autowonder.setting.dto.UpdateSettingsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class SystemSettingService {

    private static final Set<String> VALID_GROUPS = Set.of("AI", "STORAGE", "NOTIFY", "DEFAULTS", "SYSTEM");

    private final SystemSettingDao settingDao;
    private final SecretCrypto secretCrypto;

    public SystemSettingService(SystemSettingDao settingDao, SecretCrypto secretCrypto) {
        this.settingDao = settingDao;
        this.secretCrypto = secretCrypto;
    }

    public List<SettingVO> listByGroup(String group, long tenantId) {
        validateGroup(group);
        List<SettingVO> result = new ArrayList<>();
        for (SystemSettingDO s : settingDao.listByGroup(tenantId, group)) {
            result.add(toVO(s));
        }
        return result;
    }

    @Transactional
    public void updateGroup(String group, UpdateSettingsRequest req, long tenantId, long userId) {
        validateGroup(group);
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return;
        }
        for (UpdateSettingsRequest.SettingItem item : req.getItems()) {
            SystemSettingDO existing = settingDao.findByUk(tenantId, group, item.getKey());
            if (existing == null) {
                SystemSettingDO s = new SystemSettingDO();
                s.setTenantId(tenantId);
                s.setSettingGroup(group);
                s.setSettingKey(item.getKey());
                if (item.isSecret()) {
                    s.setIsSecret(1);
                    s.setCredentialRef(secretCrypto.encrypt(item.getValueJson()));
                    s.setValueJson(null);
                } else {
                    s.setIsSecret(0);
                    s.setValueJson(item.getValueJson());
                }
                s.setCreatorId(userId);
                s.setModifierId(userId);
                settingDao.insert(s);
            } else {
                String valueJson;
                String credentialRef;
                int isSecret;
                if (item.isSecret()) {
                    isSecret = 1;
                    credentialRef = secretCrypto.encrypt(item.getValueJson());
                    valueJson = null;
                } else {
                    isSecret = 0;
                    valueJson = item.getValueJson();
                    credentialRef = null;
                }
                settingDao.update(existing.getId(), tenantId, valueJson, isSecret, credentialRef, userId);
            }
        }
    }

    public String getDecryptedValue(String group, String key, long tenantId) {
        validateGroup(group);
        SystemSettingDO s = settingDao.findByUk(tenantId, group, key);
        if (s == null) {
            return null;
        }
        if (s.getIsSecret() == 1) {
            return secretCrypto.decrypt(s.getCredentialRef());
        }
        return s.getValueJson();
    }

    private void validateGroup(String group) {
        if (!VALID_GROUPS.contains(group)) {
            throw new BizException(ErrorCode.SETTING_GROUP_INVALID);
        }
    }

    private SettingVO toVO(SystemSettingDO s) {
        SettingVO vo = new SettingVO();
        vo.setId(s.getId());
        vo.setGroup(s.getSettingGroup());
        vo.setKey(s.getSettingKey());
        vo.setSecret(s.getIsSecret() == 1);
        if (s.getIsSecret() == 1) {
            vo.setValueJson(secretCrypto.mask(s.getCredentialRef()));
        } else {
            vo.setValueJson(s.getValueJson());
        }
        return vo;
    }
}
