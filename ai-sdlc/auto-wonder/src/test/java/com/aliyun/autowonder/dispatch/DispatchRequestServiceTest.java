package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.scheduledtask.compat.V037MapperMode;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapability;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaMode;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchRequestServiceTest {
    JdbcTemplate jdbc;
    DispatchDao dao;
    DispatchRecoveryService recovery;
    DispatchRequestService service;
    JwtService jwt;
    SdlcStepDO step;
    WorkspaceMemberDao members;
    UserDao users;
    ApplicationEventPublisher events;

    @BeforeEach void setup() throws Exception {
        setup(new V037SchemaCapability(V037SchemaMode.V037_READY, V037MapperMode.SOURCE_AWARE,
                true, true, false, Set.of(), Instant.now()));
    }

    void setup(V037SchemaCapability v037Capability) throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dispatch (id BIGINT PRIMARY KEY,tenant_id BIGINT,workitem_id BIGINT,status VARCHAR(32),source_type VARCHAR(32),is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE workitem_execution_control (tenant_id BIGINT,workitem_id BIGINT,closed INT DEFAULT 0,PRIMARY KEY(tenant_id,workitem_id))");
        String schema = Files.readString(Path.of("docs/migration/V077__dispatch_requests.sql"))
                .replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        jdbc.execute(schema);
        jdbc.update("INSERT INTO dispatch VALUES (100,1,10,'RUNNING','WORKITEM',0)");
        dao = mock(DispatchDao.class);
        when(dao.findById(anyLong())).thenAnswer(i -> jdbc.query("SELECT * FROM dispatch WHERE id=?", (r,n) -> {
            var d = new DispatchDO(); d.setId(r.getLong("id")); d.setTenantId(r.getLong("tenant_id")); d.setWorkitemId(r.getLong("workitem_id"));
            d.setStatus(r.getString("status")); d.setSourceType(r.getString("source_type")); d.setAgentId(20L); d.setSdlcStepId(30L);
            return d;
        }, (Object)i.getArgument(0)).stream().findFirst().orElse(null));
        when(dao.listByWorkitem(1L,10L)).thenAnswer(i -> jdbc.queryForList("SELECT id FROM dispatch ORDER BY id", Long.class).stream().map(dao::findById).toList());
        var steps = mock(SdlcStepDao.class);
        step = new SdlcStepDO(); step.setId(30L); step.setName("编写验收摘要并等待批准"); step.setTenantId(1L); step.setSdlcId(40L); step.setGatePolicyJson("{\"humanApprovalRequired\":true,\"askWhenUnclear\":true}");
        when(steps.findById(30L)).thenReturn(step); when(steps.listBySdlc(40L)).thenReturn(List.of(step));
        recovery = mock(DispatchRecoveryService.class);
        var props = new JwtProperties(null); props.setSecret("test-only-dispatch-request-secret-1234567890"); jwt = new JwtService(props);
        var workitems = mock(WorkitemDao.class);
        var workitem = new WorkitemDO(); workitem.setId(10L); workitem.setTenantId(1L); workitem.setCreatorId(9L); workitem.setTitle("测试工单");
        when(workitems.findById(10L)).thenReturn(workitem);
        var workitemService = mock(WorkitemService.class);
        when(workitemService.getHumanParticipantIds(10L, 1L)).thenReturn(List.of(9L, 10L));
        @SuppressWarnings("unchecked") ObjectProvider<WorkitemService> workitemProvider = mock(ObjectProvider.class);
        when(workitemProvider.getObject()).thenReturn(workitemService);
        members = mock(WorkspaceMemberDao.class);
        var writer = member(9L, "READ_WRITE", "[]");
        var tagged = member(10L, "READ_WRITE", "[\"开发\"]");
        var reader = member(11L, "READ_ONLY", "[\"开发\"]");
        var admin = member(12L, "ADMIN", "[]");
        when(members.listByTenant(1L)).thenReturn(List.of(writer, tagged, reader, admin));
        for (var m : List.of(writer, tagged, reader, admin)) when(members.findByWorkspaceAndUser(1L, m.getUserId())).thenReturn(m);
        events = mock(ApplicationEventPublisher.class);
        users = mock(UserDao.class);
        service = new DispatchRequestService(jdbc,new DataSourceTransactionManager(ds),dao,steps,recovery,
                workitems, workitemProvider, members, users, events, v037Capability);
    }

    WorkspaceMemberDO member(long id, String access, String tags) {
        var member = new WorkspaceMemberDO(); member.setTenantId(1L); member.setUserId(id); member.setStatus(0);
        member.setAccessLevel(access); member.setIdentityTags(tags); return member;
    }

    JSONObject json(String raw) { return JSON.parseObject(raw); }
    DispatchRequestService.Request create(String kind, String key) {
        return service.create(100, new DispatchRequestService.Create(kind,key,"30",1,"Choose or wait",
                json("{\"type\":\"object\",\"properties\":{\"choice\":{\"type\":\"string\",\"enum\":[\"A\",\"B\"]}},\"required\":[\"choice\"]}"),
                json("{\"fingerprint\":\"" + "a".repeat(64) + "\"}"),0));
    }

    @Test void historyResolvesActorNamesInOneBatchAndPreservesRawAnswers() {
        var first = create("INPUT", "actor-nickname");
        service.reply(1, 10, first.id(), 9, new DispatchRequestService.Reply("accept", json("{\"choice\":\"原始答案 {0}\"}")));
        var second = create("INPUT", "actor-username");
        service.reply(1, 10, second.id(), 10, new DispatchRequestService.Reply("decline", null));
        var nickname = new com.aliyun.autowonder.user.UserDO();
        nickname.setId(9L); nickname.setNickname("回答人原名"); nickname.setUsername("login-nine");
        var username = new com.aliyun.autowonder.user.UserDO();
        username.setId(10L); username.setNickname(" "); username.setUsername("login-ten");
        when(users.listByIds(anyCollection())).thenReturn(List.of(nickname, username));
        var history = service.listForUser(1, 10, 9);
        assertEquals("login-ten", history.get(0).getString("actorName"));
        assertEquals("回答人原名", history.get(1).getString("actorName"));
        assertEquals("原始答案 {0}", history.get(1).getJSONObject("answer").getJSONObject("content").getString("choice"));
        verify(users).listByIds(List.of(10L, 9L));
        when(users.listByIds(anyCollection())).thenReturn(List.of());
        assertNull(service.listForUser(1, 10, 9).getFirst().getString("actorName"));
        assertTrue(service.listForUser(2, 10, 9).isEmpty());
    }

    @Test void historyIncludesEachHumanReplyTimeWithoutInventingTimesForUnansweredRequests() {
        for (String action : List.of("approve", "reject", "accept", "decline")) {
            var request = create(Set.of("approve", "reject").contains(action) ? "APPROVAL" : "INPUT", "time-" + action);
            assertNull(request.answeredAt());
            var before = Instant.now().minusSeconds(1);
            service.reply(1, 10, request.id(), 9, new DispatchRequestService.Reply(action,
                    json(Set.of("approve", "reject").contains(action) ? "{\"note\":\"原始意见 {0}\"}" : "{\"choice\":\"A\"}")));
            var answered = service.get(1, 100, request.id());
            assertNotNull(answered.answeredAt());
            assertTrue(answered.answeredAt().toInstant().isAfter(before));
            assertFalse(answered.answeredAt().toInstant().isAfter(Instant.now().plusSeconds(1)));
            assertEquals(answered.answeredAt().getTime(), service.listForUser(1, 10, 9).getFirst().getLong("answeredAt"));
            service.cancel(1, 100, request.id());
            service.reconcile();
            assertEquals(answered.answeredAt(), service.get(1, 100, request.id()).answeredAt());
        }
        var canceled = create("INPUT", "time-canceled");
        service.cancel(1, 100, canceled.id());
        assertNull(service.get(1, 100, canceled.id()).answeredAt());
    }

    @Test void reconciliationDoesNotOverwriteAConcurrentHumanReplyTime() {
        var request = create("APPROVAL", "time-during-sweep");
        var answeredAt = java.sql.Timestamp.from(Instant.parse("2026-10-12T06:04:47Z"));
        when(recovery.fenced(any())).thenAnswer(invocation -> {
            doReturn(false).when(recovery).fenced(any());
            service.reply(1, 10, request.id(), 9, new DispatchRequestService.Reply("reject", json("{\"note\":\"重新执行\"}")));
            jdbc.update("UPDATE dispatch_request SET gmt_modified=? WHERE id=?", answeredAt, request.id());
            return false;
        });
        service.reconcile();
        assertEquals(answeredAt, service.get(1, 100, request.id()).answeredAt());
        assertEquals("reject", service.get(1, 100, request.id()).answer().getString("action"));
    }

    @Test void questionAcceptsCustomTextOnlyOnceAndStopsOnPause() {
        var r=create("INPUT","question");
        assertThrows(BizException.class, () -> service.reply(1,10,r.id(),9,new DispatchRequestService.Reply("accept",json("{}"))));
        service.reply(1,10,r.id(),9,new DispatchRequestService.Reply("accept",json("{\"choice\":\"my own answer\"}")));
        assertEquals(9L,service.get(1,100,r.id()).actorId());
        assertThrows(BizException.class, () -> service.reply(1,10,r.id(),9,new DispatchRequestService.Reply("decline",null)));
        service.cancel(1,100,r.id()); assertEquals("RESOLVED",service.get(1,100,r.id()).status());
        var pending=create("INPUT","pending"); jdbc.update("UPDATE dispatch SET status='PAUSING'");
        assertEquals("CANCELED",service.get(1,100,pending.id()).status());
    }

    @Test void legacySchemaWithoutSourceTypeCanListAndReplyToHumanRequests() throws Exception {
        setup(new V037SchemaCapability(V037SchemaMode.LEGACY, V037MapperMode.LEGACY,
                false, false, false, Set.of("dispatch.source_type"), Instant.now()));
        var dispatch = dao.findById(100L);
        dispatch.setSourceType(null);
        doReturn(dispatch).when(dao).findById(100L);
        jdbc.execute("ALTER TABLE dispatch DROP COLUMN source_type");
        var request = create("APPROVAL", "legacy");
        assertEquals(request.id(), service.list(1, 10).getFirst().id());
        service.reply(1, 10, request.id(), 9, new DispatchRequestService.Reply("approve", null));
        assertEquals("RESOLVED", service.get(1, 100, request.id()).status());
    }

    @Test void sourceAwareQueriesExcludeScheduledTaskRequests() {
        var request = create("APPROVAL", "source-fence");
        jdbc.update("UPDATE dispatch SET source_type='SCHEDULED_TASK_RUN' WHERE id=100");
        assertTrue(service.list(1, 10).isEmpty());
        assertThrows(BizException.class, () -> service.reply(1, 10, request.id(), 9,
                new DispatchRequestService.Reply("approve", null)));
        assertEquals("PENDING", service.get(1, 100, request.id()).status());
    }

    @Test void legacyExternalRowsStayHiddenUntouchedAndExpireNaturally() {
        var input = create("INPUT", "human-input");
        jdbc.update("INSERT INTO dispatch_request (tenant_id,dispatch_id,kind,request_key,body_json,expires_at) "
                + "VALUES (1,100,'EXTERNAL','legacy-wait','{\"message\":\"old\"}',DATEADD('DAY',1,NOW()))");
        long legacy = jdbc.queryForObject("SELECT id FROM dispatch_request WHERE request_key='legacy-wait'", Long.class);
        // 人工列表不返回旧行，旧行也不能被当作人工请求回答
        assertEquals(List.of(input.id()), service.list(1, 10).stream().map(DispatchRequestService.Request::id).toList());
        assertEquals(List.of(input.id()), service.listForUser(1, 10, 9).stream().map(view -> view.getLong("id")).toList());
        var rejected = assertThrows(BizException.class, () -> service.reply(1, 10, legacy, 9,
                new DispatchRequestService.Reply("accept", json("{\"choice\":\"A\"}"))));
        assertEquals("10404", rejected.getCode());
        // 新创建一律拒绝 EXTERNAL
        assertThrows(BizException.class, () -> create("EXTERNAL", "new-wait"));
        // reconcile 不再读写旧行：源执行非活跃时人工请求被取消，旧行保持原样
        jdbc.update("UPDATE dispatch SET status='PAUSED' WHERE id=100");
        service.reconcile();
        assertEquals("CANCELED", service.get(1, 100, input.id()).status());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM dispatch_request WHERE id=?", String.class, legacy));
        assertNull(jdbc.queryForObject("SELECT answer_json FROM dispatch_request WHERE id=?", String.class, legacy));
        // 旧行仅由既有扫描按 expires_at 自然过期
        jdbc.update("UPDATE dispatch_request SET expires_at=DATEADD('SECOND',-1,NOW()) WHERE id=?", legacy);
        service.reconcile();
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM dispatch_request WHERE id=?", String.class, legacy));
        assertEquals("TIMEOUT", JSON.parseObject(jdbc.queryForObject("SELECT answer_json FROM dispatch_request WHERE id=?",
                String.class, legacy)).getString("outcome"));
    }

    @Test void integerAnswerRejectsFractionBeforeAcceptingWholeNumber() {
        var r = service.create(100, new DispatchRequestService.Create("INPUT", "count", "30", 1, "How many?",
                json("{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}"), null, 0));
        assertThrows(BizException.class, () -> service.reply(1,10,r.id(),9,
                new DispatchRequestService.Reply("accept", json("{\"count\":1.5}"))));
        service.reply(1,10,r.id(),9,new DispatchRequestService.Reply("accept", json("{\"count\":2}")));
        assertEquals("RESOLVED", service.get(1,100,r.id()).status());
    }

    @Test void approvalCannotBeAcceptedAsOrdinaryQuestion() {
        var r=create("APPROVAL","approve");
        assertThrows(BizException.class, () -> service.reply(1,10,r.id(),9,new DispatchRequestService.Reply("accept",json("{}"))));
        service.reply(1,10,r.id(),9,new DispatchRequestService.Reply("approve",json("{\"note\":\"reviewed\"}")));
        assertEquals("approve",service.get(1,100,r.id()).answer().getString("action"));
        assertTrue(service.requiresHumanRequests(dao.findById(100L)));
    }

    @Test void humanRequestsRequireCapableRuntimeOnlyWhenEnabled() {
        for (String policy : List.of("{}", "{\"humanApprovalRequired\":false,\"askWhenUnclear\":false}")) {
            step.setGatePolicyJson(policy);
            assertFalse(service.requiresHumanRequests(dao.findById(100L)));
        }
        for (String policy : List.of("{\"humanApprovalRequired\":true}", "{\"askWhenUnclear\":true}")) {
            step.setGatePolicyJson(policy);
            assertTrue(service.requiresHumanRequests(dao.findById(100L)));
        }
    }

    @Test void approvalWaitsWithoutExpiryButStillCancelsWithExecution() {
        var approval = create("APPROVAL", "indefinite");
        assertNull(approval.expiresAt());
        jdbc.update("UPDATE dispatch_request SET gmt_create=DATEADD('DAY',-7,NOW()) WHERE id=?", approval.id());
        service.reconcile();
        assertEquals("PENDING", service.get(1,100,approval.id()).status());
        service.reply(1,10,approval.id(),9,new DispatchRequestService.Reply("approve",json("{}")));
        assertEquals("RESOLVED", service.get(1,100,approval.id()).status());

        var canceled = create("APPROVAL", "canceled-gate");
        when(recovery.fenced(any())).thenReturn(true);
        service.reconcile();
        assertEquals("CANCELED", service.get(1,100,canceled.id()).status());
    }

    @Test void creatorScopedTokenCannotImpersonateHumanSession() {
        var controller = new DispatchRequestController(service,jwt);
        String capability = jwt.signScoped(9,1,"conversation",100,60);
        var error = assertThrows(BizException.class, () -> controller.reply(10,1,"Bearer " + capability,
                new DispatchRequestService.Reply("approve",json("{}"))));
        assertLocalized(error, 401, "必须由登录用户亲自确认", "A signed-in user must confirm this request personally.");
    }

    @Test void questionsAreOptInAndNotifyParticipantsOnlyOnceWithFullStepName() {
        for (String policy : List.of("{}", "{\"askWhenUnclear\":false}")) {
            step.setGatePolicyJson(policy);
            assertThrows(BizException.class, () -> create("INPUT", "disabled"));
        }
        verifyNoInteractions(events);
        step.setGatePolicyJson("{\"askWhenUnclear\":true}");
        var r = create("INPUT", "enabled");
        assertEquals(r.id(), create("INPUT", "enabled").id());
        assertEquals("编写验收摘要并等待批准", r.body().getString("stepName"));
        var event = org.mockito.ArgumentCaptor.forClass(DispatchRequestService.HumanRequestCreated.class);
        verify(events).publishEvent(event.capture());
        assertEquals(List.of(9L, 10L), event.getValue().recipients());
        assertEquals(step.getName(), event.getValue().stepName());
        assertFalse(service.listForUser(1, 10, 11).getFirst().getBoolean("canReply"));
        service.reply(1, 10, r.id(), 10, new DispatchRequestService.Reply("accept", json("{\"choice\":\"custom\"}")));
        assertEquals(10L, service.get(1, 100, r.id()).actorId());
    }

    @Test void defaultApprovalIsCreatorOnlyIncludingForAdminsAndRejectionRequiresReason() {
        var r = create("APPROVAL", "creator");
        for (long other : List.of(10L, 11L, 12L)) {
            assertFalse(service.listForUser(1, 10, other).getFirst().getBoolean("canReply"));
            assertThrows(BizException.class, () -> service.reply(1, 10, r.id(), other, new DispatchRequestService.Reply("approve", null)));
        }
        assertTrue(service.listForUser(1, 10, 9).getFirst().getBoolean("canReply"));
        for (JSONObject content : List.of(json("{}"), json("{\"note\":\"  \"}"), json("{\"note\":123}"))) {
            assertThrows(BizException.class, () -> service.reply(1, 10, r.id(), 9, new DispatchRequestService.Reply("reject", content)));
        }
        assertEquals("PENDING", service.get(1, 100, r.id()).status());
        service.reply(1, 10, r.id(), 9, new DispatchRequestService.Reply("reject", json("{\"note\":\"补充测试证据\"}")));
        var next = create("APPROVAL", "approve-without-note");
        service.reply(1, 10, next.id(), 9, new DispatchRequestService.Reply("approve", null));
        assertEquals("RESOLVED", service.get(1, 100, next.id()).status());
    }

    @Test void selectedUsersAndIdentityTagsResolveOnceAndStillRequireCurrentWriteAccess() {
        step.setGatePolicyJson("{\"humanApprovalRequired\":true,\"humanApprovers\":{\"userIds\":[12],\"identityTags\":[\"开发\"]}}");
        var r = create("APPROVAL", "tagged");
        var event = org.mockito.ArgumentCaptor.forClass(DispatchRequestService.HumanRequestCreated.class);
        verify(events).publishEvent(event.capture());
        assertEquals(List.of(10L, 12L), event.getValue().recipients());
        assertFalse(service.listForUser(1, 10, 9).getFirst().getBoolean("canReply"));
        assertTrue(service.listForUser(1, 10, 10).getFirst().getBoolean("canReply"));
        when(members.findByWorkspaceAndUser(1L, 10L)).thenReturn(member(10L, "READ_ONLY", "[\"开发\"]"));
        assertThrows(BizException.class, () -> service.reply(1, 10, r.id(), 10, new DispatchRequestService.Reply("approve", null)));
        // A retry must neither change the selected approvers nor notify them again.
        when(members.listByTenant(1L)).thenReturn(List.of());
        assertEquals(r.id(), create("APPROVAL", "tagged").id());
        verify(events, times(1)).publishEvent(any(Object.class));
        service.reply(1, 10, r.id(), 12, new DispatchRequestService.Reply("approve", null));
        assertEquals("RESOLVED", service.get(1, 100, r.id()).status());
    }

    @Test void readOnlyDispatchMembersAreExcludedFromApproverSelectionAndReplies() {
        step.setGatePolicyJson("{\"humanApprovalRequired\":true,\"humanApprovers\":{\"includeCreator\":true,\"userIds\":[10],\"identityTags\":[\"开发\"]}}");
        var taggedReadOnly = member(10L, "READ_WRITE", "[\"开发\"]");
        taggedReadOnly.setReadOnlyDomains("[\"DISPATCH\"]");
        when(members.listByTenant(1L)).thenReturn(List.of(member(9L, "READ_WRITE", "[]"), taggedReadOnly, member(12L, "ADMIN", "[]")));
        when(members.findByWorkspaceAndUser(1L, 10L)).thenReturn(taggedReadOnly);

        var r = create("APPROVAL", "read-only-candidate");
        var event = org.mockito.ArgumentCaptor.forClass(DispatchRequestService.HumanRequestCreated.class);
        verify(events).publishEvent(event.capture());
        // 角色只读 DISPATCH 的成员既不入审批名单，也不能回答。
        assertEquals(List.of(9L), event.getValue().recipients());
        assertTrue(service.listForUser(1, 10, 9).getFirst().getBoolean("canReply"));
        assertFalse(service.listForUser(1, 10, 10).getFirst().getBoolean("canReply"));
        assertThrows(BizException.class, () -> service.reply(1, 10, r.id(), 10, new DispatchRequestService.Reply("approve", null)));
        // 只读角色仍可查看请求，请求保持待处理。
        assertFalse(service.listForUser(1, 10, 10).isEmpty());
        assertEquals("PENDING", service.get(1, 100, r.id()).status());
    }

    @Test void approvalCreationFailsExplicitlyWhenAllCandidatesAreReadOnlyDispatch() {
        step.setGatePolicyJson("{\"humanApprovalRequired\":true,\"humanApprovers\":{\"identityTags\":[\"开发\"]}}");
        var taggedReadOnly = member(10L, "READ_WRITE", "[\"开发\"]");
        taggedReadOnly.setReadOnlyDomains("[\"DISPATCH\"]");
        when(members.listByTenant(1L)).thenReturn(List.of(taggedReadOnly));

        BizException error = assertThrows(BizException.class, () -> create("APPROVAL", "all-read-only"));
        assertEquals("dispatch_request.no_approvers", error.getMessageKey());
        verifyNoInteractions(events);
    }

    @Test void roleTurningReadOnlyAfterCreationRevokesReplyFromListAndEndpoint() {
        step.setGatePolicyJson("{\"askWhenUnclear\":true}");
        var r = create("INPUT", "revoke-after-create");
        assertTrue(service.listForUser(1, 10, 9).getFirst().getBoolean("canReply"));

        var nowReadOnly = member(9L, "READ_WRITE", "[]");
        nowReadOnly.setReadOnlyDomains("[\"DISPATCH\"]");
        when(members.findByWorkspaceAndUser(1L, 9L)).thenReturn(nowReadOnly);

        assertFalse(service.listForUser(1, 10, 9).getFirst().getBoolean("canReply"));
        assertThrows(BizException.class, () -> service.reply(1, 10, r.id(), 9, new DispatchRequestService.Reply("accept", json("{\"choice\":\"A\"}"))));
        assertEquals("PENDING", service.get(1, 100, r.id()).status());
        assertFalse(service.listForUser(1, 10, 9).isEmpty());
    }

    @Test void multipleChoiceAcceptsCustomValuesButRejectsEmptyRequiredSelection() {
        var r = service.create(100, new DispatchRequestService.Create("INPUT", "regions", "30", 1, "选择地域",
                json("{\"type\":\"object\",\"properties\":{\"regions\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"上海\",\"北京\"]}}},\"required\":[\"regions\"]}"), null, 0));
        assertThrows(BizException.class, () -> service.reply(1, 10, r.id(), 9, new DispatchRequestService.Reply("accept", json("{\"regions\":[]}"))));
        service.reply(1, 10, r.id(), 9, new DispatchRequestService.Reply("accept", json("{\"regions\":[\"上海\",\"深圳\"]}")));
        assertEquals("RESOLVED", service.get(1, 100, r.id()).status());
    }

    @Test void replyErrorsUseRequestLocaleWithoutChangingCodesDiagnosticsOrAnswers() {
        var approval = create("APPROVAL", "localized-approval");
        assertLocalized(assertThrows(BizException.class, () -> service.reply(1, 10, approval.id(), 10,
                new DispatchRequestService.Reply("approve", null))), 403,
                "你不在本次审批人中或没有工作空间写权限", "You are not a designated approver or do not have workspace write access.");
        assertLocalized(assertThrows(BizException.class, () -> service.reply(1, 10, approval.id(), 9,
                new DispatchRequestService.Reply("reject", json("{}")))), 400,
                "要求修改时必须填写修改意见", "Enter a note when requesting changes.");
        var question = create("INPUT", "localized-question");
        assertLocalized(assertThrows(BizException.class, () -> service.reply(1, 10, question.id(), 9,
                new DispatchRequestService.Reply("accept", json("{}")))), 400,
                "请填写所有必答项", "Complete all required answers.");
        var rawAnswer = json("{\"choice\":\"请填写所有必答项 {0} — O'Reilly\"}");
        service.reply(1, 10, question.id(), 9, new DispatchRequestService.Reply("accept", rawAnswer));
        assertLocalized(assertThrows(BizException.class, () -> service.reply(1, 10, question.id(), 9,
                new DispatchRequestService.Reply("accept", rawAnswer))), 409,
                "问题已回答或过期", "The question has already been answered or has expired.");
        assertEquals(rawAnswer, service.get(1, 100, question.id()).answer().getJSONObject("content"));
    }

    @Test void systemHeadingIntentSurvivesPersistenceAndRetryWithoutChangingTheRawQuestion() {
        var schema = json("{\"type\":\"object\",\"properties\":{\"choice\":{\"type\":\"string\",\"title\":\"原始问题 {0}\"}},\"x-autowonder-message-kind\":\"CONFIRMATION_REQUIRED\"}");
        var input = new DispatchRequestService.Create("INPUT", "system-heading", "30", 1, "需要你的确认", schema, null, 0);
        var request = service.create(100, input);
        assertEquals(request.id(), service.create(100, input).id());
        var event = org.mockito.ArgumentCaptor.forClass(DispatchRequestService.HumanRequestCreated.class);
        verify(events).publishEvent(event.capture());
        assertEquals("CONFIRMATION_REQUIRED", event.getValue().messageKind());
        assertEquals("需要你的确认", event.getValue().message());
        var stored = service.listForUser(1, 10, 9).getFirst().getJSONObject("body");
        assertEquals(schema, stored.getJSONObject("schema"));
        assertEquals("需要你的确认", stored.getString("message"));
    }

    @Test void fallbackLabelIntentsDoNotChangeRequestIdentity() {
        var approval = create("APPROVAL", "missing-user-name");
        var approver = approval.body().getJSONArray("approvers").getJSONObject(0);
        assertEquals("USER_ID", approver.getString("nameKind"));
        assertEquals(9L, approver.getLong("userId"));
        assertEquals(approval.id(), create("APPROVAL", "missing-user-name").id());
        var user = new com.aliyun.autowonder.user.UserDO(); user.setNickname("用户 9");
        when(users.findById(9L)).thenReturn(user);
        step.setName("当前任务");
        var rawNames = create("APPROVAL", "raw-names");
        assertNull(rawNames.body().getString("stepNameKind"));
        assertNull(rawNames.body().getJSONArray("approvers").getJSONObject(0).getString("nameKind"));
    }

    private static void assertLocalized(BizException error, int status, String diagnostic, String english) {
        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        try {
            for (var locale : List.of(java.util.Locale.CHINA, java.util.Locale.US)) {
                org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
                var response = handler.handleBiz(error);
                assertEquals(status, response.getStatusCode().value());
                assertEquals(error.getCode(), response.getBody().getCode());
                assertEquals(locale.equals(java.util.Locale.US) ? english : diagnostic, response.getBody().getMessage());
                assertEquals(diagnostic, error.getMessage());
                assertEquals(diagnostic, error.getLocalizedMessage());
            }
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }
}
