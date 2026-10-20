package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;

/** Immutable human steering corpus. Delivery retries never alter the original instruction or actor. */
@Service
public class DispatchGuidanceService {
    private final JdbcTemplate jdbc;
    private final DispatchDao dispatches;
    private final PresenceManager presence;
    private final SessionRegistry sessions;
    private final RedisManager redis;
    public DispatchGuidanceService(JdbcTemplate jdbc, DispatchDao dispatches, PresenceManager presence,
            SessionRegistry sessions, RedisManager redis) {
        this.jdbc=jdbc; this.dispatches=dispatches; this.presence=presence; this.sessions=sessions; this.redis=redis;
    }
    public DispatchDO require(long tenantId, long dispatchId) {
        DispatchDO d=dispatches.findById(dispatchId);
        if (d==null || !Objects.equals(d.getTenantId(), tenantId)
                || d.executionSourceType()!=ExecutionSourceType.WORKITEM)
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        return d;
    }
    public List<Map<String,Object>> list(long tenantId, long dispatchId) {
        require(tenantId, dispatchId);
        return jdbc.queryForList("SELECT id, workitem_id AS workitemId, dispatch_id AS dispatchId, actor_id AS actorId, content, status, error, gmt_create AS createdAt, applied_at AS appliedAt FROM dispatch_guidance WHERE workspace_id=? AND dispatch_id=? ORDER BY id", tenantId, dispatchId);
    }
    public List<Map<String,Object>> create(long tenantId, long dispatchId, long actorId, String content, String requestKey) {
        DispatchDO d=require(tenantId, dispatchId);
        if(content==null || content.isBlank() || content.length()>16000 || requestKey==null || !requestKey.matches("[a-zA-Z0-9-]{1,64}"))
            throw problem("invalid", "A non-empty instruction (up to 16000 characters) and request key are required");
        var previous=jdbc.queryForList("SELECT actor_id, content FROM dispatch_guidance WHERE workspace_id=? AND dispatch_id=? AND request_key=?",tenantId,dispatchId,requestKey);
        if(!previous.isEmpty()) {
            var row=previous.getFirst();
            if(((Number)row.get("actor_id")).longValue()!=actorId || !content.equals(row.get("content"))) throw problem("key_conflict", "Request key already used");
            return list(tenantId,dispatchId);
        }
        if(!DispatchStatus.RUNNING.equals(d.getStatus()) || d.getExecutorId()==null
                || !presence.isExecutorOnline(d.getExecutorId()) || !presence.supportsProtocolFeature(d.getExecutorId(), "terminal_steer_v1"))
            throw problem("unavailable", "The dispatch must be running on an online Runtime supporting terminal steering");
        try {
            jdbc.update("INSERT INTO dispatch_guidance (workspace_id,workitem_id,dispatch_id,executor_id,actor_id,request_key,content) VALUES (?,?,?,?,?,?,?)",tenantId,d.getWorkitemId(),dispatchId,d.getExecutorId(),actorId,requestKey,content);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // A concurrent retry must preserve the original author and exact instruction too.
            return create(tenantId, dispatchId, actorId, content, requestKey);
        }
        // The durable row is the outbox; scheduled delivery also recovers process/socket failures.
        deliverPending();
        return list(tenantId,dispatchId);
    }
    @Scheduled(fixedDelay=3000)
    public void deliverPending() {
        for(var row:jdbc.queryForList("SELECT * FROM dispatch_guidance WHERE status='QUEUED' ORDER BY id LIMIT 100")) {
            long tenant=((Number)row.get("workspace_id")).longValue(), id=((Number)row.get("id")).longValue();
            long dispatch=((Number)row.get("dispatch_id")).longValue(), executor=((Number)row.get("executor_id")).longValue();
            DispatchDO d=dispatches.findById(dispatch);
            if(d==null || !Objects.equals(d.getTenantId(),tenant) || !Objects.equals(d.getExecutorId(),executor) || !DispatchStatus.RUNNING.equals(d.getStatus())) {
                jdbc.update("UPDATE dispatch_guidance SET status='FAILED',error=?,gmt_modified=NOW(3) WHERE id=? AND status='QUEUED'","Dispatch ended or changed executor before steering acknowledgement",id);
                continue;
            }
            JSONObject f=new JSONObject(); f.put("type","TASK_STEER"); f.put("guidanceId",id); f.put("dispatchId",dispatch); f.put("executorId",executor); f.put("content",row.get("content"));
            try {
                var session=sessions.findByExecutorId(executor);
                if(session!=null && session.getSession().isOpen()) session.sendText(f.toJSONString());
                else redis.publish(WsDispatchTransport.BROADCAST_CHANNEL,f.toJSONString());
            } catch(Exception ignored) { /* durable retry on next sweep */ }
        }
    }
    public void acknowledge(long tenant,long executor,long dispatch,long id,String status,String error) {
        if(!Set.of("APPLIED","FAILED").contains(status)) throw problem("invalid", "Invalid steering receipt");
        jdbc.update("UPDATE dispatch_guidance SET status=?,error=?,applied_at=CASE WHEN ?='APPLIED' THEN NOW(3) ELSE NULL END,gmt_modified=NOW(3) WHERE workspace_id=? AND executor_id=? AND dispatch_id=? AND id=? AND status='QUEUED'",status,error,status,tenant,executor,dispatch,id);
    }
    private BizException problem(String key,String diagnostic) {
        return BizException.keyed(ErrorCode.CONFLICT,"dispatch.guidance."+key,new Object[0],diagnostic);
    }
}
