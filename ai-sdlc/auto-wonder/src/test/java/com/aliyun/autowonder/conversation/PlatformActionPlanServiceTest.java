package com.aliyun.autowonder.conversation;
import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PlatformActionPlanServiceTest {
    PlatformActionPlanService service;PlatformConversationAccessService access;JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:plan-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE conversation_action_plan(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,workspace_id BIGINT,conversation_id BIGINT,turn_id BIGINT,owner_user_id BIGINT,canonical_payload_json TEXT,payload_sha256 CHAR(64),status VARCHAR(24),expires_at TIMESTAMP,approved_by BIGINT,approved_at TIMESTAMP,consumed_at TIMESTAMP,version INT DEFAULT 0,gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,conversation_id,payload_sha256))");
        jdbc.execute("CREATE TABLE conversation_action_step(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,plan_id BIGINT,step_no INT,tool_name VARCHAR(128),arguments_json TEXT,arguments_sha256 CHAR(64),idempotency_key VARCHAR(128),idempotent INT,status VARCHAR(24),result_summary_json TEXT,started_at TIMESTAMP,finished_at TIMESTAMP)");
        access=mock(PlatformConversationAccessService.class);var conversation=new AgentConversationDO();conversation.setStatus("ACTIVE");
        when(access.requireBodyWrite(1L,2L,3L)).thenReturn(conversation);
        var turns=mock(AgentConversationTurnDao.class);var turn=new AgentConversationTurnDO();turn.setId(4L);
        when(turns.findProcessingInbound(1L,2L)).thenReturn(turn);
        service=new PlatformActionPlanService(jdbc,access,turns);
    }
    @Test void confirmedArgumentsComeFromStorageAndCannotExecuteTwice() {
        var plan=service.propose(1,3,2,List.of(new PlatformActionPlanService.Action("update",Map.of("id",128))));
        AtomicInteger calls=new AtomicInteger();
        var complete=service.resolve(1,3,2,plan.id(),plan.payloadHash(),true,a->{assertEquals(128,a.arguments().get("id"));calls.incrementAndGet();return Map.of("token","secret");});
        assertEquals("COMPLETED",complete.status());assertFalse(complete.resultJson().contains("secret"));
        assertThrows(BizException.class,()->service.resolve(1,3,2,plan.id(),plan.payloadHash(),true,a->{calls.incrementAndGet();return null;}));
        assertEquals(1,calls.get());
    }
    @Test void canceledExpiredAndForeignPlansCannotExecute() {
        var plan=service.propose(1,3,2,List.of(new PlatformActionPlanService.Action("update",Map.of("id",128))));
        service.resolve(1,3,2,plan.id(),plan.payloadHash(),false,a->{fail();return null;});
        assertThrows(BizException.class,()->service.resolve(1,3,2,plan.id(),plan.payloadHash(),true,a->{fail();return null;}));
        assertThrows(BizException.class,()->service.resolve(1,3,2,"foreign",plan.payloadHash(),true,a->{fail();return null;}));
        var expired=service.propose(1,3,2,List.of(new PlatformActionPlanService.Action("update",Map.of())));
        jdbc.update("UPDATE conversation_action_plan SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?",expired.id());
        assertThrows(BizException.class,()->service.resolve(1,3,2,expired.id(),expired.payloadHash(),true,a->{fail();return null;}));
    }
    @Test void failureStopsTheRemainingActionsAndPreservesCompletedOutcomes() {
        var plan=service.propose(1,3,2,List.of(new PlatformActionPlanService.Action("first",Map.of()),new PlatformActionPlanService.Action("second",Map.of()),new PlatformActionPlanService.Action("third",Map.of())));
        AtomicInteger calls=new AtomicInteger();
        var result=service.resolve(1,3,2,plan.id(),plan.payloadHash(),true,a->{if(calls.incrementAndGet()==2)throw new IllegalStateException("private diagnostic");return "ok";});
        assertEquals("FAILED",result.status());assertEquals(2,calls.get());assertFalse(result.resultJson().contains("private diagnostic"));
    }
}
