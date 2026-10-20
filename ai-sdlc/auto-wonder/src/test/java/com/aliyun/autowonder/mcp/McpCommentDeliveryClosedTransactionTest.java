package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.category.CategoryService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.dispatch.DispatchControlTransport;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchRecoveryService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.guidance.GuidanceDO;
import com.aliyun.autowonder.guidance.GuidanceDao;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.GuidanceTransport;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.sdlc.SdlcService;
import com.aliyun.autowonder.skill.SkillPackageService;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.squad.SquadService;
import com.aliyun.autowonder.statemachine.StatusTemplateService;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The MCP comment entry must save the comment and start its interaction inside one real
 * transaction, so a closed delivery rolls the comment back instead of leaving it saved
 * while telling the caller it was not sent.
 */
class McpCommentDeliveryClosedTransactionTest {
    private static final long WORKSPACE_ID = 100L;
    private static final long USER_ID = 7L;
    private static final long WORKITEM_ID = 99L;
    private static final long AGENT_ID = 40013L;
    private static final long DISPATCH_ID = 321L;

    @Test
    void closedDeliveryRollsBackTheCommentOfThePersonalCredential() {
        Fixture fixture = fixture();
        closeDelivery(fixture);

        BizException failure = assertThrows(BizException.class, () -> fixture.service.call(personal(),
                "autowonder.add_workitem_comment", commentArgs("@AW全栈开发 看下", List.of(AGENT_ID))));

        assertEquals(ErrorCode.WORKITEM_DELIVERY_CLOSED.getCode(), failure.getCode());
        assertEquals("workitem.comment.delivery_closed", failure.getMessageKey());
        assertEquals(0, fixture.comments(), "整笔回滚后不得残留已保存的评论");

        assertThrows(BizException.class, () -> fixture.service.call(personal(),
                "autowonder.add_workitem_comment", commentArgs("@AW全栈开发 看下", List.of(AGENT_ID))));
        assertEquals(0, fixture.comments());
    }

    @Test
    void closedDeliveryRollsBackTheCommentOfTheDispatchCredential() {
        Fixture fixture = fixture();
        closeDelivery(fixture);

        BizException failure = assertThrows(BizException.class, () -> fixture.service.call(dispatchPrincipal(),
                "autowonder.add_workitem_comment", Map.of("workspaceId", WORKSPACE_ID, "id", WORKITEM_ID,
                        "contentMd", "@tester 看下", "targetAgentIds", List.of(40015L),
                        "targetHumanIds", List.of(77L))));

        assertEquals(ErrorCode.WORKITEM_DELIVERY_CLOSED.getCode(), failure.getCode());
        assertEquals("workitem.comment.delivery_closed", failure.getMessageKey());
        assertEquals(0, fixture.comments(), "整笔回滚后不得残留已保存的评论");
    }

    @Test
    void closedDeliveryKeepsCommittingCommentsWithoutAgentTargets() {
        Fixture fixture = fixture();
        closeDelivery(fixture);

        Object result = fixture.service.call(personal(), "autowonder.add_workitem_comment",
                Map.of("workspaceId", WORKSPACE_ID, "id", WORKITEM_ID, "contentMd", "普通评论"));

        assertNotNull(result);
        assertEquals(1, fixture.comments(), "没有 @ 数字员工的普通评论不受关闭交付影响");
    }

    @Test
    void openDeliveryCommitsTheCommentAndReturnsIt() {
        Fixture fixture = fixture();
        fixture.jdbc.update("INSERT INTO workitem_execution_control VALUES(?,?,0)", WORKSPACE_ID, WORKITEM_ID);

        Object result = fixture.service.call(personal(), "autowonder.add_workitem_comment",
                commentArgs("@AW全栈开发 看下", List.of(AGENT_ID)));

        CommentVO comment = assertInstanceOf(CommentVO.class, result);
        assertEquals(1L, comment.getId().longValue());
        assertEquals(1, fixture.comments());
    }

    private static Map<String, Object> commentArgs(String contentMd, List<Long> targetAgentIds) {
        return Map.of("workspaceId", WORKSPACE_ID, "id", WORKITEM_ID, "contentMd", contentMd,
                "targetAgentIds", targetAgentIds);
    }

    private static McpAccessTokenService.Principal personal() {
        return McpAccessTokenService.Principal.personal(USER_ID, 1L);
    }

    private static McpAccessTokenService.Principal dispatchPrincipal() {
        return new McpAccessTokenService.Principal(WORKSPACE_ID, USER_ID, -DISPATCH_ID,
                WorkspaceAccessLevel.READ_WRITE, McpAccessTokenService.CredentialType.DISPATCH);
    }

    private static void closeDelivery(Fixture fixture) {
        fixture.jdbc.update("INSERT INTO workitem_execution_control VALUES(?,?,1)",
                WORKSPACE_ID, WORKITEM_ID);
    }

    private static CommentVO insertComment(JdbcTemplate jdbc, String contentMd) {
        Long id = jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM workitem_comment", Long.class);
        jdbc.update("INSERT INTO workitem_comment(id,tenant_id,workitem_id,content_md) VALUES(?,?,?,?)",
                id, WORKSPACE_ID, WORKITEM_ID, contentMd);
        CommentVO comment = new CommentVO();
        comment.setId(id);
        return comment;
    }

