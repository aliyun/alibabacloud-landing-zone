package com.aliyun.autowonder.audit;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.audit.dto.AuditLogQuery;
import com.aliyun.autowonder.audit.dto.AuditLogVO;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final int MAX_DETAIL_JSON_CHARS = 4000;

    private final AuditLogDao auditLogDao;
    private final UserDao userDao;
    private final AgentDao agentDao;

    public AuditLogService(AuditLogDao auditLogDao, UserDao userDao, AgentDao agentDao) {
        this.auditLogDao = auditLogDao;
        this.userDao = userDao;
        this.agentDao = agentDao;
    }

    public void record(AuditLogRecord record) {
        AuditLogDO logDO = toLogDO(record);
        if (logDO == null) {
            return;
        }
        try {
            auditLogDao.insert(logDO);
        } catch (Exception e) {
            log.warn("audit log record skipped module={} action={} tenantId={}",
                    record.getModule(), record.getAction(), record.getTenantId(), e);
        }
    }

    public void recordRequired(AuditLogRecord record) {
        AuditLogDO logDO = toLogDO(record);
        if (logDO == null) {
            throw new IllegalStateException("Required audit record is invalid");
        }
        auditLogDao.insert(logDO);
    }

    public List<AuditLogVO> search(AuditLogQuery query, long tenantId, int page, int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * sz;
        List<AuditLogVO> result = new ArrayList<>();
        for (AuditLogDO log : auditLogDao.search(tenantId, query.getModule(), query.getAction(),
                query.getActorId(), query.getTargetType(), query.getTargetId(),
                query.getStartTime(), query.getEndTime(), query.getKeyword(), offset, sz)) {
            result.add(toVO(log));
        }
        return result;
    }

    public int count(AuditLogQuery query, long tenantId) {
        return auditLogDao.countSearch(tenantId, query.getModule(), query.getAction(),
                query.getActorId(), query.getTargetType(), query.getTargetId(),
                query.getStartTime(), query.getEndTime(), query.getKeyword());
    }

    private AuditLogDO toLogDO(AuditLogRecord record) {
        if (record == null || record.getTenantId() <= 0
                || isBlank(record.getModule()) || isBlank(record.getAction())) {
            return null;
        }
        AuditLogDO logDO = new AuditLogDO();
        logDO.setTenantId(record.getTenantId());
        logDO.setActorId(record.getActorId());
        logDO.setModule(record.getModule());
        logDO.setAction(record.getAction());
        logDO.setTargetType(record.getTargetType());
        logDO.setTargetId(record.getTargetId());
        logDO.setDetailJson(buildDetail(record));
        return logDO;
    }

    private String buildDetail(AuditLogRecord record) {
        Map<String, Object> detail = new LinkedHashMap<>();
        put(detail, "actorType", record.getActorType());
        put(detail, "triggerType", record.getTriggerType());
        put(detail, "triggerSource", record.getTriggerSource());
        put(detail, "eventType", record.getEventType());
        if (record.getDetail() != null) {
            record.getDetail().forEach((key, value) -> put(detail, key, sanitize(value)));
        }
        String json = JSON.toJSONString(detail);
        if (json.length() <= MAX_DETAIL_JSON_CHARS) {
            return json;
        }
        JSONObject truncated = new JSONObject();
        truncated.put("actorType", record.getActorType());
        truncated.put("triggerType", record.getTriggerType());
        truncated.put("triggerSource", record.getTriggerSource());
        truncated.put("eventType", record.getEventType());
        truncated.put("truncated", true);
        truncated.put("originalLength", json.length());
        return truncated.toJSONString();
    }

    private void put(Map<String, Object> detail, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            detail.put(key, value);
        }
    }

    private Object sanitize(Object value) {
        if (value instanceof String text) {
            return text.length() > 512 ? text.substring(0, 512) : text;
        }
        return value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AuditLogVO toVO(AuditLogDO log) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(log.getId());
        vo.setActorId(log.getActorId());
        String actorType = actorTypeOf(log.getDetailJson());
        vo.setActorType(actorType);
        vo.setActorName(resolveActorName(actorType, log.getActorId()));
        vo.setModule(log.getModule());
        vo.setAction(log.getAction());
        vo.setTargetType(log.getTargetType());
        vo.setTargetId(log.getTargetId());
        vo.setDetailJson(log.getDetailJson());
        vo.setGmtCreate(log.getGmtCreate());
        return vo;
    }

    private String actorTypeOf(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(detailJson).getString("actorType");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveActorName(String actorType, Long actorId) {
        if (actorId == null || actorType == null) {
            return null;
        }
        try {
            if ("AGENT".equals(actorType)) {
                AgentDO agent = agentDao.findById(actorId);
                return agent == null ? null : agent.getName();
            }
            if ("HUMAN".equals(actorType)) {
                UserDO user = userDao.findById(actorId);
                if (user == null) {
                    return null;
                }
                if (user.getNickname() != null && !user.getNickname().isBlank()) {
                    return user.getNickname();
                }
                return user.getUsername();
            }
        } catch (Exception e) {
            log.warn("audit actor name resolve skipped actorType={} actorId={}", actorType, actorId, e);
        }
        return null;
    }
}
