package com.aliyun.autowonder.repo;

import com.aliyun.autowonder.agent.AgentRepoPermDao;
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
        req.setUrl("https://github.com/org/repo.git");
        req.setDefaultBranch("main");
        req.setDescription("desc");

        RepoVO vo = service.create(req, 1L, 2L);
        assertEquals(100L, vo.getId());
        assertEquals("my-repo", vo.getName());
        assertEquals("https://github.com/org/repo.git", vo.getUrl());
        assertEquals("UNSCANNED", vo.getScanStatus());
        verify(repoDao).insert(any());
    }

    @Test
    void updatePersistsNoCredentialColumns() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setName("my-repo");
        repo.setUrl("https://github.com/org/repo.git");
        repo.setDefaultBranch("main");
        repo.setVersion(3);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(repoDao.update(1L, 1L, "my-repo", "https://github.com/org/repo.git", "main",
                "new desc", 3, 2L)).thenReturn(1);

        UpdateRepoRequest req = new UpdateRepoRequest();
        req.setDescription("new desc");

        service.update(1L, req, 1L, 2L);

        verify(repoDao).update(1L, 1L, "my-repo", "https://github.com/org/repo.git", "main",
                "new desc", 3, 2L);
    }

    @Test
    void createMissingNameThrows() {
        CreateRepoRequest req = new CreateRepoRequest();
        req.setUrl("https://github.com/org/repo.git");

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
    void deleteInUseThrows() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setVersion(0);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(agentRepoPermDao.countByRepoId(1L, 1L)).thenReturn(2);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 1L, 2L));
        assertEquals(ErrorCode.REPO_DELETE_IN_USE.getCode(), ex.getCode());
    }

    @Test
    void deleteSuccessCascades() {
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setTenantId(1L);
        repo.setVersion(0);
        when(repoDao.findById(1L)).thenReturn(repo);
        when(agentRepoPermDao.countByRepoId(1L, 1L)).thenReturn(0);
        when(repoDao.softDelete(1L, 1L, 0, 2L)).thenReturn(1);

        service.delete(1L, 1L, 2L);

        verify(repoDao).softDelete(1L, 1L, 0, 2L);
        verify(conclusionDao).deleteByRepoId(1L, 1L);
        verify(relationDao).deleteByRepoId(1L, 1L);
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
        req.setUrl("git@github.com:org/repo.git");
        req.setDefaultBranch("main");
        when(connectionTester.test(req)).thenReturn(RepoConnectionTestResult.ok("连接成功"));

        RepoConnectionTestResult result = service.testConnection(req);

        assertTrue(result.isSuccess());
        assertEquals("连接成功", result.getMessage());
        verify(connectionTester).test(req);
    }
}
