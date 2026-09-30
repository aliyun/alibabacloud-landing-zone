package com.aliyun.autowonder.dispatch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Tracks each delivery-restart round's progress in workitem_delivery_restart. */
@Component
public class DeliveryRestartProgressStore {

    private final JdbcTemplate jdbc;

    public DeliveryRestartProgressStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private int roundOf(String idempotencyKey) {
        String value = idempotencyKey == null ? "" : idempotencyKey;
        int first = value.indexOf(':');
        int second = value.indexOf(':', first + 1);
        String round = second < 0 ? "" : value.substring(first + 1, second);
        try {
            return Integer.parseInt(round);
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private int update(long tenantId, long workitemId, String idempotencyKey,
            String status, String stopReason, Long dispatchId) {
        return jdbc.update("UPDATE workitem_delivery_restart SET status=?,stop_reason=?,dispatch_id=?,"
                        + "gmt_modified=NOW(3) WHERE tenant_id=? AND workitem_id=? AND restart_round=?",
                status, stopReason, dispatchId, tenantId, workitemId, roundOf(idempotencyKey));
    }

    public void markStoppingOld(long tenantId, long workitemId, String idempotencyKey, int stoppingCount) {
        update(tenantId, workitemId, idempotencyKey, "STOPPING_OLD",
                "正在停止旧执行 " + stoppingCount + " 个，停止确认后启动新轮次", null);
    }

    public void markStarting(long tenantId, long workitemId, String idempotencyKey) {
        update(tenantId, workitemId, idempotencyKey, "STARTING", null, null);
    }

    public void markStarted(long tenantId, long workitemId, String idempotencyKey, long dispatchId) {
        update(tenantId, workitemId, idempotencyKey, "STARTED", null, dispatchId);
    }

    public void markFailed(long tenantId, long workitemId, String idempotencyKey, String reason) {
        update(tenantId, workitemId, idempotencyKey, "FAILED",
                reason == null ? null : reason.substring(0, Math.min(512, reason.length())), null);
    }
}
