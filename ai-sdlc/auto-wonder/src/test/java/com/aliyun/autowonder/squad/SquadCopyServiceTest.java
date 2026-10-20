package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDO;
import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDao;
import com.aliyun.autowonder.agent.AgentSkillDO;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.environment.EnvironmentVariableDO;
import com.aliyun.autowonder.environment.EnvironmentVariableDao;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.squad.dto.CopyCheckResultVO;
import com.aliyun.autowonder.squad.dto.CopySourceSnapshot;
import com.aliyun.autowonder.squad.dto.CopySquadRequest;
import com.aliyun.autowonder.squad.dto.CopySquadResultVO;
import com.aliyun.autowonder.squad.dto.CopyTargetResultVO;
import com.aliyun.autowonder.squad.dto.CopyTargetVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SquadCopyServiceTest {

    private static final long SOURCE = 100L;
    private static final long TARGET = 200L;
    private static final long OTHER_TARGET = 300L;
    private static final long SQUAD_ID = 5L;
    private static final long USER = 7L;

    SquadDao squadDao;
    SquadMemberDao memberDao;
    AgentDao agentDao;
    AgentVersionDao versionDao;
    AgentSkillDao agentSkillDao;
    AgentEnvironmentVariableRefDao environmentVariableRefDao;
    SdlcDao sdlcDao;
    SdlcStepDao stepDao;
    SkillDao skillDao;
    EnvironmentVariableDao environmentVariableDao;
    ExecutorDao executorDao;
    SquadCopyOperationDao operationDao;
    SquadCopyTargetService targetService;
    WorkspaceDao workspaceDao;
    WorkspaceService workspaceService;
    SystemAdminService systemAdminService;
    ObjectStorage objectStorage;

    SquadCopyService service;

    @BeforeEach
    void setUp() {
        squadDao = mock(SquadDao.class);
        memberDao = mock(SquadMemberDao.class);
        agentDao = mock(AgentDao.class);
        versionDao = mock(AgentVersionDao.class);
        agentSkillDao = mock(AgentSkillDao.class);
        environmentVariableRefDao = mock(AgentEnvironmentVariableRefDao.class);
        sdlcDao = mock(SdlcDao.class);
        stepDao = mock(SdlcStepDao.class);
        skillDao = mock(SkillDao.class);
        environmentVariableDao = mock(EnvironmentVariableDao.class);
        executorDao = mock(ExecutorDao.class);
        operationDao = mock(SquadCopyOperationDao.class);
        targetService = mock(SquadCopyTargetService.class);
        workspaceDao = mock(WorkspaceDao.class);
        workspaceService = mock(WorkspaceService.class);
        systemAdminService = mock(SystemAdminService.class);
        objectStorage = mock(ObjectStorage.class);
        service = new SquadCopyService(squadDao, memberDao, agentDao, versionDao, agentSkillDao,
                environmentVariableRefDao, sdlcDao, stepDao, skillDao, environmentVariableDao,
                executorDao, operationDao, targetService, workspaceDao, workspaceService,
                systemAdminService, objectStorage);
        AutoWonderContext.destroy();
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    private CopySquadRequest request(String requestId, List<Long> targets) {
        CopySquadRequest req = new CopySquadRequest();
        req.setRequestId(requestId);
        req.setTargetWorkspaceIds(targets);
        return req;
    }

    private void stubSourceSquad() {
        SquadDO squad = new SquadDO();
        squad.setId(SQUAD_ID);
        squad.setTenantId(SOURCE);
        squad.setName("开发小队");
        squad.setDescription("核心功能");
        when(squadDao.findById(SQUAD_ID)).thenReturn(squad);
    }

    private void stubEligibleMember() {
        SquadMemberDO member = new SquadMemberDO();
        member.setSquadId(SQUAD_ID);
        member.setAgentId(9L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(member));
        AgentDO agent = new AgentDO();
        agent.setId(9L);
        agent.setTenantId(SOURCE);
        agent.setName("后端数字人");
        agent.setOnlineVersionId(90L);
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(agent));
        AgentVersionDO version = new AgentVersionDO();
        version.setId(90L);
        version.setAgentId(9L);
        version.setVersionNo(1);
        version.setStatus("APPROVED");
        when(versionDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(version));
    }

    private void stubValidTargetCalls() {
        when(targetService.copyToTarget(any(), eq(TARGET), eq(USER), any(), any())).thenReturn(999L);
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(TARGET);
        workspace.setName("目标空间");
        workspace.setStatus(0);
        workspace.setIsDeleted(0);
        when(workspaceDao.findById(TARGET)).thenReturn(workspace);
        when(workspaceService.allowsCopyTarget(TARGET, USER)).thenReturn(true);
    }

    private static SquadCopyOperationDO record(long targetId, String status, String fingerprint) {
        SquadCopyOperationDO record = new SquadCopyOperationDO();
        record.setId(31L);
        record.setTargetWorkspaceId(targetId);
        record.setStatus(status);
        record.setRequestFingerprint(fingerprint);
        return record;
    }

    private static String fingerprintOf(long... targets) {
        StringBuilder material = new StringBuilder();
        material.append(SOURCE).append('|').append(SQUAD_ID).append('|');
        long[] sorted = targets.clone();
        Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) {
            if (i > 0) {
                material.append(',');
            }
            material.append(sorted[i]);
        }
        return CopySourceSnapshot.sha256(material.toString());
    }

    @Test
    void checkCopy_eligibleWhenAllMembersHaveOnlineVersions() {
        stubSourceSquad();
        stubEligibleMember();

        CopyCheckResultVO result = service.checkCopy(SQUAD_ID, SOURCE);

        assertTrue(result.isEligible());
        assertTrue(result.getMissingOnlineVersions().isEmpty());
        assertTrue(result.getMissingDependencies().isEmpty());
    }

    @Test
    void checkCopy_reportsStaleMembershipAsMissingDependency() {
        stubSourceSquad();
        SquadMemberDO member = new SquadMemberDO();
        member.setSquadId(SQUAD_ID);
        member.setAgentId(40474L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(member));
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of());

        CopyCheckResultVO result = service.checkCopy(SQUAD_ID, SOURCE);

        assertFalse(result.isEligible());
        assertTrue(result.getMissingOnlineVersions().isEmpty());
        assertEquals(List.of("agent#40474"), result.getMissingDependencies());
    }

    @Test
    void checkCopy_reportsMissingCapabilityAndSdlcDependencies() {
        stubSourceSquad();
        SquadMemberDO member = new SquadMemberDO();
        member.setSquadId(SQUAD_ID);
        member.setAgentId(9L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(member));
        AgentDO agent = new AgentDO();
        agent.setId(9L);
        agent.setName("后端数字人");
        agent.setOnlineVersionId(90L);
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(agent));
        AgentVersionDO version = new AgentVersionDO();
        version.setId(90L);
        version.setAgentId(9L);
        version.setVersionNo(2);
        version.setStatus("APPROVED");
        version.setSdlcId(30L);
        when(versionDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(version));
        AgentSkillDO skillRef = new AgentSkillDO();
        skillRef.setSkillId(3L);
        when(agentSkillDao.listByVersion(90L)).thenReturn(List.of(skillRef));

        CopyCheckResultVO result = service.checkCopy(SQUAD_ID, SOURCE);

        assertFalse(result.isEligible());
        assertTrue(result.getMissingOnlineVersions().isEmpty());
        assertEquals(List.of("skill#3", "sdlc#30"), result.getMissingDependencies());
    }

    @Test
    void checkCopy_reportsMembersWithoutOnlineVersionAlongsideStaleDependencies() {
        stubSourceSquad();
        SquadMemberDO draft = new SquadMemberDO();
        draft.setSquadId(SQUAD_ID);
        draft.setAgentId(9L);
        SquadMemberDO stale = new SquadMemberDO();
        stale.setSquadId(SQUAD_ID);
        stale.setAgentId(40474L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(draft, stale));
        AgentDO draftAgent = new AgentDO();
        draftAgent.setId(9L);
        draftAgent.setName("草稿数字人");
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(draftAgent));

        CopyCheckResultVO result = service.checkCopy(SQUAD_ID, SOURCE);

        assertFalse(result.isEligible());
        assertEquals(1, result.getMissingOnlineVersions().size());
        assertEquals(9L, result.getMissingOnlineVersions().get(0).getAgentId());
        assertEquals(List.of("agent#40474"), result.getMissingDependencies());
    }

    @Test
    void checkCopy_listsMembersWithoutOnlineVersion() {
        stubSourceSquad();
        SquadMemberDO member = new SquadMemberDO();
        member.setSquadId(SQUAD_ID);
        member.setAgentId(9L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(member));
        AgentDO agent = new AgentDO();
        agent.setId(9L);
        agent.setName("草稿数字人");
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(agent));

        CopyCheckResultVO result = service.checkCopy(SQUAD_ID, SOURCE);

        assertFalse(result.isEligible());
        assertEquals(1, result.getMissingOnlineVersions().size());
        assertEquals(9L, result.getMissingOnlineVersions().get(0).getAgentId());
        assertEquals("草稿数字人", result.getMissingOnlineVersions().get(0).getAgentName());
    }

    @Test
    void checkCopy_missingOnlineVersionRowIsAlsoIneligible() {
        stubSourceSquad();
        SquadMemberDO member = new SquadMemberDO();
        member.setSquadId(SQUAD_ID);
        member.setAgentId(9L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(member));
        AgentDO agent = new AgentDO();
        agent.setId(9L);
        agent.setName("悬空版本数字人");
        agent.setOnlineVersionId(90L);
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(agent));
        when(versionDao.listByIds(eq(SOURCE), any())).thenReturn(List.of());

        CopyCheckResultVO result = service.checkCopy(SQUAD_ID, SOURCE);

        assertFalse(result.isEligible());
        assertEquals("悬空版本数字人", result.getMissingOnlineVersions().get(0).getAgentName());
    }

    @Test
    void checkCopy_squadFromOtherWorkspaceIsNotFound() {
        SquadDO squad = new SquadDO();
        squad.setId(SQUAD_ID);
        squad.setTenantId(999L);
        when(squadDao.findById(SQUAD_ID)).thenReturn(squad);

        BizException ex = assertThrows(BizException.class, () -> service.checkCopy(SQUAD_ID, SOURCE));
        assertEquals(ErrorCode.SQUAD_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void copySquad_requiresRequestId() {
        BizException ex = assertThrows(BizException.class,
                () -> service.copySquad(SQUAD_ID, SOURCE, USER, request("  ", List.of(TARGET))));
        assertEquals(ErrorCode.SQUAD_COPY_REQUEST_ID_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void copySquad_rejectsOverlongRequestId() {
        String requestId = "r".repeat(65);
        BizException ex = assertThrows(BizException.class,
                () -> service.copySquad(SQUAD_ID, SOURCE, USER, request(requestId, List.of(TARGET))));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertEquals("validation.field_invalid", ex.getMessageKey());
    }

    @Test
    void copySquad_requiresAtLeastOneTarget() {
        BizException ex = assertThrows(BizException.class,
                () -> service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of())));
        assertEquals(ErrorCode.SQUAD_COPY_TARGETS_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void copySquad_filtersNullTargetsAndDeduplicates() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER,
                request("req-1", Arrays.asList(null, TARGET, TARGET)));

        assertEquals(1, result.getResults().size());
        assertEquals(TARGET, result.getResults().get(0).getWorkspaceId());
        verify(targetService, times(1)).copyToTarget(any(), eq(TARGET), eq(USER), any(), any());
    }

    @Test
    void copySquad_rejectsMoreThanTwentyTargets() {
        List<Long> targets = new ArrayList<>();
        for (long i = 0; i < 21; i++) {
            targets.add(1000L + i);
        }
        BizException ex = assertThrows(BizException.class,
                () -> service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", targets)));
        assertEquals(ErrorCode.SQUAD_COPY_TOO_MANY_TARGETS.getCode(), ex.getCode());
        assertEquals("error.squad_copy_too_many_targets", ex.getMessageKey());
    }

    @Test
    void copySquad_rejectsFingerprintMismatchForReusedRequestId() {
        when(operationDao.listByRequest(SOURCE, USER, "req-1"))
                .thenReturn(List.of(record(TARGET, SquadCopyOperationDO.STATUS_SUCCESS, "other-fingerprint")));

        BizException ex = assertThrows(BizException.class,
                () -> service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET))));
        assertEquals(ErrorCode.SQUAD_COPY_FINGERPRINT_MISMATCH.getCode(), ex.getCode());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void copySquad_replaysStoredSuccessWithoutCreatingAgain() {
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setName("目标空间");
        when(workspaceDao.findById(TARGET)).thenReturn(workspace);
        SquadCopyOperationDO stored = record(TARGET, SquadCopyOperationDO.STATUS_SUCCESS,
                fingerprintOf(TARGET));
        stored.setTargetSquadId(888L);
        when(operationDao.listByRequest(SOURCE, USER, "req-1")).thenReturn(List.of(stored));

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        assertEquals(1, result.getResults().size());
        CopyTargetResultVO target = result.getResults().get(0);
        assertTrue(target.isSuccess());
        assertEquals(888L, target.getSquadId());
        assertEquals("目标空间", target.getWorkspaceName());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
        verify(operationDao, never()).insert(any());
    }

    @Test
    void copySquad_replaysStoredFailureWithOriginalCodeAndMessage() {
        when(workspaceDao.findById(TARGET)).thenReturn(null);
        SquadCopyOperationDO stored = record(TARGET, SquadCopyOperationDO.STATUS_FAILED,
                fingerprintOf(TARGET));
        stored.setErrorCode("35006");
        stored.setErrorMessage("复制依赖无法解析：skill#3");
        when(operationDao.listByRequest(SOURCE, USER, "req-1")).thenReturn(List.of(stored));

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        CopyTargetResultVO target = result.getResults().get(0);
        assertFalse(target.isSuccess());
        assertEquals("35006", target.getErrorCode());
        assertEquals("复制依赖无法解析：skill#3", target.getMessage());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void copySquad_replaysInProgressRecordAsInProgressFailure() {
        when(operationDao.listByRequest(SOURCE, USER, "req-1"))
                .thenReturn(List.of(record(TARGET, SquadCopyOperationDO.STATUS_IN_PROGRESS, fingerprintOf(TARGET))));

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        CopyTargetResultVO target = result.getResults().get(0);
        assertFalse(target.isSuccess());
        assertEquals(ErrorCode.SQUAD_COPY_IN_PROGRESS.getCode(), target.getErrorCode());
        assertEquals("同一复制请求正在处理中，请稍后重试", target.getMessage());
    }

    @Test
    void copySquad_replaysExistingResultAndExecutesPendingTargetInRequestOrder() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        WorkspaceDO other = new WorkspaceDO();
        other.setId(OTHER_TARGET);
        other.setName("另一空间");
        other.setStatus(0);
        other.setIsDeleted(0);
        when(workspaceDao.findById(OTHER_TARGET)).thenReturn(other);
        when(workspaceService.allowsCopyTarget(OTHER_TARGET, USER)).thenReturn(true);
        when(targetService.copyToTarget(any(), eq(OTHER_TARGET), eq(USER), any(), any())).thenReturn(777L);
        SquadCopyOperationDO stored = record(TARGET, SquadCopyOperationDO.STATUS_SUCCESS,
                fingerprintOf(TARGET, OTHER_TARGET));
        stored.setTargetSquadId(888L);
        when(operationDao.listByRequest(SOURCE, USER, "req-1")).thenReturn(List.of(stored));

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER,
                request("req-1", List.of(TARGET, OTHER_TARGET)));

        assertEquals(List.of(TARGET, OTHER_TARGET),
                result.getResults().stream().map(CopyTargetResultVO::getWorkspaceId).toList());
        assertEquals(888L, result.getResults().get(0).getSquadId());
        assertEquals(777L, result.getResults().get(1).getSquadId());
    }

    @Test
    void copySquad_blocksWholeRequestWhenAnyMemberLacksOnlineVersion() {
        stubSourceSquad();
        SquadMemberDO member = new SquadMemberDO();
        member.setSquadId(SQUAD_ID);
        member.setAgentId(9L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(member));
        AgentDO agent = new AgentDO();
        agent.setId(9L);
        agent.setName("草稿数字人");
        agent.setOnlineVersionId(null);
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(agent));

        BizException ex = assertThrows(BizException.class,
                () -> service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET))));
        assertEquals(ErrorCode.SQUAD_COPY_NO_ONLINE_VERSION.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("草稿数字人"), "消息应列出阻止复制的数字人");
        verify(operationDao, never()).insert(any());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void copySquad_recordsPerTargetFailureForInvalidTargetWithoutOperationRow() {
        stubSourceSquad();
        stubEligibleMember();
        // 目标等于源空间：非法目标，无操作记录、无复制调用。
        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(SOURCE)));

        CopyTargetResultVO target = result.getResults().get(0);
        assertFalse(target.isSuccess());
        assertEquals(ErrorCode.SQUAD_COPY_TARGET_INVALID.getCode(), target.getErrorCode());
        assertEquals("目标工作空间不存在或没有复制权限", target.getMessage());
        verify(operationDao, never()).insert(any());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void copySquad_rejectsDeletedOrDisabledTargetAsInvalid() {
        stubSourceSquad();
        stubEligibleMember();
        WorkspaceDO deleted = new WorkspaceDO();
        deleted.setId(TARGET);
        deleted.setName("已删除空间");
        deleted.setStatus(0);
        deleted.setIsDeleted(1);
        when(workspaceDao.findById(TARGET)).thenReturn(deleted);

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        assertFalse(result.getResults().get(0).isSuccess());
        assertEquals(ErrorCode.SQUAD_COPY_TARGET_INVALID.getCode(),
                result.getResults().get(0).getErrorCode());
    }

    @Test
    void copySquad_rejectsTargetWithoutCopyPermissionAsInvalid() {
        stubSourceSquad();
        stubEligibleMember();
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(TARGET);
        workspace.setName("只读空间");
        workspace.setStatus(0);
        workspace.setIsDeleted(0);
        when(workspaceDao.findById(TARGET)).thenReturn(workspace);
        when(workspaceService.allowsCopyTarget(TARGET, USER)).thenReturn(false);

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        assertFalse(result.getResults().get(0).isSuccess());
        assertEquals(ErrorCode.SQUAD_COPY_TARGET_INVALID.getCode(),
                result.getResults().get(0).getErrorCode());
    }

    @Test
    void copySquad_executesValidTargetInsertingInProgressRecordInTargetContext() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        AutoWonderContext.get().setCurrentWorkspaceId(SOURCE);
        List<Long> ctxDuringCopy = new ArrayList<>();
        when(targetService.copyToTarget(any(), eq(TARGET), eq(USER), any(), any()))
                .thenAnswer(invocation -> {
                    ctxDuringCopy.add(AutoWonderContext.get().getCurrentWorkspaceId());
                    return 999L;
                });

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        CopyTargetResultVO target = result.getResults().get(0);
        assertTrue(target.isSuccess());
        assertEquals(999L, target.getSquadId());
        assertEquals("目标空间", target.getWorkspaceName());
        assertEquals(List.of(TARGET), ctxDuringCopy, "复制必须在目标空间上下文内执行");
        assertEquals(SOURCE, AutoWonderContext.get().getCurrentWorkspaceId(), "执行后必须恢复原上下文");

        ArgumentCaptor<SquadCopyOperationDO> inserted = ArgumentCaptor.forClass(SquadCopyOperationDO.class);
        verify(operationDao).insert(inserted.capture());
        SquadCopyOperationDO row = inserted.getValue();
        assertEquals(SquadCopyOperationDO.STATUS_IN_PROGRESS, row.getStatus());
        assertEquals(SOURCE, row.getSourceWorkspaceId());
        assertEquals(SQUAD_ID, row.getSourceSquadId());
        assertEquals(USER, row.getUserId());
        assertEquals("req-1", row.getRequestId());
        assertEquals(TARGET, row.getTargetWorkspaceId());
        assertEquals(fingerprintOf(TARGET), row.getRequestFingerprint());
        assertNotNull(row.getSourceSnapshotRef());
    }

    @Test
    void copySquad_recordsTargetBizFailureAndRestoresContext() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        AutoWonderContext.get().setCurrentWorkspaceId(SOURCE);
        doAnswer(invocation -> {
            ((SquadCopyOperationDO) invocation.getArgument(0)).setId(31L);
            return null;
        }).when(operationDao).insert(any());
        when(targetService.copyToTarget(any(), eq(TARGET), eq(USER), any(), any()))
                .thenThrow(BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED,
                        "squad.copy.dependency_missing", new Object[]{"skill#3"}, "diag"));

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        CopyTargetResultVO target = result.getResults().get(0);
        assertFalse(target.isSuccess());
        assertEquals(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), target.getErrorCode());
        assertEquals("复制依赖无法解析：skill#3", target.getMessage());
        verify(operationDao).markFailed(31L, "35006", "复制依赖无法解析：skill#3", null);
        assertEquals(SOURCE, AutoWonderContext.get().getCurrentWorkspaceId(), "失败后也必须恢复原上下文");
    }

    @Test
    void copySquad_recordsUnexpectedFailureAsSystemError() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        doAnswer(invocation -> {
            ((SquadCopyOperationDO) invocation.getArgument(0)).setId(31L);
            return null;
        }).when(operationDao).insert(any());
        when(targetService.copyToTarget(any(), eq(TARGET), eq(USER), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        CopyTargetResultVO target = result.getResults().get(0);
        assertFalse(target.isSuccess());
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), target.getErrorCode());
        assertEquals("复制失败，请稍后重试", target.getMessage());
        verify(operationDao).markFailed(31L, "10000", "复制失败，请稍后重试", null);
    }

    @Test
    void copySquad_truncatesLongFailureMessageBeforePersisting() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        doAnswer(invocation -> {
            ((SquadCopyOperationDO) invocation.getArgument(0)).setId(31L);
            return null;
        }).when(operationDao).insert(any());
        when(targetService.copyToTarget(any(), eq(TARGET), eq(USER), any(), any()))
                .thenThrow(BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED,
                        "squad.copy.dependency_missing", new Object[]{"x".repeat(600)}, "diag"));

        service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(operationDao).markFailed(eq(31L), eq("35006"), message.capture(), eq(null));
        assertEquals(512, message.getValue().length());
    }

    @Test
    void copySquad_cleansUpPackageAndTracksSecretRefFromLedger() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        doAnswer(invocation -> {
            ((SquadCopyOperationDO) invocation.getArgument(0)).setId(31L);
            return null;
        }).when(operationDao).insert(any());
        when(targetService.copyToTarget(any(), eq(TARGET), eq(USER), any(), any()))
                .thenAnswer(invocation -> {
                    SquadCopyResourceLedger ledger = invocation.getArgument(4);
                    ledger.recordPackage("SKILL#打包能力", "bucket-x/t/100/skills/3/skill.zip");
                    ledger.recordSecretRef("MCP#github.headers.Authorization");
                    throw BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED,
                            "squad.copy.dependency_missing", new Object[]{"skill#4"}, "diag");
                });

        service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        verify(objectStorage).delete("bucket-x/t/100/skills/3/skill.zip");
        ArgumentCaptor<String> cleanup = ArgumentCaptor.forClass(String.class);
        verify(operationDao).markFailed(eq(31L), eq("35006"), any(), cleanup.capture());
        JSONObject root = JSON.parseObject(cleanup.getValue());
        JSONArray resources = root.getJSONArray("resources");
        assertEquals(2, resources.size());
        assertEquals("PACKAGE", resources.getJSONObject(0).getString("kind"));
        assertEquals("SKILL#打包能力", resources.getJSONObject(0).getString("label"));
        assertEquals("bucket-x/t/100/skills/3/skill.zip", resources.getJSONObject(0).getString("ossRef"));
        assertEquals("DELETED", resources.getJSONObject(0).getString("cleanup"));
        assertEquals("SECRET_REF", resources.getJSONObject(1).getString("kind"));
        assertEquals("MCP#github.headers.Authorization", resources.getJSONObject(1).getString("label"));
        assertEquals("TRACKED", resources.getJSONObject(1).getString("cleanup"));
    }

    @Test
    void copySquad_marksPackageDeleteFailedWhenCompensationThrows() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        doAnswer(invocation -> {
            ((SquadCopyOperationDO) invocation.getArgument(0)).setId(31L);
            return null;
        }).when(operationDao).insert(any());
        when(targetService.copyToTarget(any(), eq(TARGET), eq(USER), any(), any()))
                .thenAnswer(invocation -> {
                    SquadCopyResourceLedger ledger = invocation.getArgument(4);
                    ledger.recordPackage("SKILL#打包能力", "bucket-x/t/200/skills/9/skill.zip");
                    throw BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED,
                            "squad.copy.dependency_missing", new Object[]{"skill#4"}, "diag");
                });
        doThrow(new RuntimeException("storage down")).when(objectStorage).delete(any());

        service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        ArgumentCaptor<String> cleanup = ArgumentCaptor.forClass(String.class);
        verify(operationDao).markFailed(eq(31L), eq("35006"), any(), cleanup.capture());
        JSONObject resource = JSON.parseObject(cleanup.getValue())
                .getJSONArray("resources").getJSONObject(0);
        assertEquals("DELETE_FAILED", resource.getString("cleanup"));
    }

    @Test
    void copySquad_duplicateInsertRaceReplaysRacedSuccess() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        doThrow(new DuplicateKeyException("uk")).when(operationDao).insert(any());
        SquadCopyOperationDO raced = record(TARGET, SquadCopyOperationDO.STATUS_SUCCESS, fingerprintOf(TARGET));
        raced.setTargetSquadId(888L);
        when(operationDao.find(SOURCE, USER, "req-1", TARGET)).thenReturn(raced);

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        assertTrue(result.getResults().get(0).isSuccess());
        assertEquals(888L, result.getResults().get(0).getSquadId());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void copySquad_duplicateInsertRaceWithoutRecordReportsInProgress() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        doThrow(new DuplicateKeyException("uk")).when(operationDao).insert(any());
        when(operationDao.find(SOURCE, USER, "req-1", TARGET)).thenReturn(null);

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        CopyTargetResultVO target = result.getResults().get(0);
        assertFalse(target.isSuccess());
        assertEquals(ErrorCode.SQUAD_COPY_IN_PROGRESS.getCode(), target.getErrorCode());
    }

    @Test
    void copySquad_duplicateInsertRaceWithForeignFingerprintReportsInProgress() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        doThrow(new DuplicateKeyException("uk")).when(operationDao).insert(any());
        when(operationDao.find(SOURCE, USER, "req-1", TARGET))
                .thenReturn(record(TARGET, SquadCopyOperationDO.STATUS_IN_PROGRESS, "other"));

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        assertFalse(result.getResults().get(0).isSuccess());
        assertEquals(ErrorCode.SQUAD_COPY_IN_PROGRESS.getCode(),
                result.getResults().get(0).getErrorCode());
    }

    @Test
    void copySquad_sourceChangedBetweenCheckAndCopyFailsWholeRequest() {
        stubEligibleMember();
        SquadDO before = new SquadDO();
        before.setId(SQUAD_ID);
        before.setTenantId(SOURCE);
        before.setName("开发小队");
        SquadDO after = new SquadDO();
        after.setId(SQUAD_ID);
        after.setTenantId(SOURCE);
        after.setName("开发小队（已改名）");
        when(squadDao.findById(SQUAD_ID)).thenReturn(before, after);
        stubValidTargetCalls();

        BizException ex = assertThrows(BizException.class,
                () -> service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET))));
        assertEquals(ErrorCode.SQUAD_COPY_SOURCE_CHANGED.getCode(), ex.getCode());
        verify(operationDao, never()).insert(any());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void listCopyTargets_normalizesPagingAndMapsRows() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        when(workspaceDao.countCopyTargets(SOURCE, USER, false, "核心")).thenReturn(2L);
        WorkspaceDO first = new WorkspaceDO();
        first.setId(TARGET);
        first.setName("目标空间");
        first.setDescription("d1");
        WorkspaceDO second = new WorkspaceDO();
        second.setId(OTHER_TARGET);
        second.setName("另一空间");
        when(workspaceDao.listCopyTargets(SOURCE, USER, false, "核心", 0, 100))
                .thenReturn(List.of(first, second));

        PageResult<CopyTargetVO> result = service.listCopyTargets(SOURCE, USER, "  核心  ", -3, 500);

        assertEquals(1, result.getPageNum());
        assertEquals(100, result.getPageSize());
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getList().size());
        assertEquals("目标空间", result.getList().get(0).getName());
        assertEquals("d1", result.getList().get(0).getDescription());
        assertEquals(OTHER_TARGET, result.getList().get(1).getId());
    }

    @Test
    void listCopyTargets_blankKeywordBecomesNullAndDefaultsPaging() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(true);
        when(workspaceDao.countCopyTargets(SOURCE, USER, true, null)).thenReturn(0L);

        PageResult<CopyTargetVO> result = service.listCopyTargets(SOURCE, USER, "   ", 0, 0);

        assertEquals(1, result.getPageNum());
        assertEquals(20, result.getPageSize());
        assertTrue(result.getList().isEmpty());
        verify(workspaceDao, never()).listCopyTargets(anyLong(), anyLong(), anyBoolean(), any(), anyInt(), anyInt());
    }

    @Test
    void listCopyTargets_acceptsValidPageAndNullKeyword() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        when(workspaceDao.countCopyTargets(SOURCE, USER, false, null)).thenReturn(1L);
        WorkspaceDO row = new WorkspaceDO();
        row.setId(TARGET);
        row.setName("目标空间");
        when(workspaceDao.listCopyTargets(SOURCE, USER, false, null, 30, 30)).thenReturn(List.of(row));

        PageResult<CopyTargetVO> result = service.listCopyTargets(SOURCE, USER, null, 2, 30);

        assertEquals(2, result.getPageNum());
        assertEquals(30, result.getPageSize());
        assertEquals(1L, result.getTotal());
        assertEquals(TARGET, result.getList().get(0).getId());
    }

    @Test
    void checkCopy_missingSquadRowIsNotFound() {
        BizException ex = assertThrows(BizException.class, () -> service.checkCopy(SQUAD_ID, SOURCE));

        assertEquals(ErrorCode.SQUAD_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void copySquad_blocksWhenAgentRowMissingOrOnlineAgentHasNoName() {
        stubSourceSquad();
        SquadMemberDO member9 = new SquadMemberDO();
        member9.setSquadId(SQUAD_ID);
        member9.setAgentId(9L);
        SquadMemberDO member10 = new SquadMemberDO();
        member10.setSquadId(SQUAD_ID);
        member10.setAgentId(10L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(member9, member10));
        AgentDO namelessAgent = new AgentDO();
        namelessAgent.setId(9L);
        namelessAgent.setTenantId(SOURCE);
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(namelessAgent));

        BizException ex = assertThrows(BizException.class,
                () -> service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET))));

        assertEquals(ErrorCode.SQUAD_COPY_NO_ONLINE_VERSION.getCode(), ex.getCode());
        assertEquals("小队存在没有在线版本的数字员工: agent#9", ex.getMessage());
        verify(operationDao, never()).insert(any());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void copySquad_marksAbsentAndDisabledTargetsInvalid() {
        stubSourceSquad();
        stubEligibleMember();
        WorkspaceDO disabled = new WorkspaceDO();
        disabled.setId(OTHER_TARGET);
        disabled.setName("停用空间");
        disabled.setStatus(1);
        disabled.setIsDeleted(0);
        when(workspaceDao.findById(OTHER_TARGET)).thenReturn(disabled);

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER,
                request("req-1", List.of(TARGET, OTHER_TARGET)));

        assertEquals(List.of(ErrorCode.SQUAD_COPY_TARGET_INVALID.getCode(),
                        ErrorCode.SQUAD_COPY_TARGET_INVALID.getCode()),
                result.getResults().stream().map(CopyTargetResultVO::getErrorCode).toList());
        verify(operationDao, never()).insert(any());
        verify(targetService, never()).copyToTarget(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void copySquad_snapshotWithoutMembersSkipsDependentLookups() {
        stubSourceSquad();
        stubValidTargetCalls();

        service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        ArgumentCaptor<CopySourceSnapshot> snapshotCaptor = ArgumentCaptor.forClass(CopySourceSnapshot.class);
        verify(targetService).copyToTarget(snapshotCaptor.capture(), eq(TARGET), eq(USER), any(), any());
        CopySourceSnapshot snapshot = snapshotCaptor.getValue();
        assertEquals(5L, snapshot.squad().squadId());
        assertTrue(snapshot.agents().isEmpty());
        assertTrue(snapshot.capabilities().isEmpty());
        assertTrue(snapshot.sdlcs().isEmpty());
        assertTrue(snapshot.envVars().isEmpty());
        assertTrue(snapshot.executors().isEmpty());
        verify(agentDao, never()).listByIds(anyLong(), any());
        verify(versionDao, never()).listByIds(anyLong(), any());
        verify(agentSkillDao, never()).listByVersion(any());
        verify(executorDao, never()).listByAgent(anyLong(), any());
    }

    @Test
    void copySquad_buildsFullSnapshotFromEverySourceCollection() {
        stubSourceSquad();
        SquadMemberDO member9 = new SquadMemberDO();
        member9.setSquadId(SQUAD_ID);
        member9.setAgentId(9L);
        SquadMemberDO member9Duplicate = new SquadMemberDO();
        member9Duplicate.setSquadId(SQUAD_ID);
        member9Duplicate.setAgentId(9L);
        SquadMemberDO memberWithoutAgent = new SquadMemberDO();
        memberWithoutAgent.setSquadId(SQUAD_ID);
        SquadMemberDO member12 = new SquadMemberDO();
        member12.setSquadId(SQUAD_ID);
        member12.setAgentId(12L);
        SquadMemberDO member13 = new SquadMemberDO();
        member13.setSquadId(SQUAD_ID);
        member13.setAgentId(13L);
        when(memberDao.listBySquad(SQUAD_ID)).thenReturn(List.of(
                member9, member9Duplicate, memberWithoutAgent, member12, member13));
        when(agentDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(
                agentRow(9L, "后端数字人", 90L), agentRow(12L, "前端数字人", 91L),
                agentRow(13L, "测试数字人", 92L)));
        AgentVersionDO version90 = versionRow(90L, 9L, 1, 21L);
        version90.setRoleName("后端工程师");
        version90.setRoleCode("AW_BE_DEV");
        version90.setBusinessBackground("背景");
        version90.setResponsibilities("职责");
        version90.setIdentityJson("{}");
        when(versionDao.listByIds(eq(SOURCE), any())).thenReturn(List.of(
                version90, versionRow(91L, 12L, 2, 22L), versionRow(92L, 13L, 1, null)));
        when(agentSkillDao.listByVersion(90L)).thenReturn(List.of(
                skillRef(31L), skillRef(31L), skillRef(null)));
        when(agentSkillDao.listByVersion(91L)).thenReturn(List.of(skillRef(32L), skillRef(33L)));
        when(environmentVariableRefDao.listByVersion(SOURCE, 90L)).thenReturn(List.of(
                envRef(41L), envRef(41L), envRef(null)));
        when(environmentVariableRefDao.listByVersion(SOURCE, 91L)).thenReturn(List.of(envRef(42L)));
        SkillDO capabilitySkill = new SkillDO();
        capabilitySkill.setId(31L);
        capabilitySkill.setType("SKILL");
        capabilitySkill.setName("能力A");
        capabilitySkill.setInstallSpec("spec-a");
        capabilitySkill.setSourceType("LOCAL");
        capabilitySkill.setPackageMd5("md5-a");
        when(skillDao.findById(31L)).thenReturn(capabilitySkill);
        SkillDO pluginSkill = new SkillDO();
        pluginSkill.setId(33L);
        pluginSkill.setType("PLUGIN");
        pluginSkill.setName("插件B");
        when(skillDao.findById(33L)).thenReturn(pluginSkill);
        EnvironmentVariableDO variable41 = new EnvironmentVariableDO();
        variable41.setId(41L);
        variable41.setName("API_TOKEN");
        variable41.setCredentialRef("kc:env-41");
        when(environmentVariableDao.findActiveById(SOURCE, 41L)).thenReturn(variable41);
        SdlcDO sdlc21 = new SdlcDO();
        sdlc21.setId(21L);
        sdlc21.setName("标准流程");
        sdlc21.setWorkType("REQ");
        sdlc21.setStatus("ENABLED");
        sdlc21.setIsDefault(1);
        sdlc21.setEntryStepId(501L);
        when(sdlcDao.findById(21L)).thenReturn(sdlc21);
        SdlcStepDO step501 = new SdlcStepDO();
        step501.setId(501L);
        step501.setStepOrder(0);
        step501.setName("分析");
        step501.setKind("TASK");
        step501.setCode("ANALYZE");
        step501.setHandlerType("AGENT");
        step501.setHandlerRoleRef("AW_PM");
        step501.setStatusOnEnterCode("IN_PROGRESS");
        step501.setOnSuccess("NEXT");
        step501.setOnFail("STOP");
        when(stepDao.listBySdlc(21L)).thenReturn(List.of(step501));
        ExecutorDO executor21 = new ExecutorDO();
        executor21.setId(21L);
        executor21.setAgentId(9L);
        executor21.setName("执行器A");
        executor21.setClientKind("QODER_CLI");
        executor21.setLaunchConfig("{\"model\":\"qmodel\"}");
        when(executorDao.listByAgent(SOURCE, 9L)).thenReturn(List.of(executor21));
        when(executorDao.listByAgent(SOURCE, 12L)).thenReturn(List.of());
        stubValidTargetCalls();

        service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        ArgumentCaptor<CopySourceSnapshot> snapshotCaptor = ArgumentCaptor.forClass(CopySourceSnapshot.class);
        verify(targetService).copyToTarget(snapshotCaptor.capture(), eq(TARGET), eq(USER), any(), any());
        CopySourceSnapshot snapshot = snapshotCaptor.getValue();
        assertEquals("开发小队", snapshot.squad().name());
        assertEquals(List.of(9L, 12L, 13L), snapshot.agents().stream()
                .map(CopySourceSnapshot.AgentSnapshot::agentId).toList());
        CopySourceSnapshot.AgentSnapshot firstAgent = snapshot.agents().get(0);
        assertEquals(90L, firstAgent.onlineVersionId());
        assertEquals(1, firstAgent.onlineVersionNo());
        assertEquals("后端工程师", firstAgent.roleName());
        assertEquals(List.of(31L), firstAgent.skillIds());
        assertEquals(List.of(41L), firstAgent.envVarIds());
        assertEquals(21L, firstAgent.sdlcId());
        assertEquals(List.of(32L, 33L), snapshot.agents().get(1).skillIds());
        assertEquals(List.of(42L), snapshot.agents().get(1).envVarIds());
        assertEquals(22L, snapshot.agents().get(1).sdlcId());
        assertNull(snapshot.agents().get(2).sdlcId());
        assertEquals(1, snapshot.capabilities().size());
        assertEquals(31L, snapshot.capabilities().get(0).skillId());
        assertEquals("md5-a", snapshot.capabilities().get(0).packageMd5());
        assertEquals(1, snapshot.envVars().size());
        assertEquals(41L, snapshot.envVars().get(0).envVarId());
        assertEquals("kc:env-41", snapshot.envVars().get(0).credentialRef());
        assertEquals(1, snapshot.sdlcs().size());
        assertEquals(501L, snapshot.sdlcs().get(0).entryStepId());
        assertEquals(1, snapshot.sdlcs().get(0).steps().size());
        assertEquals("ANALYZE", snapshot.sdlcs().get(0).steps().get(0).code());
        assertEquals(1, snapshot.executors().size());
        assertEquals("QODER_CLI", snapshot.executors().get(0).clientKind());
        assertEquals(List.of("skill#32", "plugin#插件B", "environment_variable#42", "sdlc#22"),
                snapshot.missingDependencies());
    }

    @Test
    void copySquad_toleratesFailureRecordUpdateError() {
        stubSourceSquad();
        stubEligibleMember();
        stubValidTargetCalls();
        doAnswer(invocation -> {
            ((SquadCopyOperationDO) invocation.getArgument(0)).setId(31L);
            return null;
        }).when(operationDao).insert(any());
        when(targetService.copyToTarget(any(), eq(TARGET), eq(USER), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(operationDao.markFailed(eq(31L), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        CopySquadResultVO result = service.copySquad(SQUAD_ID, SOURCE, USER, request("req-1", List.of(TARGET)));

        CopyTargetResultVO target = result.getResults().get(0);
        assertFalse(target.isSuccess());
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), target.getErrorCode());
        assertEquals("复制失败，请稍后重试", target.getMessage());
    }

    private static AgentDO agentRow(long id, String name, Long onlineVersionId) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setTenantId(SOURCE);
        agent.setName(name);
        agent.setAvatarUrl("avatar-" + id + ".png");
        agent.setOnlineVersionId(onlineVersionId);
        return agent;
    }

    private static AgentVersionDO versionRow(long id, long agentId, int versionNo, Long sdlcId) {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(id);
        version.setAgentId(agentId);
        version.setVersionNo(versionNo);
        version.setStatus("APPROVED");
        version.setSdlcId(sdlcId);
        return version;
    }

    private static AgentSkillDO skillRef(Long skillId) {
        AgentSkillDO ref = new AgentSkillDO();
        ref.setSkillId(skillId);
        return ref;
    }

    private static AgentEnvironmentVariableRefDO envRef(Long envVariableId) {
        AgentEnvironmentVariableRefDO ref = new AgentEnvironmentVariableRefDO();
        ref.setEnvironmentVariableId(envVariableId);
        return ref;
    }
}
