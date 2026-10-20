package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchGuidanceServiceTest {
    JdbcTemplate jdbc;
    DispatchDao dispatches;
    PresenceManager presence;
    DispatchGuidanceService service;
    DispatchDO dispatch;
    @BeforeEach void setup() throws Exception {
        // Keep the database alive across JdbcTemplate connections.
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:steer-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        String sql=Files.readString(Path.of("docs/migration/V084__dispatch_guidance.sql"));
        sql=sql.replace(" CHARACTER SET ascii COLLATE ascii_bin", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        jdbc.execute(sql);
        dispatches=mock(DispatchDao.class); presence=mock(PresenceManager.class);
        service=new DispatchGuidanceService(jdbc,dispatches,presence,mock(SessionRegistry.class),mock(RedisManager.class));
        dispatch=new DispatchDO(); dispatch.setId(11L);dispatch.setTenantId(1L);dispatch.setWorkitemId(22L);
        dispatch.setExecutorId(33L);dispatch.setStatus(DispatchStatus.RUNNING);
        when(dispatches.findById(11L)).thenReturn(dispatch);
        when(presence.isExecutorOnline(33L)).thenReturn(true);
        when(presence.supportsProtocolFeature(33L,"terminal_steer_v1")).thenReturn(true);
    }
    @Test void preservesOriginalCorpusAndIdempotency() {
        String text="  改为检查 concurrency <script> & 原样\n";
        var rows=service.create(1,11,7,text,"request-1");
        assertEquals(1,rows.size()); assertEquals(text,rows.getFirst().get("content"));
        assertEquals(7L,((Number)rows.getFirst().get("actorId")).longValue());
        assertEquals(22L,((Number)rows.getFirst().get("workitemId")).longValue());
        assertEquals(1,service.create(1,11,7,text,"request-1").size());
        assertThrows(BizException.class,()->service.create(1,11,8,text,"request-1"));
        assertThrows(BizException.class,()->service.create(1,11,7,"different","request-1"));
    }
    @Test void acknowledgementIsFencedAndMonotonic() {
        service.create(1,11,7,"redirect","request-1");
        long id=jdbc.queryForObject("SELECT id FROM dispatch_guidance",Long.class);
        service.acknowledge(2,33,11,id,"APPLIED",null);
        service.acknowledge(1,34,11,id,"APPLIED",null);
        service.acknowledge(1,33,12,id,"APPLIED",null);
        assertEquals("QUEUED",service.list(1,11).getFirst().get("status"));
        service.acknowledge(1,33,11,id,"APPLIED",null);
        service.acknowledge(1,33,11,id,"FAILED","late failure");
        assertEquals("APPLIED",service.list(1,11).getFirst().get("status"));
        assertNotNull(jdbc.queryForObject("SELECT applied_at FROM dispatch_guidance",java.sql.Timestamp.class));
    }
    @Test void rejectsForeignStoppedAndOldRuntimes() {
        assertThrows(BizException.class,()->service.list(2,11));
        dispatch.setStatus(DispatchStatus.SUCCEEDED);
        assertThrows(BizException.class,()->service.create(1,11,7,"redirect","request-1"));
        dispatch.setStatus(DispatchStatus.RUNNING);
        when(presence.supportsProtocolFeature(33L,"terminal_steer_v1")).thenReturn(false);
        assertThrows(BizException.class,()->service.create(1,11,7,"redirect","request-1"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_guidance",Integer.class));
    }
    @Test void systemRejectionsHaveBothLanguagesWithoutChangingDiagnostics() {
        when(presence.supportsProtocolFeature(33L,"terminal_steer_v1")).thenReturn(false);
        BizException error=assertThrows(BizException.class,()->service.create(1,11,7,"redirect","request-1"));
        String diagnostic=error.getMessage();
        String en=SystemMessages.render("dispatch.guidance.unavailable",new Object[0],diagnostic,Locale.US);
        String zh=SystemMessages.render("dispatch.guidance.unavailable",new Object[0],diagnostic,Locale.SIMPLIFIED_CHINESE);
        assertTrue(en.startsWith("Steering requires"));assertTrue(zh.contains("在线"));assertEquals(diagnostic,error.getMessage());
    }
    @Test void endedDispatchFailsPendingButKeepsAudit() {
        service.create(1,11,7,"redirect","request-1");
        dispatch.setStatus(DispatchStatus.FAILED);service.deliverPending();
        var row=service.list(1,11).getFirst();
        assertEquals("FAILED",row.get("status"));assertEquals("redirect",row.get("content"));
    }
}
