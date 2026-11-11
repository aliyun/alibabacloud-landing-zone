package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.feature.PlatformFeatureSwitchDO;
import com.aliyun.autowonder.feature.PlatformFeatureSwitchDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkitemDuplicateDaoH2Test {

    private static final long TENANT = 71L;
    private static final long OTHER_TENANT = 72L;
    private static final String JDBC_URL =
            "jdbc:h2:mem:workitem_duplicate_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static WorkitemDao workitemDao;
    private static WorkitemDuplicateCheckDao checkDao;
    private static PlatformFeatureSwitchDao featureSwitchDao;

    @BeforeAll
    static void init() throws Exception {
        try (InputStream input = Resources.getResourceAsStream(
                "mybatis-workitem-duplicate-test-config.xml")) {
            SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(input));
            workitemDao = template.getMapper(WorkitemDao.class);
            checkDao = template.getMapper(WorkitemDuplicateCheckDao.class);
            featureSwitchDao = template.getMapper(PlatformFeatureSwitchDao.class);
        }
        executeScript("workitem-duplicate-schema-h2.sql");
    }

    @BeforeEach
    void clean() throws Exception {
        execute("DELETE FROM workitem", "DELETE FROM workitem_duplicate_check",
                "DELETE FROM platform_feature_switch", "DELETE FROM agent_conversation");
    }

    @Test
    void exactCandidateQueryScopesWorkspaceAndSkipsDeletedAndSelf() throws Exception {
        insertWorkitem(1, TENANT, "Alpha", 0, new Date());
        insertWorkitem(2, TENANT, "alpha", 0, new Date());
        insertWorkitem(3, TENANT, "Alpha", 1, new Date());
        insertWorkitem(4, OTHER_TENANT, "Alpha", 0, new Date());

        List<WorkitemDO> rows = workitemDao.listByExactTitleCandidate(TENANT, "alpha", 1L, 50);

        // 比较器语义随数据库而定（MySQL 默认不区分大小写），但空间、软删与自身排除必须成立。
        assertTrue(rows.stream().anyMatch(row -> Long.valueOf(2).equals(row.getId())));
        assertTrue(rows.stream().allMatch(row -> "alpha".equalsIgnoreCase(row.getTitle())));
        assertTrue(rows.stream().allMatch(row -> Long.valueOf(TENANT).equals(row.getTenantId())));
        assertTrue(rows.stream().noneMatch(row -> Long.valueOf(1).equals(row.getId())));
        assertTrue(rows.stream().noneMatch(row -> Long.valueOf(3).equals(row.getId())));
        assertTrue(rows.stream().noneMatch(row -> Long.valueOf(4).equals(row.getId())));
    }

    @Test
    void exactCandidateQueryTrimsStoredAndQueryTitle() throws Exception {
        insertWorkitem(5, TENANT, "  需求标题  ", 0, new Date());

        List<WorkitemDO> rows = workitemDao.listByExactTitleCandidate(TENANT, "需求标题", null, 50);

        assertEquals(1, rows.size());
        assertEquals("  需求标题  ", rows.get(0).getTitle());
    }

    @Test
    void exactCandidatePrefilterFindsTitlesWithSurroundingTabOrNewline() throws Exception {
        insertWorkitem(6, TENANT, "\t带空白标题\n", 0, new Date());

        List<WorkitemDO> rows = workitemDao.listByExactTitleCandidate(TENANT, "带空白标题", null, 50);

        assertEquals(1, rows.size());
    }

    @Test
    void streamIntentCandidatesReadsTheWholeWindowOnceWithoutPaging() throws Exception {
        Date now = new Date();
        insertWorkitem(10, TENANT, "old", 0, new Date(now.getTime() - 31L * 24 * 3600 * 1000));
        insertWorkitem(11, TENANT, "fresh-1", 0, new Date(now.getTime() - 2 * 3600_000L));
        insertWorkitem(12, TENANT, "fresh-2", 0, new Date(now.getTime() - 60_000L));
        insertWorkitem(13, OTHER_TENANT, "other", 0, new Date(now.getTime() - 60_000L));
        insertWorkitem(14, TENANT, "deleted", 1, new Date(now.getTime() - 60_000L));
        insertWorkitem(16, TENANT, "after-t0", 0, new Date(now.getTime() + 3600_000L));

        Date since = new Date(now.getTime() - 30L * 24 * 3600 * 1000);
        List<Long> ids = new ArrayList<>();
        workitemDao.streamIntentCandidates(TENANT, since, now, null, context -> ids.add(context.getResultObject().getId()));

        assertEquals(List.of(11L, 12L), ids);

        List<Long> withoutSelf = new ArrayList<>();
        workitemDao.streamIntentCandidates(TENANT, since, now, 12L,
                context -> withoutSelf.add(context.getResultObject().getId()));

        assertEquals(List.of(11L), withoutSelf);
    }

    @Test
    void duplicateCheckClaimRequiresTheCurrentAttempt() {
        WorkitemDuplicateCheckDO row = pendingRow(new Date(System.currentTimeMillis() + 60_000L));
        checkDao.insert(row);
        assertNotNull(row.getId());
        Date now = new Date();
        Date leaseUntil = new Date(now.getTime() + 60_000L);

        assertEquals(1, checkDao.claim(row.getId(), 0, leaseUntil, now));
        assertEquals(0, checkDao.claim(row.getId(), 0, leaseUntil, now));
        assertEquals(0, checkDao.saveSnapshot(row.getId(), 0, "[]", 0, now));
        assertEquals(1, checkDao.saveSnapshot(row.getId(), 1, "[]", 0, now));
        assertEquals(0, checkDao.saveSnapshot(row.getId(), 1, "[{\"id\":1,\"title\":\"x\"}]", 1, now));

        WorkitemDuplicateCheckDO stored = checkDao.findById(TENANT, row.getId());
        assertEquals("[]", stored.getCandidateSnapshotJson());
        assertEquals(Integer.valueOf(1), stored.getAttempt());
        assertNull(stored.getLeaseUntil());
    }

    @Test
    void finishRequiresTheClaimedAttemptAndRejectsLateSuccess() {
        Date now = new Date();
        WorkitemDuplicateCheckDO row = pendingRow(new Date(now.getTime() + 60_000L));
        checkDao.insert(row);
        Date leaseUntil = new Date(now.getTime() + 60_000L);
        assertEquals(1, checkDao.claim(row.getId(), 0, leaseUntil, now));

        assertEquals(0, checkDao.finish(row.getId(), 0, "SUCCEEDED", "[]", null, now));
        assertEquals(1, checkDao.finish(row.getId(), 1, "SUCCEEDED", "[{\"id\":1,\"reason\":\"r\"}]", null, now));
        assertEquals(0, checkDao.finish(row.getId(), 1, "FAILED", null, "late", now));

        WorkitemDuplicateCheckDO finished = checkDao.findById(TENANT, row.getId());
        assertEquals("SUCCEEDED", finished.getStatus());
        assertEquals("[{\"id\":1,\"reason\":\"r\"}]", finished.getMatchJson());
        assertNull(finished.getLeaseUntil());

        WorkitemDuplicateCheckDO late = pendingRow(new Date(now.getTime() - 1_000L));
        checkDao.insert(late);
        assertEquals(1, checkDao.claim(late.getId(), 0, leaseUntil, now));
        assertEquals(0, checkDao.finish(late.getId(), 1, "SUCCEEDED", "[]", null, now));
    }

    @Test
    void expireConvergesOnlyExpiredRowsAndQueuesOneBestEffortCancel() {
        Date now = new Date();
        WorkitemDuplicateCheckDO withRequest = pendingRow(new Date(now.getTime() - 1_000L));
        withRequest.setRequestId("11111111-1111-1111-1111-111111111111");
        checkDao.insert(withRequest);
        WorkitemDuplicateCheckDO withoutRequest = pendingRow(new Date(now.getTime() - 1_000L));
        checkDao.insert(withoutRequest);
        WorkitemDuplicateCheckDO stillRunning = pendingRow(new Date(now.getTime() + 60_000L));
        checkDao.insert(stillRunning);

        assertEquals(1, checkDao.expire(withRequest.getId(), "timeout", now));
        assertEquals(0, checkDao.expire(withRequest.getId(), "timeout", now));
        assertEquals(1, checkDao.expire(withoutRequest.getId(), "timeout", now));
        assertEquals(0, checkDao.expire(stillRunning.getId(), "timeout", now));

        WorkitemDuplicateCheckDO expired = checkDao.findById(TENANT, withRequest.getId());
        assertEquals("FAILED", expired.getStatus());
        assertEquals(Integer.valueOf(1), expired.getCancelPending());
        assertEquals("11111111-1111-1111-1111-111111111111", expired.getRequestId());
        assertEquals(Integer.valueOf(0), checkDao.findById(TENANT, withoutRequest.getId()).getCancelPending());

        assertEquals(List.of(withRequest.getId()), checkDao.listCancelPendingIds(10));
        assertEquals(1, checkDao.claimCancelPending(withRequest.getId()));
        assertEquals(0, checkDao.claimCancelPending(withRequest.getId()));
        assertTrue(checkDao.listCancelPendingIds(10).isEmpty());
    }

    @Test
    void quotasCountActiveRowsGloballyAndPerTenant() {
        checkDao.insert(pendingRow(new Date(System.currentTimeMillis() + 60_000L)));
        WorkitemDuplicateCheckDO otherTenant = pendingRow(new Date(System.currentTimeMillis() + 60_000L));
        otherTenant.setTenantId(OTHER_TENANT);
        checkDao.insert(otherTenant);
        WorkitemDuplicateCheckDO done = pendingRow(new Date(System.currentTimeMillis() + 60_000L));
        done.setStatus("SUCCEEDED");
        checkDao.insert(done);

        assertEquals(1L, checkDao.countActiveByTenant(TENANT));
        assertEquals(2L, checkDao.countActiveGlobal());
    }

    @Test
    void dueScanUsesTheCursorAndSkipsExpiredRows() {
        Date now = new Date();
        WorkitemDuplicateCheckDO first = pendingRow(new Date(now.getTime() + 60_000L));
        checkDao.insert(first);
        checkDao.insert(pendingRow(new Date(now.getTime() - 1_000L)));
        WorkitemDuplicateCheckDO third = pendingRow(new Date(now.getTime() + 60_000L));
        checkDao.insert(third);

        assertEquals(List.of(first.getId(), third.getId()), checkDao.listDueIds(0L, now, 10));
        assertEquals(List.of(third.getId()), checkDao.listDueIds(first.getId(), now, 10));
        assertEquals(3, checkDao.listExpiredIds(new Date(now.getTime() + 120_000L), 10).size());
    }

    @Test
    void lockedSwitchReadsTheGlobalRowAndTreatsMissingAsDisabled() {
        PlatformFeatureSwitchDO row = new PlatformFeatureSwitchDO();
        row.setSwitchKey("duplicateWorkitemIntentEnabled");
        row.setEnabled(1);
        row.setModifierId(1L);
        featureSwitchDao.insert(row);

        assertEquals(Integer.valueOf(1), checkDao.lockSwitchEnabled("duplicateWorkitemIntentEnabled"));
        assertNull(checkDao.lockSwitchEnabled("missingSwitch"));
    }

    @Test
    void intentConversationIsResolvedOnlyThroughTheRequestIdAssociation() throws Exception {
        WorkitemDuplicateCheckDO task = pendingRow(new Date(System.currentTimeMillis() + 60_000L));
        task.setRequestId("request-1");
        checkDao.insert(task);
        insertConversation(500, TENANT, "PLATFORM_INTERNAL", "request-1");
        insertConversation(501, TENANT, "DINGTALK", "request-1");
        insertConversation(502, TENANT, "PLATFORM_INTERNAL", "other-request");
        insertConversation(503, OTHER_TENANT, "PLATFORM_INTERNAL", "request-1");

        assertEquals(1L, checkDao.countIntentConversations(TENANT, 500));
        assertEquals(0L, checkDao.countIntentConversations(TENANT, 501));
        assertEquals(0L, checkDao.countIntentConversations(TENANT, 502));
        assertEquals(0L, checkDao.countIntentConversations(OTHER_TENANT, 503));
        assertEquals(0L, checkDao.countIntentConversations(TENANT, 999));
    }

    @Test
    void cancelledRowsKeepTheRequestIdForLaterDiagnosis() {
        WorkitemDuplicateCheckDO row = pendingRow(new Date(System.currentTimeMillis() + 60_000L));
        row.setRequestId("request-2");
        row.setWorkitemId(66L);
        checkDao.insert(row);

        assertEquals(1, checkDao.cancelActiveByWorkitem(TENANT, 66L, 9L));

        WorkitemDuplicateCheckDO stored = checkDao.findById(TENANT, row.getId());
        assertEquals("CANCELED", stored.getStatus());
        assertEquals("request-2", stored.getRequestId());
    }

    @Test
    void expiredDraftCleanupOnlyRemovesSubmittedFreeRows() {
        WorkitemDuplicateCheckDO draft = draftRow(null, "SUCCEEDED");
        WorkitemDuplicateCheckDO saved = draftRow(77L, "SUCCEEDED");
        checkDao.insert(draft);
        checkDao.insert(saved);

        List<Long> expired = checkDao.listExpiredDraftIds(new Date(System.currentTimeMillis() + 60_000L), 100);
        assertEquals(List.of(draft.getId()), expired);
    }

    @Test
    void featureSwitchUpdateRequiresMatchingVersion() {
        PlatformFeatureSwitchDO row = new PlatformFeatureSwitchDO();
        row.setSwitchKey("duplicateWorkitemIntentEnabled");
        row.setEnabled(0);
        row.setModifierId(1L);
        featureSwitchDao.insert(row);

        assertFalse(Integer.valueOf(1).equals(featureSwitchDao.findByKey("duplicateWorkitemIntentEnabled").getEnabled()));
        assertEquals(0, featureSwitchDao.updateEnabled("duplicateWorkitemIntentEnabled", 1, 5, 2L));
        assertEquals(1, featureSwitchDao.updateEnabled("duplicateWorkitemIntentEnabled", 1, 0, 2L));
        PlatformFeatureSwitchDO updated = featureSwitchDao.findByKey("duplicateWorkitemIntentEnabled");
        assertEquals(Integer.valueOf(1), updated.getEnabled());
        assertEquals(Integer.valueOf(1), updated.getVersion());
        assertEquals(Long.valueOf(2), updated.getModifierId());
    }

    private WorkitemDuplicateCheckDO pendingRow(Date deadline) {
        WorkitemDuplicateCheckDO row = new WorkitemDuplicateCheckDO();
        row.setTenantId(TENANT);
        row.setOperatorId(9L);
        row.setTitle("same title");
        row.setTitleFingerprint("f".repeat(64));
        row.setFormRequestId("form-1");
        row.setCandidateSince(new Date());
        row.setCandidateTotal(0);
        row.setNextBatchIndex(0);
        row.setStatus("PENDING");
        row.setDeadlineAt(deadline);
        row.setGmtCreate(new Date());
        row.setCancelPending(0);
        return row;
    }

    private WorkitemDuplicateCheckDO draftRow(Long workitemId, String status) {
        WorkitemDuplicateCheckDO row = pendingRow(new Date(System.currentTimeMillis() - 60_000L));
        row.setWorkitemId(workitemId);
        row.setTitle("draft title");
        row.setTitleFingerprint("a".repeat(64));
        row.setStatus(status);
        return row;
    }

    private void insertWorkitem(long id, long tenantId, String title, int deleted, Date created) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
             var statement = connection.prepareStatement(
                     "INSERT INTO workitem (id, tenant_id, work_type, title, status_node_id, creator_id,"
                             + " is_deleted, version, gmt_create, gmt_modified)"
                             + " VALUES (?, ?, 'REQ', ?, 100, 9, ?, 0, ?, ?)")) {
            statement.setLong(1, id);
            statement.setLong(2, tenantId);
            statement.setString(3, title);
            statement.setInt(4, deleted);
            Timestamp timestamp = new Timestamp(created.getTime());
            statement.setTimestamp(5, timestamp);
            statement.setTimestamp(6, timestamp);
            statement.executeUpdate();
        }
    }

    private void insertConversation(long id, long tenantId, String channel, String channelConversationId)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
             var statement = connection.prepareStatement(
                     "INSERT INTO agent_conversation (id, tenant_id, channel, channel_conversation_id)"
                             + " VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setLong(2, tenantId);
            statement.setString(3, channel);
            statement.setString(4, channelConversationId);
            statement.executeUpdate();
        }
    }

    private static void executeScript(String resource) throws Exception {
        String sql;
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        execute(sql.split(";"));
    }

    private static void execute(String... sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String item : sql) {
                if (!item.isBlank()) {
                    statement.execute(item);
                }
            }
        }
    }
}
