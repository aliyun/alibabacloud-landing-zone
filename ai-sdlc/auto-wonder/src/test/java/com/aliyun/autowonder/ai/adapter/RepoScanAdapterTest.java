package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.repo.RepoConclusionDO;
import com.aliyun.autowonder.repo.RepoConclusionDao;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.repo.RepoDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepoScanAdapterTest {

    private RepoDao repoDao;
    private RepoConclusionDao conclusionDao;
    private RepoScanAdapter adapter;

    @BeforeEach
    void setUp() {
        repoDao = mock(RepoDao.class);
        conclusionDao = mock(RepoConclusionDao.class);
        adapter = new RepoScanAdapter(repoDao, conclusionDao);
    }

    @Test
    void sceneIsRepoScan() {
        assertEquals(AiConstants.Scene.REPO_SCAN, adapter.scene());
    }

    @Test
    void validateResultRejectsMissingPurpose() {
        assertNotNull(adapter.validateResult("{}"));
        assertNotNull(adapter.validateResult(null));
        assertNotNull(adapter.validateResult("{\"summaryMd\":\"x\"}"));
    }

    @Test
    void validateResultAcceptsValidJson() {
        String json = JSON.toJSONString(Map.of("purpose", "backend service", "summaryMd", "# Summary"));
        assertNull(adapter.validateResult(json));
    }

    @Test
    void buildUserPromptUsesPreparedLocalRepoPath() {
        AiSessionDO session = new AiSessionDO();

        String prompt = adapter.buildUserPrompt(session, "/tmp/aiw/10003/repo");

        assertTrue(prompt.contains("/tmp/aiw/10003/repo"));
        assertTrue(prompt.contains("本地仓库"));
        assertTrue(prompt.contains("等待用户确认"));
    }

    @Test
    void persistWritesConclusionAndUpdatesStatus() {
        RepoDO repo = new RepoDO();
        repo.setId(5L);
        repo.setTenantId(1L);
        repo.setVersion(2);
        when(repoDao.findById(5L)).thenReturn(repo);
        when(conclusionDao.findByRepoId(5L)).thenReturn(null);

        AiSessionDO session = new AiSessionDO();
        session.setId(100L);
        session.setTenantId(1L);
        session.setBizRefId(5L);

        String result = JSON.toJSONString(Map.of("purpose", "backend api", "summaryMd", "# Summary"));
        adapter.persistConfirmedResult(session, result);

        verify(conclusionDao).insert(any(RepoConclusionDO.class));
        verify(repoDao).updateScanStatus(5L, 1L, "CONCLUDED", 2, null);
    }

    @Test
    void persistUpdatesExistingConclusion() {
        RepoDO repo = new RepoDO();
        repo.setId(5L);
        repo.setTenantId(1L);
        repo.setVersion(3);
        when(repoDao.findById(5L)).thenReturn(repo);

        RepoConclusionDO existing = new RepoConclusionDO();
        existing.setId(50L);
        existing.setVersion(1);
        when(conclusionDao.findByRepoId(5L)).thenReturn(existing);

        AiSessionDO session = new AiSessionDO();
        session.setId(101L);
        session.setTenantId(1L);
        session.setBizRefId(5L);

        String result = JSON.toJSONString(Map.of("purpose", "updated purpose", "summaryMd", "# Updated"));
        adapter.persistConfirmedResult(session, result);

        verify(conclusionDao, never()).insert(any());
        verify(conclusionDao).update(eq(50L), eq(1L), eq("updated purpose"), isNull(),
                isNull(), isNull(), eq("# Updated"), eq(1), isNull());
        verify(repoDao).updateScanStatus(5L, 1L, "CONCLUDED", 3, null);
    }

    @Test
    void persistSkipsWhenRepoNotFound() {
        when(repoDao.findById(99L)).thenReturn(null);

        AiSessionDO session = new AiSessionDO();
        session.setId(102L);
        session.setTenantId(1L);
        session.setBizRefId(99L);

        adapter.persistConfirmedResult(session, "{\"purpose\":\"x\",\"summaryMd\":\"y\"}");

        verify(conclusionDao, never()).insert(any());
        verify(conclusionDao, never()).findByRepoId(any());
    }
}
