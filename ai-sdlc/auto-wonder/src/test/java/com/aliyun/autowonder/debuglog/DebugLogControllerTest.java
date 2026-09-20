package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.compat.V037MapperMode;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.capability;
import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.namer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DebugLogControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    private DebugLogDao debugLogDao;
    private ObjectStorage storage;

    /** naming/key 集群已抽到 {@link DebugLogObjectNamer}（S11 前置项 1），按共享 namer(...) helper 装配。 */
    private DebugLogService realService(V037MapperMode mapperMode) {
        debugLogDao = mock(DebugLogDao.class);
        storage = mock(ObjectStorage.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        OssProperties props = new OssProperties();
        props.setArtifactBucket("test-artifact-bucket");
        return new DebugLogService(debugLogDao, dispatchDao, mock(SquadDao.class),
                namer(dispatchDao, mock(AgentVersionDao.class), mock(ScheduledTaskRunDao.class)),
                storage, props, capability(mapperMode));
    }

    private DebugLogDO row(long id, String status) {
        DebugLogDO row = new DebugLogDO();
        row.setId(id);
        row.setTenantId(100L);
        row.setSourceType("WORKITEM");
        row.setSourceId(200L);
        row.setDispatchId(900L + id);
        row.setAgentId(400L);
        row.setRunNo((int) id);
        row.setDispatchStatus("SUCCEEDED");
        row.setObjectKey("debug/200/DevAgent-run-" + id + ".log.gz");
        row.setSizeBytes(2048L);
        row.setTruncated(false);
        row.setStatus(status);
        return row;
    }

    @Test
    void listReturnsRowsWithDownloadUrlForUploadedOnly() {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        DebugLogService service = realService(V037MapperMode.SOURCE_AWARE);
        DebugLogDO pending = row(1L, DebugLogStatus.PENDING);
        DebugLogDO uploaded = row(2L, DebugLogStatus.UPLOADED);
        when(debugLogDao.listForQuery(100L, "WORKITEM", 200L, null, null, 50, 0))
                .thenReturn(List.of(pending, uploaded));
        when(storage.presignGet("test-artifact-bucket/debug/200/DevAgent-run-2.log.gz", 600))
                .thenReturn("https://oss/get?sig=1");

        List<DebugLogVO> data = new DebugLogController(service)
                .list("WORKITEM", 200L, null, null, 1, 50).getData();

        assertEquals(2, data.size());
        assertNull(data.get(0).getDownloadUrl());
        assertEquals("https://oss/get?sig=1", data.get(1).getDownloadUrl());
        assertEquals("debug/200/DevAgent-run-2.log.gz", data.get(1).getObjectKey());
        assertEquals(2, data.get(1).getRunNo());
        assertEquals(2048L, data.get(1).getSizeBytes());
        assertEquals(false, data.get(1).getTruncated());
    }

    @Test
    void listPassesAgentAndSinceFiltersWithPagingClamped() {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        DebugLogService service = realService(V037MapperMode.SOURCE_AWARE);
        when(debugLogDao.listForQuery(100L, "SCHEDULED_TASK_RUN", 77L, 400L,
                new Date(1_700_000_000_000L), 200, 0)).thenReturn(List.of());

        new DebugLogController(service)
                .list("SCHEDULED_TASK_RUN", 77L, 400L, 1_700_000_000_000L, 0, 1000);

        verify(debugLogDao).listForQuery(100L, "SCHEDULED_TASK_RUN", 77L, 400L,
                new Date(1_700_000_000_000L), 200, 0);
    }

    /** S12 质量审查 Minor#3：offset 数学（page-1）*size 钉死，page=3&size=50 → offset=100。 */
    @Test
    void pageThreeWithSizeFiftyQueriesDaoAtOffsetOneHundred() {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        DebugLogService service = realService(V037MapperMode.SOURCE_AWARE);
        when(debugLogDao.listForQuery(100L, "WORKITEM", 200L, null, null, 50, 100))
                .thenReturn(List.of());

        new DebugLogController(service).list("WORKITEM", 200L, null, null, 3, 50);

        verify(debugLogDao).listForQuery(100L, "WORKITEM", 200L, null, null, 50, 100);
    }

    @Test
    void legacySchemaReturnsEmptyListWithoutTouchingDao() {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        DebugLogService service = realService(V037MapperMode.LEGACY);

        List<DebugLogVO> data = new DebugLogController(service)
                .list("WORKITEM", 200L, null, null, 1, 50).getData();

        assertTrue(data.isEmpty());
        verifyNoInteractions(debugLogDao);
    }

    @Test
    void invalidSourceTypeRejected() {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        DebugLogController controller = new DebugLogController(realService(V037MapperMode.SOURCE_AWARE));

        BizException e = assertThrows(BizException.class,
                () -> controller.list("SCHEDULED_TASK", 1L, null, null, 1, 50));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());

        BizException e2 = assertThrows(BizException.class,
                () -> controller.list("NOPE", 1L, null, null, 1, 50));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e2.getCode());
    }

    @Test
    void missingWorkspaceContextRejected() {
        DebugLogController controller = new DebugLogController(realService(V037MapperMode.SOURCE_AWARE));

        BizException e = assertThrows(BizException.class,
                () -> controller.list("WORKITEM", 1L, null, null, 1, 50));
        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), e.getCode());
    }

    @Test
    void downloadUrlSkipsPresignForNonUploadedRows() {
        DebugLogService service = realService(V037MapperMode.SOURCE_AWARE);

        assertNull(service.downloadUrl(row(1L, DebugLogStatus.PENDING)));
        assertNull(service.downloadUrl(row(1L, DebugLogStatus.FAILED)));
        verifyNoInteractions(storage);
    }

    /**
     * S10 Minor#5 + S9 契约注记：查询 API 必须容忍非终态 dispatch_status（中转补插路径先记 RUNNING
     * 临时值，由 TASK_RESULT 收尾），且 UPLOADED 行的 error_message 可非空（Writer Close 收尾注记）——
     * VO 一律原样透出，语义由前端 S14 渲染（error 非空不得推断失败，展示为 warning）。
     */
    @Test
    void provisionalDispatchStatusAndUploadedErrorNotePassThroughUnchanged() {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        DebugLogService service = realService(V037MapperMode.SOURCE_AWARE);
        DebugLogDO relayProvisional = row(3L, DebugLogStatus.UPLOADED);
        relayProvisional.setDispatchStatus("RUNNING");
        relayProvisional.setErrorMessage("writer close note");
        relayProvisional.setUploadChannel("RELAY");
        when(debugLogDao.listForQuery(100L, "WORKITEM", 200L, null, null, 50, 0))
                .thenReturn(List.of(relayProvisional));
        when(storage.presignGet("test-artifact-bucket/debug/200/DevAgent-run-3.log.gz", 600))
                .thenReturn("https://oss/get?sig=3");

        List<DebugLogVO> data = new DebugLogController(service)
                .list("WORKITEM", 200L, null, null, 1, 50).getData();

        assertEquals(1, data.size());
        assertEquals("RUNNING", data.get(0).getDispatchStatus());
        assertEquals("writer close note", data.get(0).getErrorMessage());
        assertEquals("RELAY", data.get(0).getUploadChannel());
        assertEquals("https://oss/get?sig=3", data.get(0).getDownloadUrl());
    }
}
