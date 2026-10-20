package com.aliyun.autowonder.repo;

import com.aliyun.autowonder.agent.AgentRepoPermDao;
import com.aliyun.autowonder.agent.AgentRepoRefDO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import com.aliyun.autowonder.repo.dto.CreateRelationRequest;
import com.aliyun.autowonder.repo.dto.CreateRepoRequest;
import com.aliyun.autowonder.repo.dto.RepoRelationVO;
import com.aliyun.autowonder.repo.dto.TestRepoConnectionRequest;
import com.aliyun.autowonder.repo.dto.UpdateRepoRequest;
import com.aliyun.autowonder.repo.dto.RepoVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepoServiceTest {

    private RepoDao repoDao;
    private RepoConclusionDao conclusionDao;
    private RepoRelationDao relationDao;
    private AgentRepoPermDao agentRepoPermDao;
    private RepoConnectionTester connectionTester;
    private RepoService service;

    @BeforeEach
    void setUp() {
        repoDao = mock(RepoDao.class);
        conclusionDao = mock(RepoConclusionDao.class);
        relationDao = mock(RepoRelationDao.class);
        agentRepoPermDao = mock(AgentRepoPermDao.class);
        connectionTester = mock(RepoConnectionTester.class);
        service = new RepoService(repoDao, conclusionDao, relationDao, agentRepoPermDao, connectionTester);
    }

    @Test
    void createSuccess() {
        doAnswer(inv -> { ((RepoDO) inv.getArgument(0)).setId(100L); return null; })
                .when(repoDao).insert(any());

        CreateRepoRequest req = new CreateRepoRequest();
        req.setName("my-repo");
        req.setUrl("https://github.com/workspace/repo.git");
        req.setDefaultBranch("main");
        req.setDescription("desc");

        RepoVO vo = service.create(req, 1L, 2L);
        assertEquals(100L, vo.getId());
        assertEquals("my-repo", vo.getName());
        assertEquals("https://github.com/workspace/repo.git", vo.getUrl());
        assertEquals("UNSCANNED", vo.getScanStatus());
        verify(repoDao).insert(any());
    }

    @Test
    void updatePersistsNoCredentialColumns() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        repo.setDefaultBranch("main");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(repoDao.update(1L, 1L, "my-repo", "https://github.com/workspace/repo.git", "main",
                "new desc", 3, 2L)).thenReturn(1);

        UpdateRepoRequest req = new UpdateRepoRequest();
        req.setDescriptionPresent(true);
        req.setDescription("new desc");

        service.update(1L, req, 1L, 2L);

        verify(repoDao).update(1L, 1L, "my-repo", "https://github.com/workspace/repo.git", "main",
                "new desc", 3, 2L);
    }

    @Test
    void updateKeepsUnsentFields() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        repo.setDefaultBranch("main");
        repo.setDescription("old desc");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(repoDao.update(1L, 1L, "my-repo", "https://github.com/workspace/repo.git", "main",
                "old desc", 3, 2L)).thenReturn(1);

        service.update(1L, new UpdateRepoRequest(), 1L, 2L);

        verify(repoDao).update(1L, 1L, "my-repo", "https://github.com/workspace/repo.git", "main",
                "old desc", 3, 2L);
    }

    @Test
    void updateClearsNullableFieldsWhenExplicitNull() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        repo.setDefaultBranch("main");
        repo.setDescription("old desc");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(repoDao.update(1L, 1L, "my-repo", "https://github.com/workspace/repo.git", null,
                null, 3, 2L)).thenReturn(1);

        UpdateRepoRequest req = new UpdateRepoRequest();
        req.setDefaultBranchPresent(true);
        req.setDefaultBranch(null);
        req.setDescriptionPresent(true);
        req.setDescription(null);

        service.update(1L, req, 1L, 2L);

        verify(repoDao).update(1L, 1L, "my-repo", "https://github.com/workspace/repo.git", null,
                null, 3, 2L);
    }

    @Test
    void updateRejectsBlankNameWhenPresent() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);

        UpdateRepoRequest req = new UpdateRepoRequest();
        req.setNamePresent(true);
        req.setName("  ");

        BizException ex = assertThrows(BizException.class, () -> service.update(1L, req, 1L, 2L));
        assertEquals(ErrorCode.REPO_NAME_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void updateRejectsNullUrlWhenPresent() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);

        UpdateRepoRequest req = new UpdateRepoRequest();
        req.setUrlPresent(true);
        req.setUrl(null);

        BizException ex = assertThrows(BizException.class, () -> service.update(1L, req, 1L, 2L));
        assertEquals(ErrorCode.REPO_URL_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void createMissingNameThrows() {
        CreateRepoRequest req = new CreateRepoRequest();
        req.setUrl("https://github.com/workspace/repo.git");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.REPO_NAME_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void createMissingUrlThrows() {
        CreateRepoRequest req = new CreateRepoRequest();
        req.setName("my-repo");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.REPO_URL_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void createRejectsPathLikeNameWithoutPersisting() {
        CreateRepoRequest req = new CreateRepoRequest();
        req.setName("api-tool-agent/terraform-provider-alicloud");
        req.setUrl("https://github.com/api-tool-agent/terraform-provider-alicloud.git");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.REPO_NAME_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("单级目录名"), ex.getMessage());
        assertTrue(ex.getMessage().contains("「api-tool-agent/terraform-provider-alicloud」"), ex.getMessage());
        assertTrue(ex.getMessage().contains("namespace/repo-name"), ex.getMessage());
        verify(repoDao, never()).insert(any());
    }

    @Test
    void updateRejectsPathLikeNameWithoutPersisting() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);

        UpdateRepoRequest req = new UpdateRepoRequest();
        req.setNamePresent(true);
        req.setName("group\\repo");

        BizException ex = assertThrows(BizException.class, () -> service.update(1L, req, 1L, 2L));
        assertEquals(ErrorCode.REPO_NAME_INVALID.getCode(), ex.getCode());
        verify(repoDao, never()).update(anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt(), anyLong());
    }

    @Test
    void updateRejectsDotSegmentName() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);

        UpdateRepoRequest req = new UpdateRepoRequest();
        req.setNamePresent(true);
        req.setName("..");

        BizException ex = assertThrows(BizException.class, () -> service.update(1L, req, 1L, 2L));
        assertEquals(ErrorCode.REPO_NAME_INVALID.getCode(), ex.getCode());
    }

    @Test
    void updateRejectsAbsoluteAndTraversalNames() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);

        for (String bad : new String[]{"/etc/passwd", "C:repo", "repo/../../secret"}) {
            UpdateRepoRequest req = new UpdateRepoRequest();
            req.setNamePresent(true);
            req.setName(bad);

            BizException ex = assertThrows(BizException.class, () -> service.update(1L, req, 1L, 2L));
            assertEquals(ErrorCode.REPO_NAME_INVALID.getCode(), ex.getCode(), bad + " 应被拒绝");
        }
    }

    @Test
    void updateWithoutNameFieldKeepsStoredLegalName() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("terraform-provider-alicloud");
        repo.setUrl("https://github.com/api-tool-agent/terraform-provider-alicloud.git");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(repoDao.update(1L, 1L, "terraform-provider-alicloud",
                "https://github.com/api-tool-agent/terraform-provider-alicloud.git",
                null, null, 3, 2L)).thenReturn(1);

        service.update(1L, new UpdateRepoRequest(), 1L, 2L);

        verify(repoDao).update(1L, 1L, "terraform-provider-alicloud",
                "https://github.com/api-tool-agent/terraform-provider-alicloud.git",
                null, null, 3, 2L);
    }

    @Test
    void deleteInUseThrows() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setVersion(0);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(agentRepoPermDao.listActiveRefsByRepoId(1L, 1L))
                .thenReturn(List.of(activeRef(40013L, "AW全栈开发", 90001L, 3, "ONLINE")));

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 1L, 2L));
        assertEquals(ErrorCode.REPO_DELETE_IN_USE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("AW全栈开发(#40013) 在线版本 v3"),
                "错误信息必须指明有效引用来源，实际为: " + ex.getMessage());
        verify(repoDao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void deleteInUseByEditingDraftNamesDraftAsSource() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setVersion(0);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(agentRepoPermDao.listActiveRefsByRepoId(1L, 1L))
                .thenReturn(List.of(activeRef(40013L, "AW全栈开发", 90002L, 4, "EDITING")));

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 1L, 2L));
        assertEquals(ErrorCode.REPO_DELETE_IN_USE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("编辑草稿 v4"), ex.getMessage());
        verify(repoDao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void deleteInUseListsEveryReferencingAgent() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setVersion(0);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(agentRepoPermDao.listActiveRefsByRepoId(1L, 1L)).thenReturn(List.of(
                activeRef(40013L, "AW全栈开发", 90001L, 3, "ONLINE"),
                activeRef(40014L, "AW测试工程师", 90003L, 1, "EDITING")));

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 1L, 2L));
        assertTrue(ex.getMessage().contains("AW全栈开发(#40013) 在线版本 v3"), ex.getMessage());
        assertTrue(ex.getMessage().contains("AW测试工程师(#40014) 编辑草稿 v1"), ex.getMessage());
        verify(repoDao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void deleteInUseToleratesMissingAgentNameAndVersionNo() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setVersion(0);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(agentRepoPermDao.listActiveRefsByRepoId(1L, 1L)).thenReturn(List.of(
                activeRef(40013L, "  ", 90001L, null, "ONLINE"),
                activeRef(40014L, null, 90003L, 2, "EDITING")));

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 1L, 2L));
        assertEquals(ErrorCode.REPO_DELETE_IN_USE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("数字员工(#40013) 在线版本"), ex.getMessage());
        assertTrue(ex.getMessage().contains("数字员工(#40014) 编辑草稿 v2"), ex.getMessage());
        assertFalse(ex.getMessage().contains("在线版本 v"), ex.getMessage());
    }

    @Test
    void deleteSuccessCascades() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setVersion(0);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(agentRepoPermDao.listActiveRefsByRepoId(1L, 1L)).thenReturn(List.of());
        when(repoDao.softDelete(1L, 1L, 0, 2L)).thenReturn(1);

        service.delete(1L, 1L, 2L);

        verify(repoDao).softDelete(1L, 1L, 0, 2L);
        verify(conclusionDao).deleteByRepoId(1L, 1L);
        verify(relationDao).deleteByRepoId(1L, 1L);
    }

    private AgentRepoRefDO activeRef(long agentId, String agentName, long agentVersionId,
                                     Integer versionNo, String refType) {
        AgentRepoRefDO ref = new AgentRepoRefDO();
        ref.setAgentId(agentId);
        ref.setAgentName(agentName);
        ref.setAgentVersionId(agentVersionId);
        ref.setVersionNo(versionNo);
        ref.setRefType(refType);
        return ref;
    }

    @Test
    void createRelationSelfRefThrows() {
        CreateRelationRequest req = new CreateRelationRequest();
        req.setFromRepoId(5L);
        req.setToRepoId(5L);
        req.setRelationType("DEPENDS_ON");

        BizException ex = assertThrows(BizException.class, () -> service.createRelation(req, 1L, 2L));
        assertEquals(ErrorCode.REPO_RELATION_SELF_REF.getCode(), ex.getCode());
    }

    @Test
    void createRelationDuplicateThrows() {
        CreateRelationRequest req = new CreateRelationRequest();
        req.setFromRepoId(1L);
        req.setToRepoId(2L);
        req.setRelationType("DEPENDS_ON");

        RepoDO fromRepo = new RepoDO();
        fromRepo.setId(1L);
        fromRepo.setTenantId(1L);
        RepoDO toRepo = new RepoDO();
        toRepo.setId(2L);
        toRepo.setTenantId(1L);
        when(repoDao.findById(1L)).thenReturn(fromRepo);
        when(repoDao.findById(2L)).thenReturn(toRepo);

        RepoRelationDO existing = new RepoRelationDO();
        existing.setId(99L);
        existing.setIsDeleted(0);
        when(relationDao.findByUkIncludeDeleted(1L, 1L, 2L, "DEPENDS_ON")).thenReturn(existing);

        BizException ex = assertThrows(BizException.class, () -> service.createRelation(req, 1L, 2L));
        assertEquals(ErrorCode.REPO_RELATION_DUPLICATE.getCode(), ex.getCode());
    }

    @Test
    void createRelationReactivatesSoftDeleted() {
        CreateRelationRequest req = new CreateRelationRequest();
        req.setFromRepoId(1L);
        req.setToRepoId(2L);
        req.setRelationType("DEPENDS_ON");
        req.setDescription("new desc");

        RepoDO fromRepo = new RepoDO();
        fromRepo.setId(1L);
        fromRepo.setTenantId(1L);
        RepoDO toRepo = new RepoDO();
        toRepo.setId(2L);
        toRepo.setTenantId(1L);
        when(repoDao.findById(1L)).thenReturn(fromRepo);
        when(repoDao.findById(2L)).thenReturn(toRepo);

        RepoRelationDO deleted = new RepoRelationDO();
        deleted.setId(99L);
        deleted.setIsDeleted(1);
        when(relationDao.findByUkIncludeDeleted(1L, 1L, 2L, "DEPENDS_ON")).thenReturn(deleted);

        RepoRelationDO restored = new RepoRelationDO();
        restored.setId(99L);
        restored.setFromRepoId(1L);
        restored.setToRepoId(2L);
        restored.setRelationType("DEPENDS_ON");
        restored.setDescription("new desc");
        restored.setIsDeleted(0);
        when(relationDao.findById(99L)).thenReturn(restored);

        RepoRelationVO vo = service.createRelation(req, 1L, 2L);
        assertEquals(99L, vo.getId());
        verify(relationDao).undelete(99L, 1L, "new desc", null, 2L);
        verify(relationDao, never()).insert(any());
    }

    @Test
    void createRelationSuccess() {
        doAnswer(inv -> { ((RepoRelationDO) inv.getArgument(0)).setId(200L); return null; })
                .when(relationDao).insert(any());

        CreateRelationRequest req = new CreateRelationRequest();
        req.setFromRepoId(1L);
        req.setToRepoId(2L);
        req.setRelationType("DEPENDS_ON");
        req.setDescription("desc");
        req.setAiSessionId(10L);

        RepoDO fromRepo = new RepoDO();
        fromRepo.setId(1L);
        fromRepo.setTenantId(1L);
        RepoDO toRepo = new RepoDO();
        toRepo.setId(2L);
        toRepo.setTenantId(1L);
        when(repoDao.findById(1L)).thenReturn(fromRepo);
        when(repoDao.findById(2L)).thenReturn(toRepo);
        when(relationDao.findByUkIncludeDeleted(1L, 1L, 2L, "DEPENDS_ON")).thenReturn(null);

        RepoRelationVO vo = service.createRelation(req, 1L, 2L);
        assertEquals(200L, vo.getId());
        assertEquals(1L, vo.getFromRepoId());
        assertEquals(2L, vo.getToRepoId());
        assertEquals("DEPENDS_ON", vo.getRelationType());
        verify(relationDao).insert(any());
    }

    @Test
    void listRelationsByRepoIdReturnsBidirectional() {
        RepoRelationDO r1 = new RepoRelationDO();
        r1.setId(1L);
        r1.setFromRepoId(10L);
        r1.setToRepoId(20L);
        r1.setRelationType("DEPENDENCY");

        RepoRelationDO r2 = new RepoRelationDO();
        r2.setId(2L);
        r2.setFromRepoId(30L);
        r2.setToRepoId(10L);
        r2.setRelationType("SERVICE");

        when(relationDao.listByRepoId(1L, 10L)).thenReturn(List.of(r1, r2));

        List<RepoRelationVO> result = service.listRelationsByRepoId(1L, 10L);
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
    }

    @Test
    void scanAlwaysAllowsRescan() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setScanStatus("SCANNING");
        repo.setVersion(1);
        repo.setGmtModified(new java.util.Date());
        when(repoDao.findById(1L)).thenReturn(repo);
        when(repoDao.updateScanStatus(1L, 1L, "SCANNING", 1, 2L)).thenReturn(1);

        assertDoesNotThrow(() -> service.startScan(1L, 1L, 2L));
    }

    @Test
    void testConnectionDelegatesToGitTester() {
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("git@github.com:workspace/repo.git");
        req.setDefaultBranch("main");
        when(connectionTester.test(req)).thenReturn(RepoConnectionTestResult.ok("连接成功"));

        RepoConnectionTestResult result = service.testConnection(req);

        assertTrue(result.isSuccess());
        assertEquals("连接成功", result.getMessage());
        verify(connectionTester).test(req);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"'a/b','仓库名称「a/b」必须是单级目录名（不含 / 或 \\ 等路径分隔符、也不能是 . 或 ..、绝对路径）。如填写的是 namespace/repo-name 形式的标识，请将完整标识填在 Git 仓库地址中，仓库名称只填单级目录名。','The repository name “a/b” must be a single directory segment, without path separators, ., .., or an absolute path. If it is a namespace/repo-name identifier, put the full identifier in the Git repository URL and use only one directory segment as the name.'", "'~raw','仓库名称不能以 ~ 或 % 开头','The repository name cannot start with ~ or %'", "'a\u0001b','仓库名称不能包含控制字符','The repository name cannot contain control characters'"})
    void nameIdentityProjectsWithoutChangingValidator(String name, String raw, String en) {
        var request = new CreateRepoRequest(); request.setName(name); request.setUrl("https://raw");
        var error = assertThrows(BizException.class, () -> service.create(request, 1L, 2L));
        assertEquals(raw, RepoNameValidator.validate(name)); assertEquals(raw, error.getMessage());
        assertEquals(BizException.class, error.getClass()); assertNull(error.getCause());
        assertEquals(ErrorCode.REPO_NAME_INVALID.getCode(), error.getCode());
        verify(repoDao, never()).insert(any());
        assertEquals(en, com.aliyun.autowonder.common.i18n.SystemMessages.render(error.getMessageKey(), error.getMessageArgs(), raw, java.util.Locale.US));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh-CN", "en-US"})
    void deleteRefusalKeepsRawDiagnosticsAndProjectsKnownRowsOnBothHumanWires(String language) throws Exception {
        RepoDO repo = new RepoDO(); repo.setId(1L); repo.setTenantId(1L); repo.setVersion(0);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(agentRepoPermDao.listActiveRefsByRepoId(1L, 1L)).thenReturn(List.of(
            activeRef(9007199254740993L, " ", 11L, null, "ONLINE"),
            activeRef(7L, "数字员工", 12L, 1000, "EDITING"),
            activeRef(8L, "<b>raw</b>", 13L, null, "unknown")));
        String raw = "仓库仍被数字员工引用,无法删除:数字员工(#9007199254740993) 在线版本；数字员工(#7) 编辑草稿 v1000；<b>raw</b>(#8) 编辑草稿。请先在对应版本解除该仓库权限,或发布解除后的新版本再删除仓库。";
        String expected = language.equals("zh-CN") ? raw : "The repository is still referenced by Agents and cannot be deleted: Agent(#9007199254740993) online version; 数字员工(#7) editing draft v1000; <b>raw</b>(#8) editing draft. Remove the repository permission from the relevant version, or publish a new version without it before deleting the repository.";
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(language));
        com.aliyun.autowonder.context.AutoWonderContext.get().setCurrentWorkspaceId(1L);
        com.aliyun.autowonder.context.AutoWonderContext.get().setUserId(2L);
        try {
            var error = assertThrows(BizException.class, () -> new RepoController(service).delete(1L));
            assertEquals(raw, error.getMessage()); assertEquals(raw, error.getLocalizedMessage()); assertNull(error.getCause());
            assertEquals(BizException.class, error.getClass()); assertEquals(ErrorCode.REPO_DELETE_IN_USE.getCode(), error.getCode());
            var response = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error);
            assertEquals(200, response.getStatusCodeValue()); assertEquals(expected, response.getBody().getMessage());
            var token = mock(com.aliyun.autowonder.mcp.McpAccessTokenService.class);
            var tools = mock(com.aliyun.autowonder.mcp.McpToolService.class);
            var principal = new com.aliyun.autowonder.mcp.McpAccessTokenService.Principal(1L,2L,3L,com.aliyun.autowonder.access.WorkspaceAccessLevel.READ_WRITE,com.aliyun.autowonder.mcp.McpAccessTokenService.CredentialType.CONVERSATION);
            when(token.authenticate(null, "raw-token")).thenReturn(principal);
            doAnswer(inv -> {service.delete(1L,1L,2L); return null;}).when(tools).call(principal, "autowonder.delete_repo", java.util.Map.of("id",1L));
            var rpc = new com.aliyun.autowonder.mcp.McpController(token, tools, mock(com.aliyun.autowonder.mcp.McpSseService.class)).rpc(null, "raw-token", java.util.Map.of(
                "id","raw-id","method","tools/call","params",java.util.Map.of("name","autowonder.delete_repo","arguments",java.util.Map.of("id",1L))));
            assertEquals(-32000, rpc.getError().getCode()); assertEquals(expected, rpc.getError().getMessage()); assertEquals("raw-id", rpc.getId());
            verify(repoDao, never()).softDelete(anyLong(),anyLong(),anyInt(),anyLong());
        } finally {org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); com.aliyun.autowonder.context.AutoWonderContext.destroy();}
    }

}
