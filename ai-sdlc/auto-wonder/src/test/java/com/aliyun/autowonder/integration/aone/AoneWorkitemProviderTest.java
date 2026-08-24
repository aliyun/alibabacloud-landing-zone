package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalComment;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AoneWorkitemProviderTest {

    /** 2023-11-14T22:13:20Z, a whole-second timestamp so format/parse round-trips exactly. */
    private static final long BASE_MILLIS = 1_700_000_000_000L;

    @Test
    void createCommentExtractsCommentIdFromAoneMessage() {
        FakeAoneClient client = new FakeAoneClient();
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        ExternalComment comment = provider.createComment(config(), "84189105", "WORKER_1782377321313", "hello");

        assertEquals("124709025", comment.getExternalId());
        assertEquals("84189105", comment.getExternalWorkitemId());
        assertEquals("WORKER_1782377321313", comment.getAuthorStaffId());
        assertEquals("hello", comment.getContentMd());
    }

    @Test
    void createCommentPostsContentInFormBodyNotUrlQuery() {
        FakeAoneClient client = new FakeAoneClient();
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);
        // Long CJK content URL-encoded into a GET query string overflows the gateway URL
        // length limit; the content must travel in a POST form body instead.
        String longContent = "治理结论" + "x".repeat(2000);

        provider.createComment(config(), "84189105", "WORKER_1782377321313", longContent);

        assertEquals("/issue/openapi/IssueTopService/createComment", client.lastPostPath);
        assertEquals(longContent, client.lastPostQuery.get("content"));
        assertEquals("84189105", client.lastPostQuery.get("targetId"));
        assertEquals("WORKER_1782377321313", client.lastPostQuery.get("user"));
        assertNull(client.lastGetPath);
    }

    @Test
    void searchByIdsQueriesIdListInSinglePage() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(3, BASE_MILLIS, 1000);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        provider.searchByIds(config(), "2161074", List.of("1000", "1001"));

        assertEquals(1, client.callCount);
        assertEquals("2161074", client.postQueries.get(0).get("akProjectId"));
        assertEquals(List.of("1000", "1001"), client.postQueries.get(0).get("idList"));
    }

    @Test
    void searchByIdsBatchesRequestsToStayWithinIdListLimit() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(120, BASE_MILLIS, 1000);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            ids.add(String.valueOf(1000 + i));
        }

        PageResult<ExternalWorkitemSummary> page = provider.searchByIds(config(), "2161074", ids);

        assertEquals(3, client.callCount);
        for (Map<String, ?> query : client.postQueries) {
            List<?> idList = (List<?>) query.get("idList");
            assertTrue(idList.size() <= 50, "idList batch must not exceed 50 but was " + idList.size());
        }
        assertEquals(120, page.getItems().size());
        assertEquals(120, distinctIds(page));
        assertEquals(120, page.getTotalCount());
    }

    @Test
    void searchByIdsExactlyFiftyIdsUsesSingleRequest() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(50, BASE_MILLIS, 1000);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(String.valueOf(1000 + i));
        }

        PageResult<ExternalWorkitemSummary> page = provider.searchByIds(config(), "2161074", ids);

        assertEquals(1, client.callCount);
        assertEquals(50, page.getItems().size());
        assertEquals(50, page.getTotalCount());
    }

    @Test
    void searchByIdsEmptyIdsReturnsEmptyWithoutRequest() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(3, BASE_MILLIS, 1000);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        PageResult<ExternalWorkitemSummary> page = provider.searchByIds(config(), "2161074", List.of());

        assertEquals(0, client.callCount);
        assertEquals(0, page.getItems().size());
        assertEquals(0, page.getTotalCount());
    }

    @Test
    void searchProjectPagesThroughAllPagesWithinWindow() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(300, BASE_MILLIS, 1000);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        PageResult<ExternalWorkitemSummary> page = provider.searchProject(config(), "2161074", null, null);

        assertEquals(300, page.getItems().size());
        assertEquals(300, distinctIds(page));
    }

    @Test
    void searchProjectBisectsWindowToAvoidOffsetLimit() {
        WindowFakeClient client = new WindowFakeClient();
        // 6000 items exceed Aone's offset<=5000 cap. Linear paging would request page 27
        // (offset 5200) and the fake would throw; bisection must keep every window <= 5000.
        client.addItems(6000, BASE_MILLIS, 1000);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        PageResult<ExternalWorkitemSummary> page = provider.searchProject(config(), "2161074", null, null);

        assertEquals(6000, page.getItems().size());
        assertEquals(6000, distinctIds(page));
    }

    @Test
    void searchProjectReturnsPartialResultWhenLaterPageFails() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(400, BASE_MILLIS, 1000);
        client.failOnCall = 2; // first content page succeeds, second fails

        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        PageResult<ExternalWorkitemSummary> page = provider.searchProject(config(), "2161074", null, null);

        assertEquals(200, page.getItems().size());
    }

    @Test
    void searchProjectPropagatesFailureWhenFirstRequestFails() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(10, BASE_MILLIS, 1000);
        client.failOnCall = 1;

        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        assertThrows(IllegalStateException.class, () -> provider.searchProject(config(), "2161074", null, null));
    }

    @Test
    void searchProjectSendsCreatedAtFromWhenIncremental() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(1, BASE_MILLIS, 1000);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        provider.searchProject(config(), "2161074", new Date(BASE_MILLIS), null);

        Object sent = client.postQueries.get(0).get("createdAtFrom");
        assertNotNull(sent);
        assertTrue(sent.toString().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "createdAtFrom should be yyyy-MM-dd HH:mm:ss but was " + sent);
    }

    @Test
    void searchProjectFirstPageFetchesSinglePageOnly() {
        WindowFakeClient client = new WindowFakeClient();
        client.addItems(500, BASE_MILLIS, 1000);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        PageResult<ExternalWorkitemSummary> page = provider.searchProjectFirstPage(config(), "2161074");

        assertEquals(200, page.getItems().size());
        assertEquals(1, client.callCount);
    }

    @Test
    void listCommentsUsesNumericIssueIdsForAoneQuery() {
        FakeAoneClient client = new FakeAoneClient();
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        provider.listComments(config(), List.of("84199951", "84199952"));

        assertEquals("/issue/openapi/CommentTopService/get", client.lastGetPath);
        assertEquals("Issue", client.lastGetQuery.get("targetType"));
        assertEquals(List.of(84199951L, 84199952L), client.lastGetQuery.get("ids"));
    }

    @Test
    void listCommentsMapsCreatorIdentityAndUserDisplayName() {
        FakeAoneClient client = new FakeAoneClient();
        JSONObject comment = new JSONObject();
        comment.put("id", 124709025L);
        comment.put("targetId", 84189105L);
        comment.put("creator", "440501");
        comment.put("user", "煊童");
        comment.put("content", "请处理");
        client.comments.add(comment);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        ExternalComment mapped = provider.listComments(config(), List.of("84189105")).get(0);

        assertEquals("440501", mapped.getAuthorStaffId());
        assertEquals("煊童", mapped.getAuthorName());
        assertEquals("440501", mapped.getAuthor().getSubjectId());
        assertEquals("煊童", mapped.getAuthor().getDisplayName());
    }

    @Test
    void listCommentsResolvesInternalUserIdToStaffId() {
        FakeAoneClient client = new FakeAoneClient();
        JSONObject comment = new JSONObject();
        comment.put("id", 126034247L);
        comment.put("targetId", 85115148L);
        comment.put("userId", 48730503L);
        comment.put("content", "请处理");
        client.comments.add(comment);
        JSONObject user = new JSONObject();
        user.put("staffId", "320687");
        client.usersById.put("48730503", user);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        ExternalComment mapped = provider.listComments(config(), List.of("85115148")).get(0);

        assertEquals("48730503", mapped.getAuthorInternalUserId());
        assertEquals("320687", mapped.getAuthorStaffId());
        assertEquals("320687", mapped.getAuthor().getSubjectId());
        assertEquals("/ak/project/openapi/UserApiFacade/getById", client.lastGetPath);
        assertEquals("48730503", client.lastGetQuery.get("id"));
    }

    @Test
    void listCommentsKeepsBatchWhenAuthorLookupFails() {
        FakeAoneClient client = new FakeAoneClient();
        JSONObject comment = new JSONObject();
        comment.put("id", 126034247L);
        comment.put("targetId", 85115148L);
        comment.put("userId", 48730503L);
        client.comments.add(comment);
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        ExternalComment mapped = provider.listComments(config(), List.of("85115148")).get(0);

        assertEquals("48730503", mapped.getAuthorInternalUserId());
        assertEquals(null, mapped.getAuthor());
    }

    @Test
    void updateContentUsesAoneIssueUpdateFields() {
        FakeAoneClient client = new FakeAoneClient();
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        provider.updateContent(config(), "84189105", "WORKER_1782377321313", "新标题", "新正文");

        assertEquals("/issue/openapi/IssueTopService/update", client.lastPostPath);
        assertEquals("84189105", client.lastPostQuery.get("issueId"));
        assertEquals("WORKER_1782377321313", client.lastPostQuery.get("modifier"));
        assertEquals("新标题", client.lastPostQuery.get("subject"));
        assertEquals("新正文", client.lastPostQuery.get("description"));
    }

    @Test
    void updateContentPropagatesAoneUpdateFailure() {
        FakeAoneClient client = new FakeAoneClient();
        client.failUpdate = true;
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> provider.updateContent(config(), "84189105", "WORKER_1782377321313", "新标题", "新正文"));

        assertEquals("aone update failed", ex.getMessage());
        assertEquals("/issue/openapi/IssueTopService/update", client.lastPostPath);
        assertEquals("84189105", client.lastPostQuery.get("issueId"));
        assertEquals("WORKER_1782377321313", client.lastPostQuery.get("modifier"));
    }

    private long distinctIds(PageResult<ExternalWorkitemSummary> page) {
        return page.getItems().stream()
                .map(ExternalWorkitemSummary::getExternalId)
                .distinct()
                .count();
    }

    private AoneOpenApiConfig config() {
        return new AoneOpenApiConfig("http://aone-api.alibaba-inc.com", "auto-wonder", "secret", "1");
    }

    /**
     * Simulates Aone searchV4: filters an in-memory dataset by createdAt window, applies offset
     * paging, and throws when offset exceeds 5000 exactly like the real API. This lets tests prove
     * the provider's window bisection never over-scans past the offset cap.
     */
    private static class WindowFakeClient extends AoneOpenApiClient {
        private WindowFakeClient() {
            super(AoneClientTestSupport.enabledProperties());
        }

        private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        static {
            FMT.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        }

        private final List<long[]> data = new ArrayList<>(); // [id, createdAtMillis]
        private final List<Map<String, ?>> postQueries = new ArrayList<>();
        private int callCount;
        private int failOnCall = -1;

        void addItems(int count, long baseMillis, long stepMillis) {
            for (int i = 0; i < count; i++) {
                data.add(new long[]{1000 + data.size(), baseMillis + (long) i * stepMillis});
            }
        }

        @Override
        public JSONObject postForm(AoneOpenApiConfig config, String path, Map<String, ?> body) {
            callCount++;
            postQueries.add(body);
            if (failOnCall == callCount) {
                throw new IllegalStateException("aone searchV4 failed");
            }
            long from = parse(body.get("createdAtFrom"), Long.MIN_VALUE);
            long to = parse(body.get("createdAtTo"), Long.MAX_VALUE);
            int page = intVal(body.get("page"), 1);
            int perPage = intVal(body.get("perPage"), 200);
            int offset = (page - 1) * perPage;
            if (offset > 5000) {
                throw new IllegalStateException("not support offset value larger than 5000");
            }
            List<Long> idFilter = null;
            if (body.get("idList") instanceof List<?> idList) {
                if (idList.size() > 50) {
                    throw new IllegalStateException("idList max size 50");
                }
                idFilter = new ArrayList<>();
                for (Object id : idList) {
                    idFilter.add(Long.parseLong(String.valueOf(id)));
                }
            }
            List<Long> finalIdFilter = idFilter;
            List<long[]> filtered = data.stream()
                    .filter(r -> r[1] >= from && r[1] <= to)
                    .filter(r -> finalIdFilter == null || finalIdFilter.contains(r[0]))
                    .sorted(Comparator.comparingLong(r -> r[1]))
                    .collect(Collectors.toList());
            JSONArray issues = new JSONArray();
            for (int i = offset; i < Math.min(offset + perPage, filtered.size()); i++) {
                JSONObject issue = new JSONObject();
                issue.put("id", filtered.get(i)[0]);
                issue.put("subject", "item");
                issue.put("stamp", "Req");
                issues.add(issue);
            }
            JSONObject result = new JSONObject();
            result.put("result", issues);
            result.put("totalCount", filtered.size());
            result.put("totalPages", (filtered.size() + perPage - 1) / perPage);
            return result;
        }

        private long parse(Object value, long defaultMillis) {
            if (value == null) {
                return defaultMillis;
            }
            try {
                synchronized (FMT) {
                    return FMT.parse(value.toString()).getTime();
                }
            } catch (Exception e) {
                return defaultMillis;
            }
        }

        private int intVal(Object value, int defaultValue) {
            return value instanceof Number n ? n.intValue() : defaultValue;
        }
    }

    private static class FakeAoneClient extends AoneOpenApiClient {
        private FakeAoneClient() {
            super(AoneClientTestSupport.enabledProperties());
        }

        private Map<String, ?> lastPostQuery;
        private String lastPostPath;
        private String lastGetPath;
        private Map<String, ?> lastGetQuery;
        private boolean failUpdate;
        private final JSONArray comments = new JSONArray();
        private final Map<String, JSONObject> usersById = new HashMap<>();

        @Override
        public JSONObject get(AoneOpenApiConfig config, String path, Map<String, ?> query) {
            lastGetPath = path;
            lastGetQuery = query;
            JSONObject result = new JSONObject();
            if ("/issue/openapi/CommentTopService/get".equals(path)) {
                result.put("result", comments);
            } else if ("/ak/project/openapi/UserApiFacade/getById".equals(path)) {
                JSONObject user = usersById.get(String.valueOf(query.get("id")));
                if (user == null) {
                    throw new IllegalStateException("aone user lookup failed");
                }
                return user;
            } else {
                result.put("result", true);
                result.put("message", "comment id:124709025");
            }
            return result;
        }

        @Override
        public JSONObject postForm(AoneOpenApiConfig config, String path, Map<String, ?> body) {
            lastPostPath = path;
            lastPostQuery = body;
            if (failUpdate && "/issue/openapi/IssueTopService/update".equals(path)) {
                throw new IllegalStateException("aone update failed");
            }
            if ("/issue/openapi/IssueTopService/createComment".equals(path)) {
                JSONObject commentResult = new JSONObject();
                commentResult.put("result", true);
                commentResult.put("message", "comment id:124709025");
                return commentResult;
            }
            JSONObject issue = new JSONObject();
            issue.put("id", 84189105);
            issue.put("subject", "test request");
            issue.put("stamp", "Req");
            issue.put("status", "待处理");
            JSONArray issues = new JSONArray();
            issues.add(issue);
            JSONObject result = new JSONObject();
            result.put("result", issues);
            result.put("totalCount", 1);
            result.put("totalPages", 1);
            return result;
        }
    }
}
