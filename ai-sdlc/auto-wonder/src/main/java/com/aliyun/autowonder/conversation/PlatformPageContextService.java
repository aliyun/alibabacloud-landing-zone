package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.mcp.McpAccessTokenService;
import com.aliyun.autowonder.mcp.McpToolService;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only route identity is accepted from the browser; resource details are loaded under the owner identity. */
@Service
public class PlatformPageContextService {
    private final McpToolService tools;
    public PlatformPageContextService(McpToolService tools) { this.tools = tools; }

    public String snapshot(long workspaceId, long userId, long conversationId, String path) {
        if (path == null || path.length() > 256 || !path.matches("/[a-z0-9/-]*")) return null;
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("workspaceId", workspaceId);
        context.put("path", path);
        String[] parts = path.split("/");
        Map<String, String> readers = Map.of("workitems", "autowonder.get_workitem",
                "agents", "autowonder.get_agent", "sdlcs", "autowonder.get_sdlc",
                "repos", "autowonder.get_repo", "skills", "autowonder.get_skill",
                "scheduled-tasks", "autowonder.get_scheduled_task",
                "scheduled-task-runs", "autowonder.get_scheduled_task_run");
        if (parts.length >= 3 && readers.containsKey(parts[1]) && parts[2].matches("[1-9][0-9]{0,17}")) {
            long id = Long.parseLong(parts[2]);
            var principal = new McpAccessTokenService.Principal(workspaceId, userId, conversationId,
                    WorkspaceAccessLevel.READ_ONLY, McpAccessTokenService.CredentialType.CONVERSATION);
            Object resource = tools.call(principal, readers.get(parts[1]), Map.of("workspaceId", workspaceId, "id", id));
            Map<String, Object> raw = JSON.parseObject(JSON.toJSONString(resource));
            Map<String, Object> summary = new LinkedHashMap<>();
            for (String key : new String[]{"id", "title", "name", "status", "workType", "assigneeType", "assigneeRef", "contentMd", "description"}) {
                Object value = raw.get(key);
                if (value != null) summary.put(key, value instanceof String text && text.length() > 3000
                        ? text.substring(0, 3000) : value);
            }
            context.put("resourceType", parts[1]); context.put("resourceId", id); context.put("resource", summary);
        }
        return JSON.toJSONString(Map.of("platformPageContext", context));
    }
}