    private static Fixture fixture() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE workitem_comment(id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                + "workitem_id BIGINT, content_md VARCHAR(4000))");
        jdbc.execute("CREATE TABLE workitem_execution_control(tenant_id BIGINT, workitem_id BIGINT, "
                + "closed INT DEFAULT 0, PRIMARY KEY(tenant_id, workitem_id))");
        var txManager = new DataSourceTransactionManager(dataSource);

        // 真实关闭判定：requireOpen 读 workitem_execution_control，与生产一致。
        DispatchRecoveryService recovery = new DispatchRecoveryService(jdbc, txManager,
                mock(DispatchDao.class), mock(WorkitemDao.class), mock(DispatchControlTransport.class));

        WorkitemService workitemService = mock(WorkitemService.class);
        when(workitemService.addComment(eq(WORKITEM_ID), anyString(), any(), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenAnswer(call -> insertComment(jdbc, call.getArgument(1)));
        when(workitemService.addAgentComment(eq(WORKITEM_ID), anyString(), any(), eq(WORKSPACE_ID),
                anyLong(), eq(USER_ID))).thenAnswer(call -> insertComment(jdbc, call.getArgument(1)));
        when(workitemService.getParticipants(WORKITEM_ID, WORKSPACE_ID)).thenReturn(List.of());

        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        when(commentDao.findById(eq(WORKSPACE_ID), anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(1);
            List<Long> owners = jdbc.queryForList("SELECT workitem_id FROM workitem_comment "
                    + "WHERE tenant_id=? AND id=?", Long.class, WORKSPACE_ID, id);
            if (owners.isEmpty()) {
                return null;
            }
            WorkitemCommentDO row = new WorkitemCommentDO();
            row.setId(id);
            row.setTenantId(WORKSPACE_ID);
            row.setWorkitemId(owners.get(0));
            return row;
        });

        WorkitemDao workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(anyLong())).thenAnswer(call -> {
            WorkitemDO workitem = new WorkitemDO();
            workitem.setId(call.getArgument(0));
            workitem.setTenantId(WORKSPACE_ID);
            return workitem;
        });

        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.findById(anyLong())).thenAnswer(call -> {
            AgentDO agent = new AgentDO();
            agent.setId(call.getArgument(0));
            agent.setTenantId(WORKSPACE_ID);
            agent.setName("AW全栈开发");
            agent.setOnlineVersionId(1L);
            return agent;
        });

        GuidanceDao guidanceDao = mock(GuidanceDao.class);
        doAnswer(call -> {
            ((GuidanceDO) call.getArgument(0)).setId(900L);
            return null;
        }).when(guidanceDao).insert(any(GuidanceDO.class));
        when(guidanceDao.bindPendingDispatch(anyLong(), anyLong(), anyLong())).thenReturn(1);

        DispatchDao dispatchDao = mock(DispatchDao.class);
        when(dispatchDao.listByWorkitem(anyLong(), anyLong())).thenReturn(List.of());
        when(dispatchDao.findById(DISPATCH_ID)).thenReturn(dispatchRow());

        DispatchService dispatchService = mock(DispatchService.class);
        when(dispatchService.enqueueCommentInteraction(anyLong(), anyLong(), anyLong(), any(), anyBoolean(),
                any(), anyLong(), anyLong())).thenAnswer(call -> {
            recovery.requireOpen(workitemSubject());
            DispatchDO interaction = new DispatchDO();
            interaction.setId(700L);
            return interaction;
        });

        GuidanceService guidance = new GuidanceService(guidanceDao, workitemDao, commentDao, agentDao,
                dispatchDao, mock(GuidanceTransport.class), workitemService, dispatchService,
                mock(AgentSdlcResolver.class), mock(ApplicationEventPublisher.class));
        ProxyFactory proxy = new ProxyFactory(guidance);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        GuidanceService transactionalGuidance = (GuidanceService) proxy.getProxy();

        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID)).thenReturn(WorkspaceAccessLevel.READ_WRITE);
        McpToolService service = new McpToolService(workspaceService, workitemService, transactionalGuidance,
                mock(SkillService.class), mock(SkillPackageService.class), mock(SdlcService.class),
                mock(AgentService.class), mock(StatusTemplateService.class), new PlatformSkillCatalog(),
                dispatchDao, mock(RequirementDocumentService.class),
                mock(WorkitemCliUploadTokenService.class), mock(WorkitemCliDownloadTokenService.class),
                mock(RepoService.class), mock(SquadService.class), mock(DispatchPauseService.class),
                mock(CategoryService.class));
        return new Fixture(jdbc, service);
    }

    private static DispatchDO workitemSubject() {
        DispatchDO subject = new DispatchDO();
        subject.setTenantId(WORKSPACE_ID);
        subject.setWorkitemId(WORKITEM_ID);
        return subject;
    }

    private static DispatchDO dispatchRow() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(DISPATCH_ID);
        dispatch.setTenantId(WORKSPACE_ID);
        dispatch.setWorkitemId(WORKITEM_ID);
        dispatch.setAgentId(40014L);
        return dispatch;
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final McpToolService service;

        Fixture(JdbcTemplate jdbc, McpToolService service) {
            this.jdbc = jdbc;
            this.service = service;
        }

        int comments() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM workitem_comment", Integer.class);
        }
    }
}
