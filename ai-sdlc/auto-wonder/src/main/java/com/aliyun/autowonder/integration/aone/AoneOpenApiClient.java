package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class AoneOpenApiClient {

    private static final Logger log = LoggerFactory.getLogger(AoneOpenApiClient.class);
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");
    private static final int COMMENT_IDENTITY_LOG_LIMIT = 20;
    private static final String[] COMMENT_IDENTITY_FIELDS = {
            "userStaffId", "staffId", "authorStaffId", "creatorStaffId", "operatorStaffId",
            "userId", "authorId", "creatorId", "id",
            "userName", "authorName", "creatorName", "nickName", "nickname", "displayName", "name"
    };

    private final AoneSignatureSigner signer;
    private final ClockMillis clock;
    private final AoneThrottle rateLimiter;
    private final OkHttpClient httpClient;
    private final AoneIntegrationProperties properties;

    @Autowired
    public AoneOpenApiClient(DistributedAoneRateLimiter rateLimiter,
                             AoneIntegrationProperties properties) {
        this(new AoneSignatureSigner(), System::currentTimeMillis, rateLimiter,
                new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build(), properties);
    }

    protected AoneOpenApiClient(AoneIntegrationProperties properties) {
        this(new AoneSignatureSigner(), System::currentTimeMillis, new AoneRateLimiter(),
                new OkHttpClient(), properties);
    }

    AoneOpenApiClient(AoneSignatureSigner signer, ClockMillis clock,
                      AoneThrottle rateLimiter, OkHttpClient httpClient,
                      AoneIntegrationProperties properties) {
        this.signer = signer;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public JSONObject postForm(AoneOpenApiConfig config, String path, Map<String, ?> form) {
        properties.requireEnabled();
        long timestamp = clock.now();
        String signature = signer.sign(config.clientKey(), config.accessSecret(), timestamp);
        String url = config.baseUrl() + path;
        // The body must be percent-encoded: raw values containing '&' or '=' would corrupt
        // form parsing, and comment writeback content routinely contains both.
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(AoneQueryString.toUrlEncodedQuery(form), FORM))
                .addHeader("clientKey", config.clientKey())
                .addHeader("timestamp", String.valueOf(timestamp))
                .addHeader("signature", signature)
                .addHeader("Ao-Region-Id", config.regionId() == null ? "1" : config.regionId())
                .build();
        return execute(request);
    }

    public JSONObject get(AoneOpenApiConfig config, String path, Map<String, ?> query) {
        properties.requireEnabled();
        long timestamp = clock.now();
        String signature = signer.sign(config.clientKey(), config.accessSecret(), timestamp);
        String qs = AoneQueryString.toUrlEncodedQuery(query);
        String url = config.baseUrl() + path + (qs.isEmpty() ? "" : "?" + qs);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("clientKey", config.clientKey())
                .addHeader("timestamp", String.valueOf(timestamp))
                .addHeader("signature", signature)
                .addHeader("Ao-Region-Id", config.regionId() == null ? "1" : config.regionId())
                .build();
        return execute(request);
    }

    private JSONObject execute(Request request) {
        rateLimiter.acquire();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            JSONObject json;
            try {
                json = JSON.parseObject(text);
            } catch (Exception e) {
                throw new AoneOpenApiException("Aone returned non-JSON response: HTTP " + response.code() + " " + text, e);
            }
            if (isWorkitemReadEndpoint(request)) {
                logWorkitemReadResponse(request, response.code(), json);
            }
            if (isCommentReadEndpoint(request)) {
                logCommentReadResponse(request, response.code(), json);
            }
            if (!response.isSuccessful() || !json.getBooleanValue("success")) {
                String message = json.getString("message");
                String detail = message == null || message.isBlank() ? text : message;
                throw new AoneOpenApiException(detail, isTerminalFailure(response.code(), detail));
            }
            Object result = json.get("result");
            if (result instanceof JSONObject object) {
                copyEnvelope(json, object);
                return object;
            }
            JSONObject wrapped = new JSONObject();
            wrapped.put("result", result);
            copyEnvelope(json, wrapped);
            return wrapped;
        } catch (IOException e) {
            throw new AoneOpenApiException("Aone request failed: " + e.getMessage(), e);
        }
    }

    private boolean isWorkitemReadEndpoint(Request request) {
        String path = request.url().encodedPath();
        return "/issue/openapi/IssueTopService/searchV4".equals(path)
                || "/issue/openapi/IssueTopService/getById".equals(path);
    }

    private boolean isCommentReadEndpoint(Request request) {
        return "/issue/openapi/CommentTopService/get".equals(request.url().encodedPath());
    }

    private void logWorkitemReadResponse(Request request, int httpStatus, JSONObject response) {
        String endpoint = request.url().encodedPath();
        Object result = response.get("result");
        if (result instanceof JSONArray issues) {
            JSONObject first = issues.isEmpty() || !(issues.get(0) instanceof JSONObject issue) ? null : issue;
            log.info("Aone API response endpoint={} httpStatus={} resultType=list itemCount={} totalCount={} pageSize={} firstIssueId={}",
                    endpoint, httpStatus, issues.size(), response.getInteger("totalCount"), response.getInteger("pageSize"),
                    first == null ? null : first.get("id"));
            return;
        }
        if (result instanceof JSONObject issue) {
            String description = issue.getString("description");
            log.info("Aone API response endpoint={} httpStatus={} resultType=issue issueId={} projectId={} descriptionLength={} "
                            + "reporterStaffId={} reporterNamePresent={} assigneeStaffId={} participantCount={} watcherCount={} updatedAt={}",
                    endpoint, httpStatus, issue.get("id"), issue.get("akProjectId"),
                    description == null ? 0 : description.length(), issue.get("userStaffId"),
                    issue.getString("user") != null, issue.get("assignedToStaffId"),
                    arraySize(issue.get("participantStaffIds")), arraySize(issue.get("trackerStaffIds")), issue.get("updatedAt"));
            return;
        }
        log.info("Aone API response endpoint={} httpStatus={} resultType={}", endpoint, httpStatus,
                result == null ? "null" : result.getClass().getSimpleName());
    }

    /**
     * Logs only commenter identity fields. Comment bodies are deliberately excluded to avoid
     * duplicating external-workitem content in application logs.
     */
    private void logCommentReadResponse(Request request, int httpStatus, JSONObject response) {
        Object result = response.get("result");
        if (!(result instanceof JSONArray comments)) {
            log.info("Aone comment API response endpoint={} httpStatus={} resultType={}",
                    request.url().encodedPath(), httpStatus,
                    result == null ? "null" : result.getClass().getSimpleName());
            return;
        }
        JSONArray identitySamples = new JSONArray();
        for (int index = 0; index < comments.size() && index < COMMENT_IDENTITY_LOG_LIMIT; index++) {
            Object item = comments.get(index);
            if (item instanceof JSONObject comment) {
                identitySamples.add(commentIdentitySummary(comment));
            }
        }
        log.info("Aone comment API response endpoint={} httpStatus={} commentCount={} identitySampleCount={} "
                        + "identitySamples={}",
                request.url().encodedPath(), httpStatus, comments.size(), identitySamples.size(),
                identitySamples.toJSONString());
    }

    private JSONObject commentIdentitySummary(JSONObject comment) {
        JSONObject summary = new JSONObject();
        summary.put("commentId", firstPresent(comment, "id", "commentId"));
        summary.put("externalWorkitemId", firstPresent(comment, "targetId", "issueId"));
        for (String field : COMMENT_IDENTITY_FIELDS) {
            copyIdentityField(summary, field, comment.get(field));
        }
        copyIdentityField(summary, "creator", comment.get("creator"));
        copyIdentityField(summary, "user", comment.get("user"));
        copyIdentityField(summary, "author", comment.get("author"));
        return summary;
    }

    private void copyIdentityField(JSONObject summary, String field, Object value) {
        if (value instanceof JSONObject identity) {
            JSONObject nested = new JSONObject();
            for (String identityField : COMMENT_IDENTITY_FIELDS) {
                if (identity.containsKey(identityField)) {
                    nested.put(identityField, identity.get(identityField));
                }
            }
            summary.put(field, nested);
            return;
        }
        if (value != null) {
            summary.put(field, value);
        }
    }

    private Object firstPresent(JSONObject source, String... fields) {
        for (String field : fields) {
            Object value = source.get(field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int arraySize(Object value) {
        return value instanceof JSONArray array ? array.size() : 0;
    }

    /**
     * Rate-limit rejections and 5xx are transient (the same call succeeds once the quota
     * frees up or the server recovers), so they stay retryable. Business rejections and
     * other 4xx (e.g. Aone's HTTP-200 {@code success:false} status-transition errors) can
     * never succeed on retry, so they are terminal and get dead-lettered by the dispatcher.
     */
    private boolean isTerminalFailure(int httpStatus, String message) {
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("rate limit") || lower.contains("over limit")) {
                return false;
            }
        }
        return httpStatus < 500;
    }

    private void copyEnvelope(JSONObject source, JSONObject target) {
        target.put("message", source.get("message"));
        target.put("messages", source.get("messages"));
        target.put("errorMessages", source.get("errorMessages"));
        target.put("totalCount", source.get("totalCount"));
        target.put("pageSize", source.get("pageSize"));
        target.put("toPage", source.get("toPage"));
        target.put("totalPages", source.get("totalPages"));
    }
}
