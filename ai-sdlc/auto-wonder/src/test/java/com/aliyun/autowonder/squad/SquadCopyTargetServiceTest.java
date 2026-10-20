package com.aliyun.autowonder.squad;

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
import com.aliyun.autowonder.environment.EnvironmentVariableDO;
import com.aliyun.autowonder.environment.EnvironmentVariableDao;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.TokenService;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.squad.dto.CopySourceSnapshot;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SquadCopyTargetServiceTest {

    private static final long TARGET = 200L;
    private static final long USER = 7L;
    private static final long OPERATION_ID = 31L;

    SquadCopyOperationDao operationDao;
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
    TokenService tokenService;
    ObjectStorage objectStorage;
    SecretCrypto secretCrypto;

    SquadCopyTargetService service;
    SquadCopyResourceLedger ledger;
    SquadCopyOperationDO operation;

    @BeforeEach
    void setUp() {
        operationDao = mock(SquadCopyOperationDao.class);
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
        tokenService = mock(TokenService.class);
        objectStorage = mock(ObjectStorage.class);
        secretCrypto = mock(SecretCrypto.class);
        service = new SquadCopyTargetService(operationDao, squadDao, memberDao, agentDao, versionDao,
                agentSkillDao, environmentVariableRefDao, sdlcDao, stepDao, skillDao,
                environmentVariableDao, executorDao, tokenService, objectStorage, secretCrypto);
        ledger = new SquadCopyResourceLedger();
        operation = new SquadCopyOperationDO();
        operation.setId(OPERATION_ID);
        operation.setStatus(SquadCopyOperationDO.STATUS_IN_PROGRESS);
    }

    private static CopySourceSnapshot.SourceSquad squadSource(String name, Boolean debugLogEnabled) {
        return new CopySourceSnapshot.SourceSquad(5L, name, "小队描述", debugLogEnabled);
    }

    private static CopySourceSnapshot.AgentSnapshot agentSnapshot(Long agentId, String name, Long sdlcId,
                                                                  List<Long> skillIds, List<Long> envVarIds) {
        return new CopySourceSnapshot.AgentSnapshot(agentId, name, "avatar.png", 90L, 1,
                "后端工程师", "AW_FS_DEV", "背景", "职责", null, sdlcId, skillIds, envVarIds);
    }

    private static CopySourceSnapshot snapshot(
            CopySourceSnapshot.SourceSquad squad,
            List<CopySourceSnapshot.AgentSnapshot> agents,
            List<CopySourceSnapshot.SdlcSnapshot> sdlcs,
            List<CopySourceSnapshot.CapabilitySnapshot> capabilities,
            List<CopySourceSnapshot.EnvVarSnapshot> envVars,
            List<CopySourceSnapshot.ExecutorSnapshot> executors,
            List<String> missingDependencies) {
        return new CopySourceSnapshot(squad, agents, sdlcs, capabilities, envVars, executors,
                missingDependencies);
    }

    private static CopySourceSnapshot emptySnapshot() {
        return snapshot(squadSource("开发小队", null), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    private void stubSquadInsert(long newSquadId) {
        when(squadDao.countByTenantAndName(TARGET, "开发小队")).thenReturn(0);
        doAnswer(invocation -> {
            ((SquadDO) invocation.getArgument(0)).setId(newSquadId);
            return null;
        }).when(squadDao).insert(any(SquadDO.class));
    }

    private static BizException assertBizException(Executable executable) {
        return assertThrows(BizException.class, executable::execute);
    }

    private void assertKeyed(BizException ex, String code, String key) {
        assertEquals(code, ex.getCode());
        assertEquals(key, ex.getMessageKey());
    }

    @Test
    void copyToTarget_rejectsMissingDependenciesBeforeAnyWrite() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of("skill#3", "plugin#github"));

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.dependency_missing");
        assertEquals("复制依赖无法解析：skill#3、plugin#github", ex.getMessage());
        verifyNoInteractions(squadDao, skillDao, agentDao, objectStorage);
        verify(operationDao, never()).markSuccess(anyLong(), anyLong());
    }

    @Test
    void copyToTarget_copiesSquadAndMembersAndMarksSuccessInOrder() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());
        stubSquadInsert(700L);
        when(agentDao.findByExactName(TARGET, "后端数字人")).thenReturn(List.of());
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        Long squadId = service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        assertEquals(700L, squadId);
        ArgumentCaptor<AgentDO> agentCaptor = ArgumentCaptor.forClass(AgentDO.class);
        verify(agentDao).insert(agentCaptor.capture());
        AgentDO agent = agentCaptor.getValue();
        assertEquals(TARGET, agent.getTenantId());
        assertEquals("后端数字人", agent.getName());
        assertEquals("DRAFT", agent.getStatus());
        assertEquals("STANDARD", agent.getKind());
        assertEquals("avatar.png", agent.getAvatarUrl());

        ArgumentCaptor<AgentVersionDO> versionCaptor = ArgumentCaptor.forClass(AgentVersionDO.class);
        verify(versionDao).insert(versionCaptor.capture());
        AgentVersionDO version = versionCaptor.getValue();
        assertEquals(TARGET, version.getTenantId());
        assertEquals(555L, version.getAgentId());
        assertEquals(1, version.getVersionNo());
        assertEquals("APPROVED", version.getStatus());
        assertEquals("后端工程师", version.getRoleName());
        assertNull(version.getSdlcId());
        assertNotNull(version.getIdentityJson());
        assertTrue(version.getIdentityJson().contains("\"name\":\"后端数字人\""));
        assertTrue(version.getIdentityJson().contains("\"evolutionMode\":\"ASSISTED\""),
                "identityJson 缺失时 evolutionMode 取默认值");

        ArgumentCaptor<SquadDO> squadCaptor = ArgumentCaptor.forClass(SquadDO.class);
        verify(squadDao).insert(squadCaptor.capture());
        SquadDO squad = squadCaptor.getValue();
        assertEquals("开发小队", squad.getName());
        assertEquals("小队描述", squad.getDescription());
        assertEquals(TARGET, squad.getTenantId());
        assertNull(squad.getOwnerId(), "副本不设置负责人");
        verify(squadDao, never()).update(anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong());

        ArgumentCaptor<SquadMemberDO> memberCaptor = ArgumentCaptor.forClass(SquadMemberDO.class);
        verify(memberDao).insert(memberCaptor.capture());
        assertEquals(700L, memberCaptor.getValue().getSquadId());
        assertEquals(555L, memberCaptor.getValue().getAgentId());
        assertEquals(TARGET, memberCaptor.getValue().getTenantId());
        verify(agentSkillDao, never()).insert(any());
        verify(environmentVariableRefDao, never()).insert(any());
        verify(operationDao).markSuccess(OPERATION_ID, 700L);
        verifyNoInteractions(objectStorage);

        InOrder inOrder = inOrder(squadDao, memberDao, operationDao);
        inOrder.verify(squadDao).insert(any(SquadDO.class));
        inOrder.verify(memberDao).insert(any(SquadMemberDO.class));
        inOrder.verify(operationDao).markSuccess(OPERATION_ID, 700L);
    }

    @Test
    void copyToTarget_failsSystemErrorWhenSuccessRecordGuardRejects() {
        CopySourceSnapshot snapshot = emptySnapshot();
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(0);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void copyToTarget_debugLogEnabledTrueUpdatesSquadWithItsRealName() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", Boolean.TRUE), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        stubSquadInsert(700L);
        when(squadDao.update(700L, TARGET, "开发小队", "小队描述", null, Boolean.TRUE, 0, USER)).thenReturn(1);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        verify(squadDao).update(700L, TARGET, "开发小队", "小队描述", null, Boolean.TRUE, 0, USER);
        verify(operationDao).markSuccess(OPERATION_ID, 700L);
    }

    @Test
    void copyToTarget_debugLogUpdateGuardRejectsFailsSystemError() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", Boolean.TRUE), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        stubSquadInsert(700L);
        when(squadDao.update(700L, TARGET, "开发小队", "小队描述", null, Boolean.TRUE, 0, USER)).thenReturn(0);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void copyToTarget_skillPackageCopiedToTargetPathAndTrackedInLedger() {
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                3L, "SKILL", "打包能力", "{\"command\":\"run\"}", "能力描述", "PACKAGE",
                "bucket-a/t/100/skills/3/skill.zip", "pkg.zip", 10L, "md5-old");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(3L), List.of())),
                List.of(), List.of(capability), List.of(), List.of(), List.of());
        byte[] bytes = {1, 2, 3};
        when(objectStorage.get("bucket-a/t/100/skills/3/skill.zip")).thenReturn(bytes);
        when(skillDao.releaseSoftDeletedName(TARGET, "SKILL", "打包能力")).thenReturn(1);
        doAnswer(invocation -> {
            ((SkillDO) invocation.getArgument(0)).setId(88L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        StoredObject stored = new StoredObject("bucket-a/t/200/skills/88/skill.zip", "md5-new", 3L);
        when(objectStorage.put("bucket-a", "t/200/skills/88/skill.zip", bytes)).thenReturn(stored);
        when(skillDao.updatePackage(88L, TARGET, "SKILL", "{\"command\":\"run\"}", "打包能力", "能力描述",
                "PACKAGE", "bucket-a/t/200/skills/88/skill.zip", "pkg.zip", 3L, "md5-new", 0, USER)).thenReturn(1);
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        verify(skillDao).releaseSoftDeletedName(TARGET, "SKILL", "打包能力");
        verify(skillDao).updatePackage(88L, TARGET, "SKILL", "{\"command\":\"run\"}", "打包能力", "能力描述",
                "PACKAGE", "bucket-a/t/200/skills/88/skill.zip", "pkg.zip", 3L, "md5-new", 0, USER);
        ArgumentCaptor<AgentSkillDO> refCaptor = ArgumentCaptor.forClass(AgentSkillDO.class);
        verify(agentSkillDao).insert(refCaptor.capture());
        assertEquals(88L, refCaptor.getValue().getSkillId(), "能力引用必须指向目标空间新技能");
        assertEquals(901L, refCaptor.getValue().getAgentVersionId());
        assertEquals(TARGET, refCaptor.getValue().getTenantId());
        assertEquals(1, ledger.entries().size());
        assertEquals(SquadCopyResourceLedger.KIND_PACKAGE, ledger.entries().get(0).kind());
        assertEquals("SKILL#打包能力", ledger.entries().get(0).label());
        assertEquals("bucket-a/t/200/skills/88/skill.zip", ledger.entries().get(0).ossRef());
    }

    @Test
    void copyToTarget_missingPackageBytesFailsPackageUnavailable() {
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                3L, "SKILL", "打包能力", "{}", null, "PACKAGE",
                "bucket-a/t/100/skills/3/skill.zip", "pkg.zip", 10L, "md5-old");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        when(objectStorage.get("bucket-a/t/100/skills/3/skill.zip")).thenReturn(null);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.package_unavailable");
        assertEquals("能力安装包内容无法读取：SKILL#打包能力", ex.getMessage());
        verify(skillDao, never()).insert(any());
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void copyToTarget_packageReadErrorFailsPackageUnavailable() {
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                3L, "SKILL", "打包能力", "{}", null, "PACKAGE",
                "bucket-a/t/100/skills/3/skill.zip", "pkg.zip", 10L, "md5-old");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        when(objectStorage.get(anyString())).thenThrow(new IllegalStateException("oss down"));

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.package_unavailable");
    }

    @Test
    void copyToTarget_packageWithoutBucketPrefixFailsPackageUnavailable() {
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                3L, "SKILL", "打包能力", "{}", null, "PACKAGE",
                "skill.zip", "pkg.zip", 10L, "md5-old");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        when(objectStorage.get("skill.zip")).thenReturn(new byte[]{1});

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.package_unavailable");
        verify(objectStorage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void copyToTarget_mcpInstallSpecRewritesSecretRefsToIndependentTargetRefs() {
        String installSpec = "{\"command\":\"npx\",\"headers\":{"
                + "\"Authorization\":{\"kind\":\"secretRef\",\"ref\":\"kc:old\"},"
                + "\"X-Plain\":{\"kind\":\"literal\",\"value\":\"v\"}},"
                + "\"env\":{\"TOKEN\":{\"kind\":\"secretRef\",\"ref\":\"kc:env-old\"},\"PLAIN\":\"p\"}}";
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                4L, "MCP", "github-mcp", installSpec, null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        when(secretCrypto.decrypt("kc:old")).thenReturn("plain-secret");
        when(secretCrypto.encrypt("plain-secret")).thenReturn("kc:new-1");
        when(secretCrypto.decrypt("kc:env-old")).thenReturn("plain-env");
        when(secretCrypto.encrypt("plain-env")).thenReturn("kc:new-2");
        doAnswer(invocation -> {
            ((SkillDO) invocation.getArgument(0)).setId(88L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<SkillDO> skillCaptor = ArgumentCaptor.forClass(SkillDO.class);
        verify(skillDao).insert(skillCaptor.capture());
        String rewritten = skillCaptor.getValue().getInstallSpec();
        assertFalse(rewritten.contains("kc:old"));
        assertFalse(rewritten.contains("kc:env-old"));
        assertFalse(rewritten.contains("plain-secret"), "明文不得进入目标配置");
        assertFalse(rewritten.contains("plain-env"), "明文不得进入目标配置");
        assertEquals("kc:new-1", JSON.parseObject(rewritten).getJSONObject("headers")
                .getJSONObject("Authorization").getString("ref"));
        assertEquals("kc:new-2", JSON.parseObject(rewritten).getJSONObject("env")
                .getJSONObject("TOKEN").getString("ref"));
        assertEquals("v", JSON.parseObject(rewritten).getJSONObject("headers")
                .getJSONObject("X-Plain").getString("value"));
        assertEquals("p", JSON.parseObject(rewritten).getJSONObject("env").getString("PLAIN"));
        List<String> labels = ledger.entries().stream().map(SquadCopyResourceLedger.Entry::label).toList();
        assertEquals(List.of("MCP#github-mcp.headers.Authorization", "MCP#github-mcp.env.TOKEN"), labels);
        assertTrue(ledger.entries().stream()
                .allMatch(entry -> SquadCopyResourceLedger.KIND_SECRET_REF.equals(entry.kind())));
    }

    @Test
    void copyToTarget_mcpSecretUnresolvableFailsWithKeyedError() {
        String installSpec = "{\"headers\":{\"Authorization\":{\"kind\":\"secretRef\",\"ref\":\"kc:old\"}}}";
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                4L, "MCP", "github-mcp", installSpec, null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        when(secretCrypto.decrypt("kc:old")).thenThrow(new IllegalStateException("kc down"));

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.secret_unresolvable");
        assertEquals("私密配置无法重新加密：MCP#github-mcp.headers.Authorization", ex.getMessage());
        verify(skillDao, never()).insert(any());
    }

    @Test
    void copyToTarget_mcpSecretBlanksFailsWithKeyedError() {
        String installSpec = "{\"headers\":{\"Authorization\":{\"kind\":\"secretRef\",\"ref\":\"kc:old\"}}}";
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                4L, "MCP", "github-mcp", installSpec, null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        when(secretCrypto.decrypt("kc:old")).thenReturn("   ");

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.secret_unresolvable");
    }

    @Test
    void copyToTarget_withoutSecretCryptoFailsSecretInsteadOfCopyingPlaintext() {
        SquadCopyTargetService noKeyCenterService = new SquadCopyTargetService(operationDao, squadDao,
                memberDao, agentDao, versionDao, agentSkillDao, environmentVariableRefDao, sdlcDao,
                stepDao, skillDao, environmentVariableDao, executorDao, tokenService, objectStorage, null);
        String installSpec = "{\"headers\":{\"Authorization\":{\"kind\":\"secretRef\",\"ref\":\"kc:old\"}}}";
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                4L, "MCP", "github-mcp", installSpec, null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());

        BizException ex = assertBizException(
                () -> noKeyCenterService.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.secret_unresolvable");
    }

    @Test
    void copyToTarget_nonMcpAndUnparseableInstallSpecsCopiedVerbatim() {
        CopySourceSnapshot.CapabilitySnapshot skill = new CopySourceSnapshot.CapabilitySnapshot(
                3L, "SKILL", "普通能力", "plain-text-spec", null, null, null, null, null, null);
        CopySourceSnapshot.CapabilitySnapshot mcp = new CopySourceSnapshot.CapabilitySnapshot(
                4L, "MCP", "坏配置mcp", "not-json", null, null, null, null, null, null);
        CopySourceSnapshot.CapabilitySnapshot mcpNull = new CopySourceSnapshot.CapabilitySnapshot(
                5L, "MCP", "空配置mcp", null, null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(skill, mcp, mcpNull), List.of(), List.of(), List.of());
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<SkillDO> skillCaptor = ArgumentCaptor.forClass(SkillDO.class);
        verify(skillDao, times(3)).insert(skillCaptor.capture());
        assertEquals("plain-text-spec", skillCaptor.getAllValues().get(0).getInstallSpec());
        assertEquals("not-json", skillCaptor.getAllValues().get(1).getInstallSpec());
        assertNull(skillCaptor.getAllValues().get(2).getInstallSpec());
        verifyNoInteractions(secretCrypto);
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void copyToTarget_envVarCredentialReencryptedAndBlankKeptEmpty() {
        CopySourceSnapshot.EnvVarSnapshot secretVar = new CopySourceSnapshot.EnvVarSnapshot(
                11L, "API_TOKEN", "令牌", "kc:env");
        CopySourceSnapshot.EnvVarSnapshot blankVar = new CopySourceSnapshot.EnvVarSnapshot(
                12L, "PLAIN_VAR", null, "   ");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(), List.of(11L, 12L))),
                List.of(), List.of(), List.of(secretVar, blankVar), List.of(), List.of());
        when(secretCrypto.decrypt("kc:env")).thenReturn("plain-token");
        when(secretCrypto.encrypt("plain-token")).thenReturn("kc:env-new");
        doAnswer(invocation -> {
            EnvironmentVariableDO variable = invocation.getArgument(0);
            variable.setId("API_TOKEN".equals(variable.getName()) ? 111L : 112L);
            return 1;
        }).when(environmentVariableDao).insert(any(EnvironmentVariableDO.class));
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<EnvironmentVariableDO> varCaptor = ArgumentCaptor.forClass(EnvironmentVariableDO.class);
        verify(environmentVariableDao, times(2)).insert(varCaptor.capture());
        EnvironmentVariableDO copiedSecret = varCaptor.getAllValues().get(0);
        assertEquals("API_TOKEN", copiedSecret.getName());
        assertEquals("kc:env-new", copiedSecret.getCredentialRef());
        assertEquals(TARGET, copiedSecret.getTenantId());
        assertNull(varCaptor.getAllValues().get(1).getCredentialRef(), "空值源凭证保持为空");
        ArgumentCaptor<AgentEnvironmentVariableRefDO> refCaptor =
                ArgumentCaptor.forClass(AgentEnvironmentVariableRefDO.class);
        verify(environmentVariableRefDao, times(2)).insert(refCaptor.capture());
        assertEquals(List.of(111L, 112L), refCaptor.getAllValues().stream()
                .map(AgentEnvironmentVariableRefDO::getEnvironmentVariableId).toList());
        assertEquals(1, ledger.entries().size());
        assertEquals(SquadCopyResourceLedger.KIND_SECRET_REF, ledger.entries().get(0).kind());
        assertEquals("environment_variable#API_TOKEN", ledger.entries().get(0).label());
    }

    @Test
    void copyToTarget_envVarUnresolvableSecretFailsBeforeCreate() {
        CopySourceSnapshot.EnvVarSnapshot secretVar = new CopySourceSnapshot.EnvVarSnapshot(
                11L, "API_TOKEN", null, "kc:env");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(), List.of(secretVar), List.of(), List.of());
        when(secretCrypto.decrypt("kc:env")).thenReturn(null);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.env_value_unresolvable");
        assertEquals("环境变量私密值无法重新加密：environment_variable#API_TOKEN", ex.getMessage());
        verify(environmentVariableDao, never()).insert(any());
    }

    @Test
    void copyToTarget_envVarConflictingNameUsesUnderscoreSuffix() {
        CopySourceSnapshot.EnvVarSnapshot var = new CopySourceSnapshot.EnvVarSnapshot(
                11L, "API_TOKEN", null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(), List.of(var), List.of(), List.of());
        EnvironmentVariableDO existing = new EnvironmentVariableDO();
        when(environmentVariableDao.findActiveByNameIgnoreCase(TARGET, "API_TOKEN")).thenReturn(existing);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<EnvironmentVariableDO> varCaptor = ArgumentCaptor.forClass(EnvironmentVariableDO.class);
        verify(environmentVariableDao).insert(varCaptor.capture());
        assertEquals("API_TOKEN_copy", varCaptor.getValue().getName());
    }

    @Test
    void copyToTarget_envVarDuplicateKeyRaceRetriesWithNextSuffix() {
        CopySourceSnapshot.EnvVarSnapshot var = new CopySourceSnapshot.EnvVarSnapshot(
                11L, "API_TOKEN", null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(), List.of(var), List.of(), List.of());
        // 入参 DO 在重试间被复用，须在调用当刻记录名称，捕获器只会拿到最终可变状态。
        List<String> attemptedNames = new ArrayList<>();
        doAnswer(invocation -> {
            attemptedNames.add(((EnvironmentVariableDO) invocation.getArgument(0)).getName());
            if (attemptedNames.size() < 3) {
                throw new DuplicateKeyException("uk");
            }
            return 1;
        }).when(environmentVariableDao).insert(any(EnvironmentVariableDO.class));
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        verify(environmentVariableDao, times(3)).insert(any(EnvironmentVariableDO.class));
        assertEquals(List.of("API_TOKEN", "API_TOKEN_copy", "API_TOKEN_copy_2"), attemptedNames);
    }

    @Test
    void copyToTarget_envVarNameExhaustionThrowsConflict() {
        CopySourceSnapshot.EnvVarSnapshot var = new CopySourceSnapshot.EnvVarSnapshot(
                11L, "API_TOKEN", null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(), List.of(var), List.of(), List.of());
        when(environmentVariableDao.findActiveByNameIgnoreCase(eq(TARGET), anyString()))
                .thenReturn(new EnvironmentVariableDO());

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.CONFLICT.getCode(), "error.conflict");
        verify(environmentVariableDao, never()).insert(any());
    }

    @Test
    void copyToTarget_longEnvVarNameTruncatedSoSuffixFitsIdentifierLimit() {
        String longName = "A".repeat(128);
        CopySourceSnapshot.EnvVarSnapshot var = new CopySourceSnapshot.EnvVarSnapshot(11L, longName, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(), List.of(var), List.of(), List.of());
        EnvironmentVariableDO existing = new EnvironmentVariableDO();
        when(environmentVariableDao.findActiveByNameIgnoreCase(TARGET, longName)).thenReturn(existing);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<EnvironmentVariableDO> varCaptor = ArgumentCaptor.forClass(EnvironmentVariableDO.class);
        verify(environmentVariableDao).insert(varCaptor.capture());
        String name = varCaptor.getValue().getName();
        assertEquals(128, name.length());
        assertTrue(name.endsWith("_copy"));
        assertTrue(name.matches("[A-Za-z_][A-Za-z0-9_]*"), "环境变量名必须保持运行期标识符形态");
    }

    @Test
    void copyToTarget_squadNameConflictAppendsSuffix() {
        CopySourceSnapshot snapshot = emptySnapshot();
        when(squadDao.countByTenantAndName(TARGET, "开发小队")).thenReturn(1);
        when(squadDao.countByTenantAndName(TARGET, "开发小队-copy")).thenReturn(0);
        doAnswer(invocation -> {
            ((SquadDO) invocation.getArgument(0)).setId(700L);
            return null;
        }).when(squadDao).insert(any(SquadDO.class));
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<SquadDO> squadCaptor = ArgumentCaptor.forClass(SquadDO.class);
        verify(squadDao).insert(squadCaptor.capture());
        assertEquals("开发小队-copy", squadCaptor.getValue().getName());
    }

    @Test
    void copyToTarget_squadNameExhaustionThrowsConflict() {
        CopySourceSnapshot snapshot = emptySnapshot();
        when(squadDao.countByTenantAndName(eq(TARGET), anyString())).thenReturn(1);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.CONFLICT.getCode(), "error.conflict");
        verify(squadDao, never()).insert(any());
    }

    @Test
    void copyToTarget_agentNameConflictAppendsSuffix() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());
        AgentDO existing = new AgentDO();
        when(agentDao.findByExactName(TARGET, "后端数字人")).thenReturn(List.of(existing));
        when(agentDao.findByExactName(TARGET, "后端数字人-copy")).thenReturn(List.of());
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<AgentDO> agentCaptor = ArgumentCaptor.forClass(AgentDO.class);
        verify(agentDao).insert(agentCaptor.capture());
        assertEquals("后端数字人-copy", agentCaptor.getValue().getName());
    }

    @Test
    void copyToTarget_agentNameExhaustionThrowsConflict() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());
        AgentDO existing = new AgentDO();
        when(agentDao.findByExactName(eq(TARGET), anyString())).thenReturn(List.of(existing));

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.CONFLICT.getCode(), "error.conflict");
        verify(agentDao, never()).insert(any());
    }

    @Test
    void copyToTarget_agentOnlineGuardRejectsFailsSystemError() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(0);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void copyToTarget_agentIdentityJsonKeepsDeclaredEvolutionMode() {
        CopySourceSnapshot.AgentSnapshot agent = new CopySourceSnapshot.AgentSnapshot(9L, "后端数字人",
                "avatar.png", 90L, 1, "后端工程师", "AW_FS_DEV", "背景", "职责",
                "{\"evolutionMode\":\"AUTO_PROPOSAL\"}", null, List.of(), List.of());
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(agent),
                List.of(), List.of(), List.of(), List.of(), List.of());
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<AgentVersionDO> versionCaptor = ArgumentCaptor.forClass(AgentVersionDO.class);
        verify(versionDao).insert(versionCaptor.capture());
        assertTrue(versionCaptor.getValue().getIdentityJson().contains("\"evolutionMode\":\"AUTO_PROPOSAL\""));
    }

    @Test
    void copyToTarget_agentMissingSkillReferenceFailsDependencyMissing() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(77L), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.dependency_missing");
        assertEquals("复制依赖无法解析：skill#77", ex.getMessage());
        verify(agentSkillDao, never()).insert(any());
    }

    @Test
    void copyToTarget_agentMissingEnvVarReferenceFailsDependencyMissing() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(), List.of(88L))),
                List.of(), List.of(), List.of(), List.of(), List.of());
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.dependency_missing");
        assertEquals("复制依赖无法解析：environment_variable#88", ex.getMessage());
    }

    @Test
    void copyToTarget_agentMissingSdlcReferenceFailsDependencyMissing() {
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", 21L, List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.dependency_missing");
        assertEquals("复制依赖无法解析：sdlc#21", ex.getMessage());
        verify(agentDao, never()).insert(any());
    }

    @Test
    void copyToTarget_executorWithoutCopiedAgentFailsDependencyMissing() {
        CopySourceSnapshot.ExecutorSnapshot executor = new CopySourceSnapshot.ExecutorSnapshot(
                51L, 999L, "执行器-1", "QODER_CLI", "{\"model\":\"x\"}");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(), List.of(), List.of(executor), List.of());

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.dependency_missing");
        assertEquals("复制依赖无法解析：agent#999", ex.getMessage());
        verify(executorDao, never()).insert(any());
    }

    @Test
    void copyToTarget_executorCopiedOfflineWithFreshTokenRef() {
        CopySourceSnapshot.ExecutorSnapshot executor = new CopySourceSnapshot.ExecutorSnapshot(
                51L, 9L, "执行器-1", "QODER_CLI", "{\"model\":\"x\"}");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", null, List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(executor), List.of());
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);
        doAnswer(invocation -> {
            ((ExecutorDO) invocation.getArgument(0)).setId(42L);
            return null;
        }).when(executorDao).insert(any(ExecutorDO.class));
        when(tokenService.issue(42L)).thenReturn(new TokenService.IssuedToken("plaintext-token", "ref-1"));
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<ExecutorDO> executorCaptor = ArgumentCaptor.forClass(ExecutorDO.class);
        verify(executorDao).insert(executorCaptor.capture());
        ExecutorDO copied = executorCaptor.getValue();
        assertEquals(TARGET, copied.getTenantId());
        assertEquals(555L, copied.getAgentId());
        assertEquals("执行器-1", copied.getName());
        assertEquals("OFFLINE", copied.getStatus());
        assertEquals("QODER_CLI", copied.getClientKind());
        assertEquals("{\"model\":\"x\"}", copied.getLaunchConfig());
        verify(executorDao).updateTokenRef(42L, "ref-1");
        assertTrue(ledger.entries().stream().noneMatch(entry -> entry.label().contains("plaintext-token")),
                "台账不得包含执行器凭证明文");
    }

    @Test
    void copyToTarget_sdlcCopiedWithStepsEntryRemapAndDefaultFlag() {
        CopySourceSnapshot.SdlcSnapshot sdlc = new CopySourceSnapshot.SdlcSnapshot(21L, "标准流程", "流程描述",
                "REQ", "ENABLED", 1, 501L, List.of(
                        new CopySourceSnapshot.StepSnapshot(501L, 0, "分析", "TASK", "instr", null, null,
                                true, 600, 2, "ANALYZE", "AGENT", "ROLE", "IN_PROGRESS", "OK", "FAIL"),
                        new CopySourceSnapshot.StepSnapshot(502L, 1, "编码", "TASK", "instr", null, null,
                                true, 600, 2, "CODE", "AGENT", "ROLE", "IN_PROGRESS", "OK", "FAIL")));
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null),
                List.of(agentSnapshot(9L, "后端数字人", 21L, List.of(), List.of())),
                List.of(sdlc), List.of(), List.of(), List.of(), List.of());
        when(sdlcDao.countByTenantAndName(TARGET, "标准流程")).thenReturn(0);
        doAnswer(invocation -> {
            ((SdlcDO) invocation.getArgument(0)).setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));
        doAnswer(invocation -> {
            SdlcStepDO step = invocation.getArgument(0);
            step.setId(701L + step.getStepOrder());
            return null;
        }).when(stepDao).insert(any(SdlcStepDO.class));
        when(sdlcDao.findDefault("REQ")).thenReturn(null);
        when(sdlcDao.updateStatus(600L, TARGET, "ENABLED", 701L, 0, USER)).thenReturn(1);
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);
        stubSquadInsert(700000L);
        when(operationDao.markSuccess(OPERATION_ID, 700000L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<SdlcDO> sdlcCaptor = ArgumentCaptor.forClass(SdlcDO.class);
        verify(sdlcDao).insert(sdlcCaptor.capture());
        SdlcDO copiedSdlc = sdlcCaptor.getValue();
        assertEquals(TARGET, copiedSdlc.getTenantId());
        assertEquals("标准流程", copiedSdlc.getName());
        assertEquals("REQ", copiedSdlc.getWorkType());
        assertEquals("ENABLED", copiedSdlc.getStatus());
        assertEquals(1, copiedSdlc.getIsDefault(), "目标空间无同类型默认流程时保留默认标记");
        ArgumentCaptor<SdlcStepDO> stepCaptor = ArgumentCaptor.forClass(SdlcStepDO.class);
        verify(stepDao, times(2)).insert(stepCaptor.capture());
        assertEquals(List.of(600L, 600L), stepCaptor.getAllValues().stream()
                .map(SdlcStepDO::getSdlcId).toList());
        assertEquals(List.of(0, 1), stepCaptor.getAllValues().stream()
                .map(SdlcStepDO::getStepOrder).toList());
        verify(sdlcDao).updateStatus(600L, TARGET, "ENABLED", 701L, 0, USER);
        ArgumentCaptor<AgentVersionDO> versionCaptor = ArgumentCaptor.forClass(AgentVersionDO.class);
        verify(versionDao).insert(versionCaptor.capture());
        assertEquals(600L, versionCaptor.getValue().getSdlcId(), "数字人必须绑定目标空间的新流程");
    }

    @Test
    void copyToTarget_sdlcDefaultNotKeptWhenTargetAlreadyHasOne() {
        CopySourceSnapshot.SdlcSnapshot sdlc = new CopySourceSnapshot.SdlcSnapshot(21L, "标准流程", null,
                "REQ", "DRAFT", 1, null, List.of());
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(),
                List.of(sdlc), List.of(), List.of(), List.of(), List.of());
        when(sdlcDao.countByTenantAndName(TARGET, "标准流程")).thenReturn(0);
        doAnswer(invocation -> {
            ((SdlcDO) invocation.getArgument(0)).setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));
        SdlcDO existingDefault = new SdlcDO();
        when(sdlcDao.findDefault("REQ")).thenReturn(existingDefault);
        when(sdlcDao.updateStatus(600L, TARGET, "DRAFT", null, 0, USER)).thenReturn(1);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<SdlcDO> sdlcCaptor = ArgumentCaptor.forClass(SdlcDO.class);
        verify(sdlcDao).insert(sdlcCaptor.capture());
        assertEquals(0, sdlcCaptor.getValue().getIsDefault(), "不替换目标空间已有默认流程");
        verify(sdlcDao).updateStatus(600L, TARGET, "DRAFT", null, 0, USER);
    }

    @Test
    void copyToTarget_sdlcDefaultWithoutWorkTypeStaysDefault() {
        CopySourceSnapshot.SdlcSnapshot sdlc = new CopySourceSnapshot.SdlcSnapshot(21L, "通用流程", null,
                null, "DRAFT", 1, null, List.of());
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(),
                List.of(sdlc), List.of(), List.of(), List.of(), List.of());
        when(sdlcDao.countByTenantAndName(TARGET, "通用流程")).thenReturn(0);
        doAnswer(invocation -> {
            ((SdlcDO) invocation.getArgument(0)).setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));
        when(sdlcDao.updateStatus(600L, TARGET, "DRAFT", null, 0, USER)).thenReturn(1);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<SdlcDO> sdlcCaptor = ArgumentCaptor.forClass(SdlcDO.class);
        verify(sdlcDao).insert(sdlcCaptor.capture());
        assertEquals(1, sdlcCaptor.getValue().getIsDefault());
        verify(sdlcDao, never()).findDefault(anyString());
    }

    @Test
    void copyToTarget_enabledSdlcWithoutEntryStepFailsEntryUnresolved() {
        CopySourceSnapshot.SdlcSnapshot sdlc = new CopySourceSnapshot.SdlcSnapshot(21L, "标准流程", null,
                "REQ", "ENABLED", 0, null, List.of());
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(),
                List.of(sdlc), List.of(), List.of(), List.of(), List.of());
        when(sdlcDao.countByTenantAndName(TARGET, "标准流程")).thenReturn(0);
        doAnswer(invocation -> {
            ((SdlcDO) invocation.getArgument(0)).setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.sdlc_entry_unresolved");
        assertEquals("流程入口步骤无法映射：sdlc#标准流程", ex.getMessage());
    }

    @Test
    void copyToTarget_sdlcEntryStepOutsideStepsFailsEntryUnresolved() {
        CopySourceSnapshot.SdlcSnapshot sdlc = new CopySourceSnapshot.SdlcSnapshot(21L, "标准流程", null,
                "REQ", "ENABLED", 0, 999L, List.of(
                        new CopySourceSnapshot.StepSnapshot(501L, 0, "分析", "TASK", null, null, null,
                                true, 600, 2, "ANALYZE", "AGENT", "ROLE", "IN_PROGRESS", "OK", "FAIL")));
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(),
                List.of(sdlc), List.of(), List.of(), List.of(), List.of());
        when(sdlcDao.countByTenantAndName(TARGET, "标准流程")).thenReturn(0);
        doAnswer(invocation -> {
            ((SdlcDO) invocation.getArgument(0)).setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));
        doAnswer(invocation -> {
            ((SdlcStepDO) invocation.getArgument(0)).setId(701L);
            return null;
        }).when(stepDao).insert(any(SdlcStepDO.class));

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.sdlc_entry_unresolved");
    }

    @Test
    void copyToTarget_sdlcNameConflictAppendsSuffix() {
        CopySourceSnapshot.SdlcSnapshot sdlc = new CopySourceSnapshot.SdlcSnapshot(21L, "标准流程", null,
                "REQ", "DRAFT", 0, null, List.of());
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(),
                List.of(sdlc), List.of(), List.of(), List.of(), List.of());
        when(sdlcDao.countByTenantAndName(TARGET, "标准流程")).thenReturn(1);
        when(sdlcDao.countByTenantAndName(TARGET, "标准流程-copy")).thenReturn(0);
        doAnswer(invocation -> {
            ((SdlcDO) invocation.getArgument(0)).setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));
        when(sdlcDao.updateStatus(600L, TARGET, "DRAFT", null, 0, USER)).thenReturn(1);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<SdlcDO> sdlcCaptor = ArgumentCaptor.forClass(SdlcDO.class);
        verify(sdlcDao).insert(sdlcCaptor.capture());
        assertEquals("标准流程-copy", sdlcCaptor.getValue().getName());
    }

    @Test
    void copyToTarget_skillNameConflictRetriesWithSuffix() {
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                3L, "SKILL", "打包能力", "{}", null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        // 入参 DO 在重试间被复用，须在调用当刻记录名称，捕获器只会拿到最终可变状态。
        List<String> attemptedNames = new ArrayList<>();
        doAnswer(invocation -> {
            attemptedNames.add(((SkillDO) invocation.getArgument(0)).getName());
            if (attemptedNames.size() == 1) {
                throw new DuplicateKeyException("uk");
            }
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        verify(skillDao, times(2)).insert(any(SkillDO.class));
        assertEquals(List.of("打包能力", "打包能力-copy"), attemptedNames);
        verify(skillDao, times(2)).releaseSoftDeletedName(eq(TARGET), eq("SKILL"), anyString());
    }

    @Test
    void copyToTarget_skillNameExhaustionThrowsConflict() {
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                3L, "SKILL", "打包能力", "{}", null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        doThrow(new DuplicateKeyException("uk")).when(skillDao).insert(any(SkillDO.class));

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.CONFLICT.getCode(), "error.conflict");
        verify(skillDao, times(5)).insert(any(SkillDO.class));
    }

    @Test
    void copyToTarget_skillPackageUpdateGuardRejectsFailsSystemError() {
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                3L, "SKILL", "打包能力", "{}", null, "PACKAGE",
                "bucket-a/t/100/skills/3/skill.zip", "pkg.zip", 10L, "md5-old");
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        when(objectStorage.get("bucket-a/t/100/skills/3/skill.zip")).thenReturn(new byte[]{1});
        doAnswer(invocation -> {
            ((SkillDO) invocation.getArgument(0)).setId(88L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(objectStorage.put(anyString(), anyString(), any()))
                .thenReturn(new StoredObject("bucket-a/t/200/skills/88/skill.zip", "md5-new", 1L));
        when(skillDao.updatePackage(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(),
                any(), anyLong(), any(), anyInt(), anyLong())).thenReturn(0);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void copyToTarget_mcpExplicitNullAndNonMapSectionsCopiedVerbatim() {
        CopySourceSnapshot.CapabilitySnapshot nullConfig = new CopySourceSnapshot.CapabilitySnapshot(
                6L, "MCP", "空值mcp", "null", null, null, null, null, null, null);
        CopySourceSnapshot.CapabilitySnapshot nonMapHeaders = new CopySourceSnapshot.CapabilitySnapshot(
                7L, "MCP", "非映射头mcp",
                "{\"headers\":\"plain-token\",\"env\":{\"TOKEN\":{\"kind\":\"literal\",\"value\":\"v\"}}}",
                null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(nullConfig, nonMapHeaders), List.of(), List.of(), List.of());
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<SkillDO> skillCaptor = ArgumentCaptor.forClass(SkillDO.class);
        verify(skillDao, times(2)).insert(skillCaptor.capture());
        assertEquals("null", skillCaptor.getAllValues().get(0).getInstallSpec());
        assertEquals("{\"headers\":\"plain-token\",\"env\":{\"TOKEN\":{\"kind\":\"literal\",\"value\":\"v\"}}}",
                skillCaptor.getAllValues().get(1).getInstallSpec());
        verifyNoInteractions(secretCrypto);
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void copyToTarget_mcpReencryptBlankTargetRefFailsWithKeyedError() {
        String installSpec = "{\"headers\":{\"Authorization\":{\"kind\":\"secretRef\",\"ref\":\"kc:old\"}}}";
        CopySourceSnapshot.CapabilitySnapshot capability = new CopySourceSnapshot.CapabilitySnapshot(
                4L, "MCP", "github-mcp", installSpec, null, null, null, null, null, null);
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(), List.of(),
                List.of(capability), List.of(), List.of(), List.of());
        when(secretCrypto.decrypt("kc:old")).thenReturn("plain-secret");
        when(secretCrypto.encrypt("plain-secret")).thenReturn("   ");

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED.getCode(), "squad.copy.secret_unresolvable");
        assertEquals("私密配置无法重新加密：MCP#github-mcp.headers.Authorization", ex.getMessage());
        verify(skillDao, never()).insert(any());
    }

    @Test
    void copyToTarget_sdlcStatusUpdateGuardRejectsFailsSystemError() {
        CopySourceSnapshot.SdlcSnapshot sdlc = new CopySourceSnapshot.SdlcSnapshot(21L, "标准流程", null,
                "REQ", "DRAFT", 0, null, List.of());
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(),
                List.of(sdlc), List.of(), List.of(), List.of(), List.of());
        when(sdlcDao.countByTenantAndName(TARGET, "标准流程")).thenReturn(0);
        doAnswer(invocation -> {
            ((SdlcDO) invocation.getArgument(0)).setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));
        when(sdlcDao.updateStatus(600L, TARGET, "DRAFT", null, 0, USER)).thenReturn(0);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
        verify(sdlcDao).updateStatus(600L, TARGET, "DRAFT", null, 0, USER);
        verify(squadDao, never()).insert(any(SquadDO.class));
    }

    @Test
    void copyToTarget_sdlcNameExhaustionThrowsConflict() {
        CopySourceSnapshot.SdlcSnapshot sdlc = new CopySourceSnapshot.SdlcSnapshot(21L, "标准流程", null,
                "REQ", "DRAFT", 0, null, List.of());
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(),
                List.of(sdlc), List.of(), List.of(), List.of(), List.of());
        when(sdlcDao.countByTenantAndName(eq(TARGET), anyString())).thenReturn(1);

        BizException ex = assertBizException(() -> service.copyToTarget(snapshot, TARGET, USER, operation, ledger));

        assertKeyed(ex, ErrorCode.CONFLICT.getCode(), "error.conflict");
        assertEquals("目标空间同名资源过多，复制无法生成唯一名称: sdlc#标准流程", ex.getMessage());
        verify(sdlcDao, times(5)).countByTenantAndName(eq(TARGET), anyString());
        verify(sdlcDao, never()).insert(any(SdlcDO.class));
    }

    @Test
    void copyToTarget_agentUnparsableIdentityJsonFallsBackToDefaultEvolutionMode() {
        CopySourceSnapshot.AgentSnapshot agent = new CopySourceSnapshot.AgentSnapshot(9L, "后端数字人",
                "avatar.png", 90L, 1, "后端工程师", "AW_FS_DEV", "背景", "职责",
                "not-json", null, List.of(), List.of());
        CopySourceSnapshot snapshot = snapshot(squadSource("开发小队", null), List.of(agent),
                List.of(), List.of(), List.of(), List.of(), List.of());
        doAnswer(invocation -> {
            ((AgentDO) invocation.getArgument(0)).setId(555L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));
        doAnswer(invocation -> {
            ((AgentVersionDO) invocation.getArgument(0)).setId(901L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(555L, TARGET, "ONLINE", 901L, null, 1, 0, USER)).thenReturn(1);
        stubSquadInsert(700L);
        when(operationDao.markSuccess(OPERATION_ID, 700L)).thenReturn(1);

        service.copyToTarget(snapshot, TARGET, USER, operation, ledger);

        ArgumentCaptor<AgentVersionDO> versionCaptor = ArgumentCaptor.forClass(AgentVersionDO.class);
        verify(versionDao).insert(versionCaptor.capture());
        assertTrue(versionCaptor.getValue().getIdentityJson().contains("\"evolutionMode\":\"ASSISTED\""),
                "无法解析的源身份配置回落默认演化模式");
    }

    @FunctionalInterface
    private interface Executable {
        void execute();
    }
}
