package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.AiQuotaVO;
import com.aliyun.autowonder.aiusage.dto.AiUsageVO;
import com.aliyun.autowonder.aiusage.dto.UpdateQuotaRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiUsageService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final long PERIOD_EXPIRE_SEC = 40L * 24 * 3600;

    private static final String CHECK_AND_INCR_LUA =
            "local callKey = KEYS[1]\n" +
            "local tokenKey = KEYS[2]\n" +
            "local maxCalls = tonumber(ARGV[1])\n" +
            "local maxTokens = tonumber(ARGV[2])\n" +
            "local addTokens = tonumber(ARGV[3])\n" +
            "local expireSec = tonumber(ARGV[4])\n" +
            "local curCalls = tonumber(redis.call('GET', callKey) or '0')\n" +
            "if maxCalls > 0 and curCalls >= maxCalls then return -1 end\n" +
            "if maxTokens > 0 then\n" +
            "  local curTokens = tonumber(redis.call('GET', tokenKey) or '0')\n" +
            "  if curTokens >= maxTokens then return -2 end\n" +
            "end\n" +
            "local newCalls = redis.call('INCRBY', callKey, 1)\n" +
            "if newCalls == 1 then redis.call('EXPIRE', callKey, expireSec) end\n" +
            "local newTokens = redis.call('INCRBY', tokenKey, addTokens)\n" +
            "if newTokens == addTokens then redis.call('EXPIRE', tokenKey, expireSec) end\n" +
            "return newCalls\n";

    private final AiUsageDao usageDao;
    private final AiQuotaDao quotaDao;
    private final RedisManager redisManager;

    public AiUsageService(AiUsageDao usageDao, AiQuotaDao quotaDao,
                          RedisManager redisManager) {
        this.usageDao = usageDao;
        this.quotaDao = quotaDao;
        this.redisManager = redisManager;
    }

    public void checkQuota(long tenantId) {
        AiQuotaDO quota = quotaDao.findByTenant(tenantId);
        if (quota == null || (quota.getMaxCalls() == null && quota.getMaxTokens() == null)) {
            return;
        }
        String period = currentPeriod();
        String callKey = callsKey(tenantId, period);
        String tokenKey = tokensKey(tenantId, period);
        String callStr = redisManager.getString(callKey);
        long currentCalls = callStr != null ? Long.parseLong(callStr) : 0;
        if (quota.getMaxCalls() != null && currentCalls >= quota.getMaxCalls()) {
            throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
        String tokenStr = redisManager.getString(tokenKey);
        long currentTokens = tokenStr != null ? Long.parseLong(tokenStr) : 0;
        if (quota.getMaxTokens() != null && currentTokens >= quota.getMaxTokens()) {
            throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
    }

    public void checkAndRecordUsage(long tenantId, String scene, long inputTokens, long outputTokens) {
        AiQuotaDO quota = quotaDao.findByTenant(tenantId);
        long maxCalls = quota != null && quota.getMaxCalls() != null ? quota.getMaxCalls() : 0;
        long maxTokens = quota != null && quota.getMaxTokens() != null ? quota.getMaxTokens() : 0;
        long addTokens = inputTokens + outputTokens;
        String period = currentPeriod();
        String callKey = callsKey(tenantId, period);
        String tokenKey = tokensKey(tenantId, period);

        Object result = redisManager.eval(CHECK_AND_INCR_LUA,
                List.of(callKey, tokenKey),
                List.of(String.valueOf(maxCalls), String.valueOf(maxTokens),
                        String.valueOf(addTokens), String.valueOf(PERIOD_EXPIRE_SEC)));
        long code = result != null ? (Long) result : 0;
        if (code == -1 || code == -2) {
            throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
        usageDao.upsert(tenantId, period, scene, 1, inputTokens, outputTokens);
    }

    public void recordUsage(long tenantId, String scene, long inputTokens, long outputTokens) {
        String period = currentPeriod();
        String callKey = callsKey(tenantId, period);
        String tokenKey = tokensKey(tenantId, period);
        redisManager.exIncrBy(callKey, 1, PERIOD_EXPIRE_SEC);
        redisManager.exIncrBy(tokenKey, inputTokens + outputTokens, PERIOD_EXPIRE_SEC);
        usageDao.upsert(tenantId, period, scene, 1, inputTokens, outputTokens);
    }

    private String callsKey(long tenantId, String period) {
        return "ai:usage:" + tenantId + ":" + period + ":calls";
    }

    private String tokensKey(long tenantId, String period) {
        return "ai:usage:" + tenantId + ":" + period + ":tokens";
    }

    public List<AiUsageVO> listUsage(long tenantId, String period) {
        String p = period != null ? period : currentPeriod();
        List<AiUsageVO> result = new ArrayList<>();
        for (AiUsageDO u : usageDao.listByTenant(tenantId, p)) {
            AiUsageVO vo = new AiUsageVO();
            vo.setPeriod(u.getPeriod());
            vo.setScene(u.getScene());
            vo.setCallCount(u.getCallCount());
            vo.setInputTokens(u.getInputTokens());
            vo.setOutputTokens(u.getOutputTokens());
            result.add(vo);
        }
        return result;
    }

    public AiQuotaVO getQuota(long tenantId) {
        AiQuotaDO q = quotaDao.findByTenant(tenantId);
        AiQuotaVO vo = new AiQuotaVO();
        if (q != null) {
            vo.setPeriodType(q.getPeriodType());
            vo.setMaxCalls(q.getMaxCalls());
            vo.setMaxTokens(q.getMaxTokens());
            vo.setConcurrencyLimit(q.getConcurrencyLimit());
        } else {
            vo.setPeriodType("MONTH");
        }
        return vo;
    }

    public void updateQuota(UpdateQuotaRequest req, long tenantId) {
        AiQuotaDO existing = quotaDao.findByTenant(tenantId);
        if (existing == null) {
            AiQuotaDO q = new AiQuotaDO();
            q.setTenantId(tenantId);
            q.setPeriodType("MONTH");
            q.setMaxCalls(req.getMaxCalls());
            q.setMaxTokens(req.getMaxTokens());
            q.setConcurrencyLimit(req.getConcurrencyLimit());
            quotaDao.insert(q);
        } else {
            quotaDao.update(tenantId, req.getMaxCalls(), req.getMaxTokens(), req.getConcurrencyLimit());
        }
    }

    private String currentPeriod() {
        return LocalDate.now().format(PERIOD_FMT);
    }
}
