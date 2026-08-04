package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalComment;
import com.aliyun.autowonder.integration.provider.ExternalIssueType;
import com.aliyun.autowonder.integration.provider.ExternalStatusOption;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import com.aliyun.autowonder.integration.provider.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AoneWorkitemProvider implements ExternalWorkitemProvider {

    private static final Logger log = LoggerFactory.getLogger(AoneWorkitemProvider.class);

    private static final Pattern COMMENT_ID_PATTERN = Pattern.compile("comment\\s+id\\s*:?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Aone searchV4 caps perPage at 200. */
    private static final int PER_PAGE = 200;
    /** Aone rejects offset=(page-1)*perPage > 5000, capping a single window at 26 pages of 200. */
    private static final int WINDOW_MAX_ITEMS = 5000;
    private static final int MAX_PAGES = 26;
    /** 2000-01-01 00:00:00 Asia/Shanghai — lower bound when no createdFrom is supplied. */
    private static final long DEFAULT_EPOCH_MILLIS = 946656000000L;

    private final AoneOpenApiClient client;
    private final AoneWorkitemMapper mapper = new AoneWorkitemMapper();

    public AoneWorkitemProvider(AoneOpenApiClient client) {
        this.client = client;
    }

    @Override
    public String provider() {
        return "AONE";
    }

    @Override
    public PageResult<ExternalWorkitemSummary> searchByIds(AoneOpenApiConfig config, String externalProjectId, List<String> ids) {
        JSONObject result = searchPage(config, externalProjectId, ids, null, null, 1, PER_PAGE);
        return PageResult.of(toWorkitems(result), 1, PER_PAGE, result.getIntValue("totalCount"));
    }

    @Override
    public PageResult<ExternalWorkitemSummary> searchProjectFirstPage(AoneOpenApiConfig config, String externalProjectId) {
        JSONObject result = searchPage(config, externalProjectId, List.of(), null, null, 1, PER_PAGE);
        return PageResult.of(toWorkitems(result), 1, PER_PAGE, result.getIntValue("totalCount"));
    }

    @Override
    public PageResult<ExternalWorkitemSummary> searchProject(AoneOpenApiConfig config, String externalProjectId,
                                                             Date createdFrom, Date createdTo) {
        long from = createdFrom == null ? DEFAULT_EPOCH_MILLIS : createdFrom.getTime();
        long to = createdTo == null ? System.currentTimeMillis() : createdTo.getTime();
        Map<String, ExternalWorkitemSummary> collected = new LinkedHashMap<>();
        ScanState state = new ScanState();
        scanWindow(config, externalProjectId, from, to, collected, state);
        return PageResult.of(new ArrayList<>(collected.values()), 1, PER_PAGE, collected.size());
    }

    /**
     * Depth-first scan of a [from, to] createdAt window. Aone rejects offset>5000, so any window
     * whose totalCount exceeds WINDOW_MAX_ITEMS is bisected at its time midpoint until each window
     * is pageable within the offset cap. The first request failure propagates (nothing collected
     * yet); later failures are treated as partial success so already-fetched items survive.
     */
    private void scanWindow(AoneOpenApiConfig config, String externalProjectId, long from, long to,
                            Map<String, ExternalWorkitemSummary> collected, ScanState state) {
        Date fromDate = from <= DEFAULT_EPOCH_MILLIS ? null : new Date(from);
        Date toDate = new Date(to);
        JSONObject firstPage;
        try {
            firstPage = searchPage(config, externalProjectId, List.of(), fromDate, toDate, 1, PER_PAGE);
        } catch (RuntimeException e) {
            if (!state.firstDone) {
                throw e;
            }
            log.warn("Aone window scan failed, returning partial result from={} to={} error={}",
                    fromDate, toDate, e.getMessage());
            return;
        }
        state.firstDone = true;
        int totalCount = firstPage.getIntValue("totalCount");
        if (totalCount == 0) {
            return;
        }
        boolean canSplit = to - from > 1;
        if (totalCount > WINDOW_MAX_ITEMS && canSplit) {
            long mid = from + (to - from) / 2;
            scanWindow(config, externalProjectId, from, mid, collected, state);
            scanWindow(config, externalProjectId, mid + 1, to, collected, state);
            return;
        }
        addAll(firstPage, collected);
        int totalPages = pageCount(totalCount);
        for (int pageNo = 2; pageNo <= totalPages && pageNo <= MAX_PAGES; pageNo++) {
            JSONObject page;
            try {
                page = searchPage(config, externalProjectId, List.of(), fromDate, toDate, pageNo, PER_PAGE);
            } catch (RuntimeException e) {
                log.warn("Aone window page fetch failed, returning partial result from={} to={} page={} error={}",
                        fromDate, toDate, pageNo, e.getMessage());
                return;
            }
            addAll(page, collected);
        }
    }

    private void addAll(JSONObject result, Map<String, ExternalWorkitemSummary> collected) {
        for (ExternalWorkitemSummary item : toWorkitems(result)) {
            if (item.getExternalId() != null) {
                collected.putIfAbsent(item.getExternalId(), item);
            }
        }
    }

    private int pageCount(int totalCount) {
        return (totalCount + PER_PAGE - 1) / PER_PAGE;
    }

    private JSONObject searchPage(AoneOpenApiConfig config, String externalProjectId, List<String> ids,
                                  Date createdFrom, Date createdTo, int pageNo, int perPage) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("akProjectId", externalProjectId);
        if (ids != null && !ids.isEmpty()) {
            params.put("idList", ids);
        }
        params.put("stamp", "Req,Bug,Task");
        if (createdFrom != null) {
            params.put("createdAtFrom", formatTime(createdFrom));
        }
        if (createdTo != null) {
            params.put("createdAtTo", formatTime(createdTo));
        }
        params.put("page", pageNo);
        params.put("perPage", perPage);
        return client.postForm(config, "/issue/openapi/IssueTopService/searchV4", params);
    }

    private String formatTime(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return fmt.format(date);
    }

    private static final class ScanState {
        private boolean firstDone;
    }

    private List<ExternalWorkitemSummary> toWorkitems(JSONObject result) {
        List<ExternalWorkitemSummary> items = new ArrayList<>();
        for (Object item : arrayFrom(result)) {
            if (item instanceof JSONObject obj) {
                items.add(mapper.toDetail(obj));
            }
        }
        return items;
    }

    @Override
    public ExternalWorkitemDetail getWorkitem(AoneOpenApiConfig config, String externalWorkitemId) {
        JSONObject result = client.get(config, "/issue/openapi/IssueTopService/getById",
                Map.of("id", externalWorkitemId));
        return mapper.toDetail(result);
    }

    @Override
    public List<ExternalIssueType> listEnabledIssueTypes(AoneOpenApiConfig config, String akProjectId, String staffId,
                                                         String stamp) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("akProjectId", akProjectId);
        params.put("stamp", stamp);
        params.put("staffId", staffId);
        JSONObject result = client.get(config, "/issue/openapi/IssueTopService/getEnabledIssueTypes", params);
        List<ExternalIssueType> issueTypes = new ArrayList<>();
        for (Object item : arrayFrom(result)) {
            if (item instanceof JSONObject obj) {
                ExternalIssueType issueType = new ExternalIssueType();
                issueType.setExternalId(str(obj, "id"));
                issueType.setStamp(str(obj, "stamp"));
                issueType.setName(str(obj, "name"));
                issueTypes.add(issueType);
            }
        }
        return issueTypes;
    }

    @Override
    public List<ExternalStatusOption> listStatusRules(AoneOpenApiConfig config, String akProjectId, int issueTypeId) {
        JSONObject workflow = client.get(config, "/issue/openapi/IssueTopService/getTemplateAndWorkflowInfo",
                Map.of("akProjectId", akProjectId, "issueTypeId", issueTypeId));
        Integer workflowId = workflow.getInteger("workflowId");
        if (workflowId == null) {
            return List.of();
        }
        JSONObject result = client.get(config, "/issue/openapi/IssueTopService/getWorkflowStatusDetail",
                Map.of("akProjectId", akProjectId, "workflowId", workflowId));
        List<ExternalStatusOption> options = new ArrayList<>();
        List<JSONObject> statuses = new ArrayList<>();
        for (Object item : arrayFrom(result)) {
            if (item instanceof JSONObject status) {
                statuses.add(status);
            }
        }
        statuses.sort(Comparator.comparingInt(status -> status.getIntValue("position")));
        for (JSONObject status : statuses) {
            ExternalStatusOption option = new ExternalStatusOption();
            option.setExternalId(str(status, "id"));
            option.setName(str(status, "name"));
            options.add(option);
        }
        return options;
    }

    @Override
    public Map<String, List<ExternalStatusOption>> listOperationalStatuses(AoneOpenApiConfig config, String staffId, List<String> externalWorkitemIds) {
        if (staffId == null || staffId.isBlank() || externalWorkitemIds == null || externalWorkitemIds.isEmpty()) {
            return Map.of();
        }
        JSONObject result = client.get(config, "/issue/openapi/IssueTopService/getOperationalStatus",
                Map.of("staffId", staffId, "issueIds", numericIds(externalWorkitemIds)));
        Map<String, List<ExternalStatusOption>> statusesByIssue = new LinkedHashMap<>();
        Object data = result.get("result");
        if (data instanceof JSONObject obj) {
            for (String issueId : obj.keySet()) {
                List<ExternalStatusOption> statuses = new ArrayList<>();
                Object value = obj.get(issueId);
                if (value instanceof JSONArray array) {
                    for (Object item : array) {
                        if (item instanceof JSONObject statusObj) {
                            statuses.add(toStatusOption(statusObj));
                        }
                    }
                }
                statusesByIssue.put(issueId, statuses);
            }
        }
        return statusesByIssue;
    }

    @Override
    public List<ExternalComment> listComments(AoneOpenApiConfig config, List<String> externalWorkitemIds) {
        JSONObject result = client.get(config, "/issue/openapi/CommentTopService/get",
                Map.of("targetType", "Issue", "ids", numericIds(externalWorkitemIds)));
        List<ExternalComment> comments = new ArrayList<>();
        for (Object item : arrayFrom(result)) {
            if (item instanceof JSONObject obj) {
                comments.add(toComment(obj));
            }
        }
        return comments;
    }

    @Override
    public ExternalComment createComment(AoneOpenApiConfig config, String externalWorkitemId, String staffId, String contentMd) {
        JSONObject result = client.get(config, "/issue/openapi/IssueTopService/createComment",
                Map.of("targetType", "Issue", "targetId", externalWorkitemId, "user", staffId, "content", contentMd));
        ExternalComment comment = toComment(result);
        if (comment.getExternalId() == null) {
            comment.setExternalId(commentIdFromMessage(result.getString("message")));
        }
        comment.setExternalWorkitemId(externalWorkitemId);
        comment.setAuthorStaffId(staffId);
        comment.setContentMd(contentMd);
        return comment;
    }

    @Override
    public void updateStatus(AoneOpenApiConfig config, String externalWorkitemId, String staffId, String statusName) {
        client.postForm(config, "/issue/openapi/IssueTopService/update",
                Map.of("issueId", externalWorkitemId, "modifier", staffId, "status", statusName));
    }

    @Override
    public void updateContent(AoneOpenApiConfig config, String externalWorkitemId, String staffId, String title, String contentMd) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("issueId", externalWorkitemId);
        params.put("modifier", staffId);
        params.put("subject", title);
        params.put("description", contentMd);
        client.postForm(config, "/issue/openapi/IssueTopService/update", params);
    }

    private ExternalComment toComment(JSONObject obj) {
        ExternalComment comment = new ExternalComment();
        comment.setExternalId(firstNonBlank(str(obj, "id"), str(obj, "commentId")));
        comment.setExternalWorkitemId(firstNonBlank(str(obj, "targetId"), str(obj, "issueId")));
        comment.setAuthorStaffId(firstNonBlank(str(obj, "userStaffId"), str(obj, "staffId"), str(obj, "user")));
        comment.setAuthorName(firstNonBlank(str(obj, "userName"), str(obj, "authorName"), str(obj, "nickName")));
        comment.setContentMd(firstNonBlank(str(obj, "content"), str(obj, "body")));
        comment.setCreatedAt(date(firstNonBlank(str(obj, "createdAt"), str(obj, "gmtCreate"))));
        comment.setRawJson(obj.toJSONString());
        return comment;
    }

    private ExternalStatusOption toStatusOption(JSONObject obj) {
        ExternalStatusOption option = new ExternalStatusOption();
        option.setExternalId(firstNonBlank(str(obj, "id"), str(obj, "statusId")));
        option.setName(firstNonBlank(str(obj, "name"), str(obj, "status")));
        return option;
    }

    private List<Object> numericIds(List<String> ids) {
        List<Object> result = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                result.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                result.add(id);
            }
        }
        return result;
    }

    private JSONArray arrayFrom(JSONObject result) {
        Object data = result.get("result");
        if (data instanceof JSONArray array) return array;
        data = result.get("data");
        if (data instanceof JSONArray array) return array;
        data = result.get("list");
        if (data instanceof JSONArray array) return array;
        JSONArray array = new JSONArray();
        if (!result.isEmpty()) array.add(result);
        return array;
    }

    private Date date(String millis) {
        if (millis == null || millis.isBlank()) return null;
        try {
            return new Date(Long.parseLong(millis));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String str(JSONObject object, String key) {
        Object value = object.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String commentIdFromMessage(String message) {
        if (message == null || message.isBlank()) return null;
        Matcher matcher = COMMENT_ID_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }
}
