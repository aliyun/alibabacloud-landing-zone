package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.AiQuotaVO;
import com.aliyun.autowonder.aiusage.dto.AiUsageVO;
import com.aliyun.autowonder.aiusage.dto.UpdateQuotaRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiUsageServiceTest {

    private AiUsageDao usageDao;
    private AiQuotaDao quotaDao;
    private RedisManager redisManager;
    private AiUsageService service;

    @BeforeEach
    void setUp() {
        usageDao = mock(AiUsageDao.class);
        quotaDao = mock(AiQuotaDao.class);
        redisManager = mock(RedisManager.class);
        service = new AiUsageService(usageDao, quotaDao, redisManager);
    }

    @Test
    void checkQuotaNoQuotaConfigPasses() {
        when(quotaDao.findByTenant(1L)).thenReturn(null);
        assertDoesNotThrow(() -> service.checkQuota(1L));
    }

    @Test
    void checkQuotaCallsUnderLimitPasses() {
        AiQuotaDO quota = new AiQuotaDO();
        quota.setMaxCalls(100L);
        when(quotaDao.findByTenant(1L)).thenReturn(quota);
        when(redisManager.getString(contains(":calls"))).thenReturn("50");

        assertDoesNotThrow(() -> service.checkQuota(1L));
    }

    @Test
    void checkQuotaCallsExceededThrows() {
        AiQuotaDO quota = new AiQuotaDO();
        quota.setMaxCalls(100L);
        when(quotaDao.findByTenant(1L)).thenReturn(quota);
        when(redisManager.getString(contains(":calls"))).thenReturn("100");

        BizException ex = assertThrows(BizException.class, () -> service.checkQuota(1L));
        assertEquals(ErrorCode.AI_QUOTA_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    void checkQuotaTokensExceededThrows() {
        AiQuotaDO quota = new AiQuotaDO();
        quota.setMaxTokens(500000L);
        when(quotaDao.findByTenant(1L)).thenReturn(quota);
        when(redisManager.getString(contains(":calls"))).thenReturn(null);
        when(redisManager.getString(contains(":tokens"))).thenReturn("500000");

        BizException ex = assertThrows(BizException.class, () -> service.checkQuota(1L));
        assertEquals(ErrorCode.AI_QUOTA_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    void checkQuotaTokensUnderLimitPasses() {
        AiQuotaDO quota = new AiQuotaDO();
        quota.setMaxTokens(500000L);
        when(quotaDao.findByTenant(1L)).thenReturn(quota);
        when(redisManager.getString(contains(":calls"))).thenReturn(null);
        when(redisManager.getString(contains(":tokens"))).thenReturn("499999");

        assertDoesNotThrow(() -> service.checkQuota(1L));
    }

    @Test
    void checkAndRecordUsageAtomicSuccess() {
        AiQuotaDO quota = new AiQuotaDO();
        quota.setMaxCalls(100L);
        quota.setMaxTokens(500000L);
        when(quotaDao.findByTenant(1L)).thenReturn(quota);
        when(redisManager.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        assertDoesNotThrow(() -> service.checkAndRecordUsage(1L, "CLARIFICATION", 500, 200));
        verify(usageDao).upsert(eq(1L), anyString(), eq("CLARIFICATION"), eq(1L), eq(500L), eq(200L));
    }

    @Test
    void checkAndRecordUsageAtomicCallsExceededThrows() {
        AiQuotaDO quota = new AiQuotaDO();
        quota.setMaxCalls(100L);
        when(quotaDao.findByTenant(1L)).thenReturn(quota);
        when(redisManager.eval(anyString(), anyList(), anyList())).thenReturn(-1L);

        BizException ex = assertThrows(BizException.class,
                () -> service.checkAndRecordUsage(1L, "CLARIFICATION", 500, 200));
        assertEquals(ErrorCode.AI_QUOTA_EXCEEDED.getCode(), ex.getCode());
        verify(usageDao, never()).upsert(anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void checkAndRecordUsageAtomicTokensExceededThrows() {
        AiQuotaDO quota = new AiQuotaDO();
        quota.setMaxTokens(500000L);
        when(quotaDao.findByTenant(1L)).thenReturn(quota);
        when(redisManager.eval(anyString(), anyList(), anyList())).thenReturn(-2L);

        BizException ex = assertThrows(BizException.class,
                () -> service.checkAndRecordUsage(1L, "CLARIFICATION", 500, 200));
        assertEquals(ErrorCode.AI_QUOTA_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    void recordUsageIncrementsRedisAndDb() {
        service.recordUsage(1L, "CLARIFICATION", 500, 200);

        verify(redisManager).exIncrBy(contains(":calls"), eq(1L), anyLong());
        verify(redisManager).exIncrBy(contains(":tokens"), eq(700L), anyLong());
        verify(usageDao).upsert(eq(1L), anyString(), eq("CLARIFICATION"), eq(1L), eq(500L), eq(200L));
    }

    @Test
    void listUsageReturnsVOs() {
        AiUsageDO u = new AiUsageDO();
        u.setPeriod("2026-07");
        u.setScene("CLARIFICATION");
        u.setCallCount(10L);
        u.setInputTokens(5000L);
        u.setOutputTokens(3000L);
        when(usageDao.listByTenant(1L, "2026-07")).thenReturn(List.of(u));

        List<AiUsageVO> result = service.listUsage(1L, "2026-07");
        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).getCallCount());
    }

    @Test
    void getQuotaReturnsVO() {
        AiQuotaDO q = new AiQuotaDO();
        q.setPeriodType("MONTH");
        q.setMaxCalls(1000L);
        q.setMaxTokens(500000L);
        q.setConcurrencyLimit(5);
        when(quotaDao.findByTenant(1L)).thenReturn(q);

        AiQuotaVO vo = service.getQuota(1L);
        assertEquals("MONTH", vo.getPeriodType());
        assertEquals(1000L, vo.getMaxCalls());
        assertEquals(500000L, vo.getMaxTokens());
        assertEquals(5, vo.getConcurrencyLimit());
    }

    @Test
    void getQuotaReturnsDefaultWhenNull() {
        when(quotaDao.findByTenant(1L)).thenReturn(null);
        AiQuotaVO vo = service.getQuota(1L);
        assertEquals("MONTH", vo.getPeriodType());
        assertNull(vo.getMaxCalls());
    }

    @Test
    void updateQuotaCreatesIfNotExists() {
        when(quotaDao.findByTenant(1L)).thenReturn(null);

        UpdateQuotaRequest req = new UpdateQuotaRequest();
        req.setMaxCalls(1000L);
        req.setMaxTokens(500000L);
        req.setConcurrencyLimit(5);

        service.updateQuota(req, 1L);
        verify(quotaDao).insert(any());
    }

    @Test
    void updateQuotaUpdatesExisting() {
        AiQuotaDO existing = new AiQuotaDO();
        existing.setTenantId(1L);
        when(quotaDao.findByTenant(1L)).thenReturn(existing);

        UpdateQuotaRequest req = new UpdateQuotaRequest();
        req.setMaxCalls(2000L);
        req.setMaxTokens(1000000L);
        req.setConcurrencyLimit(10);

        service.updateQuota(req, 1L);
        verify(quotaDao).update(1L, 2000L, 1000000L, 10);
    }
}
