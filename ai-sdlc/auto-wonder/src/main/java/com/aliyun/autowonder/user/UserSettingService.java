package com.aliyun.autowonder.user;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.dto.UserSettingVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户级偏好读写。userId 一律由调用方从登录上下文传入、且参与每一条 DAO 语句的过滤，
 * 因此单个用户无法读写到他人的配置。
 */
@Service
public class UserSettingService {

    /** 与 user_setting.setting_key VARCHAR(128) 对齐。 */
    static final int MAX_KEY_LENGTH = 128;
    /** 偏好是轻量值；给上限以免 JSON 列被当成通用对象存储塞入超大内容。 */
    static final int MAX_VALUE_JSON_LENGTH = 65536;

    private final UserSettingDao userSettingDao;

    public UserSettingService(UserSettingDao userSettingDao) {
        this.userSettingDao = userSettingDao;
    }

    /** 未设置过时返回 key 有值、valueJson 为 null 的 VO，调用方无需区分「无记录」与「无响应」。 */
    public UserSettingVO get(long userId, String key) {
        validateKey(key);
        return toVO(key, userSettingDao.findLive(userId, key));
    }

    public List<UserSettingVO> listByUser(long userId) {
        List<UserSettingVO> result = new ArrayList<>();
        for (UserSettingDO row : userSettingDao.listByUser(userId)) {
            result.add(toVO(row.getSettingKey(), row));
        }
        return result;
    }

    @Transactional
    public UserSettingVO upsert(long userId, String key, String valueJson) {
        validateKey(key);
        String normalized = normalizeValueJson(valueJson);
        UserSettingDO existing = userSettingDao.findByUk(userId, key);
        if (existing == null) {
            UserSettingDO row = new UserSettingDO();
            row.setUserId(userId);
            row.setSettingKey(key);
            row.setValueJson(normalized);
            row.setCreatorId(userId);
            row.setModifierId(userId);
            userSettingDao.insert(row);
        } else {
            userSettingDao.update(existing.getId(), userId, normalized, userId);
        }
        UserSettingVO vo = new UserSettingVO();
        vo.setKey(key);
        vo.setValueJson(normalized);
        return vo;
    }

    /** 幂等：删除不存在的 key 直接返回，方便前端把「恢复默认」实现成一次 delete。 */
    @Transactional
    public void delete(long userId, String key) {
        validateKey(key);
        UserSettingDO existing = userSettingDao.findByUk(userId, key);
        if (existing == null) {
            return;
        }
        userSettingDao.softDelete(existing.getId(), userId);
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    /**
     * 入库前先按 JSON 解析一次：value_json 是 MySQL JSON 列，非法文本会让 INSERT 抛原生
     * SQL 异常变成 500，这里提前转成可读的参数错误。null 表示清空取值，是合法输入。
     */
    private String normalizeValueJson(String valueJson) {
        if (valueJson == null) {
            return null;
        }
        if (valueJson.isBlank() || valueJson.length() > MAX_VALUE_JSON_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        try {
            JSON.parse(valueJson);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return valueJson;
    }

    private UserSettingVO toVO(String key, UserSettingDO row) {
        UserSettingVO vo = new UserSettingVO();
        vo.setKey(key);
        vo.setValueJson(row == null ? null : row.getValueJson());
        return vo;
    }
}
