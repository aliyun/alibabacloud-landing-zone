package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.clarification.ClarificationDO;
import com.aliyun.autowonder.clarification.ClarificationDao;
import com.aliyun.autowonder.repo.RepoConclusionDO;
import com.aliyun.autowonder.repo.RepoConclusionDao;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClarificationAdapterTest {

    private ClarificationDao clarificationDao;
    private WorkitemDao workitemDao;
    private WorkitemCommentDao workitemCommentDao;
    private RepoDao repoDao;
    private RepoConclusionDao repoConclusionDao;
    private ClarificationAdapter adapter;

    @BeforeEach
    void setUp() {
        clarificationDao = mock(ClarificationDao.class);
        workitemDao = mock(WorkitemDao.class);
        workitemCommentDao = mock(WorkitemCommentDao.class);
        repoDao = mock(RepoDao.class);
        repoConclusionDao = mock(RepoConclusionDao.class);
        adapter = new ClarificationAdapter(clarificationDao, workitemDao,
                workitemCommentDao, repoDao, repoConclusionDao);
    }

    @Test
    void sceneIsClarification() {
        assertEquals(AiConstants.Scene.CLARIFICATION, adapter.scene());
    }

    @Test
    void validateRejectsNullClarificationMd() {
        String result = adapter.validateResult("{\"solution\":\"x\"}");
        assertNotNull(result);
    }

    @Test
    void validateAcceptsValidResult() {
        String json = JSON.toJSONString(new java.util.LinkedHashMap<>() {{
            put("clarificationMd", "# Clarified requirements");
        }});
        assertNull(adapter.validateResult(json));
    }

    @Test
    void persistCreatesWhenNoneExists() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefId(10L);
        when(clarificationDao.findByWorkitem(10L)).thenReturn(null);

        String json = "{\"clarificationMd\":\"# Result\"}";
        adapter.persistConfirmedResult(session, json);

        ArgumentCaptor<ClarificationDO> cap = ArgumentCaptor.forClass(ClarificationDO.class);
        verify(clarificationDao).insert(cap.capture());
        assertEquals("# Result", cap.getValue().getContentMd());
        assertEquals(100L, cap.getValue().getTenantId());
    }

    @Test
    void buildSystemPromptIncludesWorkitemContext() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefType("WORKITEM");
        session.setBizRefId(10L);

        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTitle("实现用户登录");
        workitem.setContentMd("需要支持手机号和邮箱登录");
        when(workitemDao.findById(10L)).thenReturn(workitem);

        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setAuthorRef(1L);
        comment.setContentMd("还需要考虑第三方登录");
        when(workitemCommentDao.listByWorkitem(100L, 10L)).thenReturn(List.of(comment));
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of());

        String prompt = adapter.buildSystemPrompt(session);

        assertTrue(prompt.contains("需求澄清专家"));
        assertTrue(prompt.contains("实现用户登录"));
        assertTrue(prompt.contains("需要支持手机号和邮箱登录"));
        assertTrue(prompt.contains("还需要考虑第三方登录"));
    }

    @Test
    void buildSystemPromptIncludesRepoContext() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefType("WORKITEM");
        session.setBizRefId(10L);

        when(workitemDao.findById(10L)).thenReturn(null);

        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setName("auto-wonder");
        repo.setUrl("git@github.com:example/auto-wonder.git");
        repo.setDescription("SDLC平台");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));

        RepoConclusionDO conclusion = new RepoConclusionDO();
        conclusion.setPurpose("SDLC全生命周期管理平台");
        conclusion.setKeyBusiness("需求管理、仓库扫描");
        conclusion.setUpstreams("GitLab");
        conclusion.setDownstreams("CI/CD");
        when(repoConclusionDao.findByRepoId(1L)).thenReturn(conclusion);

        String prompt = adapter.buildSystemPrompt(session);

        assertTrue(prompt.contains("auto-wonder"));
        assertTrue(prompt.contains("SDLC全生命周期管理平台"));
        assertTrue(prompt.contains("需求管理、仓库扫描"));
        assertTrue(prompt.contains("GitLab"));
        assertTrue(prompt.contains("CI/CD"));
    }

    @Test
    void buildSystemPromptHandlesNoWorkitemGracefully() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefType("OTHER");
        session.setBizRefId(10L);

        when(repoDao.list(100L, 0, 100)).thenReturn(List.of());

        String prompt = adapter.buildSystemPrompt(session);
        assertTrue(prompt.contains("需求澄清专家"));
        assertFalse(prompt.contains("需求信息"));
    }

    @Test
    void persistUpdatesWhenExists() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefId(10L);

        ClarificationDO existing = new ClarificationDO();
        existing.setId(5L);
        existing.setTenantId(100L);
        when(clarificationDao.findByWorkitem(10L)).thenReturn(existing);

        String json = "{\"clarificationMd\":\"# Updated\"}";
        adapter.persistConfirmedResult(session, json);

        verify(clarificationDao).update(5L, 100L, "# Updated");
    }
}
