package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.json.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Uses the existing V051 frozen plan/step tables; only the owner can consume a plan, once. */
@Service
public class PlatformActionPlanService {
    public static final String PROPOSE_TOOL = "autowonder.propose_platform_actions";
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PlatformActionPlanService.class);
    private final JdbcTemplate jdbc;
    private final PlatformConversationAccessService access;
    private final AgentConversationTurnDao turns;
    public PlatformActionPlanService(JdbcTemplate jdbc, PlatformConversationAccessService access, AgentConversationTurnDao turns) {
        this.jdbc=jdbc; this.access=access; this.turns=turns;
    }
    public boolean isPlatformOwner(long workspaceId,long userId,long conversationId) {
        try { access.requireBodyWrite(workspaceId,conversationId,userId); return true; }
        catch (BizException denied) { return false; }
    }
    public record Action(String tool, Map<String,Object> arguments) {}
    public record Plan(String id, String actionsJson, String payloadHash, String status, String resultJson, String expiresAt) {}
    private static final String COLUMNS="id,canonical_payload_json,payload_sha256,status,expires_at";

    @Transactional
    public Plan propose(long workspaceId,long userId,long conversationId,List<Action> actions) {
        requireActive(workspaceId,userId,conversationId);
        var turn=turns.findProcessingInbound(workspaceId,conversationId);
        if(turn==null || actions.isEmpty() || actions.size()>10) throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        String frozen=JSON.toJSONString(canonical(JSON.parse(JSON.toJSONString(actions))));
        if(frozen.length()>32000) throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        String hash=hash(turn.getId()+":"+frozen);
        var previous=jdbc.query("SELECT "+COLUMNS+" FROM conversation_action_plan WHERE tenant_id=? AND conversation_id=? AND payload_sha256=?",
                this::readPlan,workspaceId,conversationId,hash);
        if(!previous.isEmpty()) return publicPlan(previous.get(0));
        Timestamp expires=Timestamp.from(Instant.now().plusSeconds(1800));
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement(
                "INSERT INTO conversation_action_plan (tenant_id,workspace_id,conversation_id,turn_id,owner_user_id,canonical_payload_json,payload_sha256,status,expires_at) VALUES (?,?,?,?,?,?,?,'PENDING_CONFIRMATION',?)",
                new String[]{"id"});
            statement.setLong(1,workspaceId);statement.setLong(2,workspaceId);statement.setLong(3,conversationId);
            statement.setLong(4,turn.getId());statement.setLong(5,userId);statement.setString(6,frozen);statement.setString(7,hash);statement.setTimestamp(8,expires);
            return statement;
        },key);
        long id=Objects.requireNonNull(key.getKey()).longValue();
        int step=0;
        for(Action action:actions) {
            String args=JSON.toJSONString(canonical(action.arguments()));
            jdbc.update("INSERT INTO conversation_action_step (tenant_id,plan_id,step_no,tool_name,arguments_json,arguments_sha256,idempotency_key,idempotent,status) VALUES (?,?,?,?,?,?,?,0,'PENDING')",
                    workspaceId,id,++step,action.tool(),args,hash(args),"chief:"+id+":"+step);
        }
        return publicPlan(new Plan(String.valueOf(id),frozen,hash,"PENDING_CONFIRMATION",null,expires.toInstant().toString()));
    }
    public List<Plan> list(long workspaceId,long userId,long conversationId) {
        access.requireBodyRead(workspaceId,conversationId,userId);
        var plans=jdbc.query("SELECT "+COLUMNS+" FROM conversation_action_plan WHERE tenant_id=? AND workspace_id=? AND conversation_id=? AND owner_user_id=? ORDER BY id DESC LIMIT 100",
                this::readPlan,workspaceId,workspaceId,conversationId,userId);
        if (plans.isEmpty()) return List.of();
        Map<String,List<Object>> results=new HashMap<>();
        jdbc.query("SELECT s.plan_id,s.result_summary_json FROM conversation_action_step s JOIN conversation_action_plan p ON p.id=s.plan_id AND p.tenant_id=s.tenant_id WHERE p.tenant_id=? AND p.workspace_id=? AND p.conversation_id=? AND p.owner_user_id=? AND p.id>=? AND s.result_summary_json IS NOT NULL ORDER BY s.plan_id,s.step_no",
                (org.springframework.jdbc.core.RowCallbackHandler)row->results.computeIfAbsent(row.getString(1),ignored->new ArrayList<>()).add(JSON.parse(row.getString(2))),
                workspaceId,workspaceId,conversationId,userId,plans.get(plans.size()-1).id());
        return plans.stream().map(plan->publicPlan(new Plan(plan.id(),plan.actionsJson(),plan.payloadHash(),plan.status(),
                results.containsKey(plan.id())?JSON.toJSONString(results.get(plan.id())):null,plan.expiresAt()))).toList();
    }
    public Plan resolve(long workspaceId,long userId,long conversationId,String id,String payloadHash,boolean confirm,
            Function<Action,Object> execute) {
        requireActive(workspaceId,userId,conversationId);
        try { if (Long.parseLong(id)<=0) throw new NumberFormatException(); }
        catch(NumberFormatException invalid) { throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID); }
        Plan plan=jdbc.query("SELECT "+COLUMNS+" FROM conversation_action_plan WHERE id=? AND tenant_id=? AND workspace_id=? AND conversation_id=? AND owner_user_id=?",
                this::readPlan,id,workspaceId,workspaceId,conversationId,userId).stream().findFirst().orElseThrow(()->new BizException(ErrorCode.NOT_FOUND));
        if(payloadHash==null || !MessageDigest.isEqual(plan.payloadHash().getBytes(StandardCharsets.UTF_8),payloadHash.getBytes(StandardCharsets.UTF_8)))
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        int consumed=jdbc.update("UPDATE conversation_action_plan SET status=?,approved_by=?,approved_at=CURRENT_TIMESTAMP,consumed_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND tenant_id=? AND workspace_id=? AND conversation_id=? AND owner_user_id=? AND status='PENDING_CONFIRMATION' AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP",
                confirm?"EXECUTING":"REJECTED",userId,id,workspaceId,workspaceId,conversationId,userId);
        if(consumed!=1) throw BizException.keyed(ErrorCode.CONFLICT,"chief.plan.consumed",null,"Action plan is expired or already consumed");
        if(!confirm) return publicPlan(new Plan(id,plan.actionsJson(),plan.payloadHash(),"REJECTED",null,plan.expiresAt()));
        List<Object> outcomes=new ArrayList<>();String status="SUCCEEDED";int step=0;
        for(Action action:JSON.parseArray(plan.actionsJson(),Action.class)) {
            ++step;
            jdbc.update("UPDATE conversation_action_step SET status='RUNNING',started_at=CURRENT_TIMESTAMP WHERE plan_id=? AND tenant_id=? AND step_no=? AND status='PENDING'",id,workspaceId,step);
            long started=System.nanoTime();Object result;boolean failed=false;
            try { result=Map.of("tool",action.tool(),"result",Objects.requireNonNullElse(execute.apply(action),Map.of()),"durationMs",(System.nanoTime()-started)/1_000_000); }
            catch(RuntimeException failure) {
                log.warn("Chief of Staff action failed planId={} step={} tool={}",id,step,action.tool(),failure);
                failed=true;status="PARTIAL_FAILED";
                result=Map.of("tool",action.tool(),"failed",true,"durationMs",(System.nanoTime()-started)/1_000_000,"error",
                        failure instanceof BizException biz?SystemMessages.render(biz.getMessageKey(),biz.getMessageArgs(),biz.getMessage()):SystemMessages.render("chief.plan.tool_failed",null,"Tool execution failed"));
            }
            String summary=PlatformEventProjection.payload("tool_result",JSON.toJSONString(result));
            outcomes.add(JSON.parse(summary));
            jdbc.update("UPDATE conversation_action_step SET status=?,result_summary_json=?,finished_at=CURRENT_TIMESTAMP WHERE plan_id=? AND tenant_id=? AND step_no=?",failed?"FAILED":"SUCCEEDED",summary,id,workspaceId,step);
            if(failed) {jdbc.update("UPDATE conversation_action_step SET status='SKIPPED' WHERE plan_id=? AND tenant_id=? AND status='PENDING'",id,workspaceId);break;}
        }
        jdbc.update("UPDATE conversation_action_plan SET status=?,version=version+1 WHERE id=? AND tenant_id=? AND status='EXECUTING'",status,id,workspaceId);
        return publicPlan(new Plan(id,plan.actionsJson(),plan.payloadHash(),status,JSON.toJSONString(outcomes),plan.expiresAt()));
    }
    private void requireActive(long workspaceId,long userId,long conversationId) {
        var conversation=access.requireBodyWrite(workspaceId,conversationId,userId);
        if(conversation.getArchivedAt()!=null || !"ACTIVE".equals(conversation.getStatus())) throw new BizException(ErrorCode.PLATFORM_CONVERSATION_ARCHIVED);
    }
    private Plan readPlan(ResultSet row,int index)throws SQLException {
        return new Plan(row.getString(1),row.getString(2),row.getString(3),row.getString(4),null,row.getTimestamp(5).toInstant().toString());
    }
    private static Plan publicPlan(Plan plan) {
        String status=switch(plan.status()){case "PENDING_CONFIRMATION"->Instant.parse(plan.expiresAt()).isBefore(Instant.now())?"EXPIRED":"PENDING";case "SUCCEEDED"->"COMPLETED";case "REJECTED","CANCELED"->"CANCELED";case "PARTIAL_FAILED"->"FAILED";default->plan.status();};
        return new Plan(plan.id(),PlatformEventProjection.payload("tool",plan.actionsJson()),plan.payloadHash(),status,
                PlatformEventProjection.payload("tool",plan.resultJson()),plan.expiresAt());
    }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) {Map<String,Object> sorted=new TreeMap<>();map.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted;}
        if(value instanceof List<?> list)return list.stream().map(PlatformActionPlanService::canonical).toList();return value;
    }
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
