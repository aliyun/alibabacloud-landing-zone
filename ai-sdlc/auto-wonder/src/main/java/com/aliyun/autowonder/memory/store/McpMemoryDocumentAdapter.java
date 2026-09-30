package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.memory.dto.UpdateMemoryRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Compatibility adapter for the deprecated MCP memory tool. */
@Service
public class McpMemoryDocumentAdapter {
    private static final Set<String> TYPES = Set.of("user", "feedback", "project", "reference");

    private final MemoryStoreBootstrapService stores;
    private final MemoryDocumentDao documents;
    private final MemoryStoreApplicationService mutations;

    public McpMemoryDocumentAdapter(MemoryStoreBootstrapService stores, MemoryDocumentDao documents,
                                    MemoryStoreApplicationService mutations) {
        this.stores = stores;
        this.documents = documents;
        this.mutations = mutations;
    }

    @Transactional
    public MemoryVO create(CreateMemoryRequest request, long tenantId, long dispatchId,
                           long workitemId, long agentId, long userId, String dedupeKey) {
        if (request == null || request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("memory title is required");
        }
        MemoryStoreDO store = stores.getOrCreate(tenantId, "AGENT", agentId,
                "Agent " + agentId + " memory", userId);
        String path = "mcp-" + digest(dedupeKey).substring(0, 20) + ".md";
        String title = cleanTitle(request.getTitle());
        String type = normalizeType(request.getType());
        String body = canonicalTopic(path, title, type, request.getContentMd());
        MemoryDocumentDO current = documents.findByPath(tenantId, store.getId(), path, false);
        MemoryMutationRequest topicMutation = current == null
                ? MemoryMutationRequest.create(path, body, dedupeKey + ":topic")
                : MemoryMutationRequest.update(path, body, current.getVersion(), dedupeKey + ":topic");
        MemoryDocumentDO topic = mutate(tenantId, dispatchId, agentId, userId, store.getId(), topicMutation);

        String entry = "- [" + title + "](" + path + ") — " + retrievalHook(request.getContentMd());
        MemoryDocumentDO index = documents.findByPath(tenantId, store.getId(), "MEMORY.md", false);
        String indexBody = index == null ? entry + "\n" : appendIndexEntry(index.getContentMd(), path, entry);
        if (index == null || !indexBody.equals(index.getContentMd())) {
            MemoryMutationRequest indexMutation = index == null
                    ? MemoryMutationRequest.create("MEMORY.md", indexBody, dedupeKey + ":index")
                    : MemoryMutationRequest.update("MEMORY.md", indexBody, index.getVersion(), dedupeKey + ":index");
            mutate(tenantId, dispatchId, agentId, userId, store.getId(), indexMutation);
        }

        MemoryVO result = new MemoryVO();
        result.setId(topic.getId());
        result.setScope("AGENT");
        result.setOwnerRef(agentId);
        result.setType(type);
        result.setTitle(title);
        result.setContentMd(topic.getContentMd());
        result.setStatus("ADOPTED");
        result.setSource("MCP_DOCUMENT");
        result.setSourceRef("dispatch:" + dispatchId + ":workitem:" + workitemId);
        result.setVersion(topic.getVersion());
        result.setGmtModified(new Date());
        return result;
    }

    public List<MemoryVO> search(long tenantId, long agentId, long userId, String keyword, int page, int size) {
        MemoryStoreDO store = stores.getOrCreate(tenantId, "AGENT", agentId,
                "Agent " + agentId + " memory", userId);
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return documents.listLive(tenantId, store.getId()).stream()
                .filter(document -> !"MEMORY.md".equals(document.getPath()))
                .map(document -> toVO(document, agentId))
                .filter(memory -> needle.isEmpty()
                        || memory.getContentMd().toLowerCase(Locale.ROOT).contains(needle)
                        || memory.getTitle().toLowerCase(Locale.ROOT).contains(needle))
                .skip((long) (safePage - 1) * safeSize).limit(safeSize).toList();
    }

    public MemoryVO get(long tenantId, long agentId, long userId, long documentId) {
        MemoryStoreDO store = stores.getOrCreate(tenantId, "AGENT", agentId,
                "Agent " + agentId + " memory", userId);
        return toVO(requiredDocument(tenantId, store.getId(), documentId), agentId);
    }

    @Transactional
    public MemoryVO update(long tenantId, long dispatchId, long agentId, long userId,
                           long documentId, UpdateMemoryRequest request, String idempotencyKey) {
        if (request == null || request.getScope() != null || request.getOwnerRef() != null) {
            throw new IllegalArgumentException("memory document scope cannot be changed");
        }
        MemoryStoreDO store = stores.getOrCreate(tenantId, "AGENT", agentId,
                "Agent " + agentId + " memory", userId);
        MemoryDocumentDO current = requiredDocument(tenantId, store.getId(), documentId);
        String title = request.getTitle() == null ? title(current) : cleanTitle(request.getTitle());
        String type = request.getType() == null ? type(current) : normalizeType(request.getType());
        String content = request.getContentMd() == null ? body(current) : request.getContentMd();
        MemoryDocumentDO updated = mutate(tenantId, dispatchId, agentId, userId, store.getId(),
                MemoryMutationRequest.update(current.getPath(), canonicalTopic(current.getPath(), title, type, content),
                        current.getVersion(), idempotencyKey));
        MemoryDocumentDO index = documents.findByPath(tenantId, store.getId(), "MEMORY.md", false);
        if (index != null) {
            String entry = "- [" + title + "](" + current.getPath() + ") — " + retrievalHook(content);
            String indexBody = replaceIndexEntry(index.getContentMd(), current.getPath(), entry);
            if (!Objects.equals(indexBody, index.getContentMd())) {
                mutate(tenantId, dispatchId, agentId, userId, store.getId(), MemoryMutationRequest.update(
                        "MEMORY.md", indexBody, index.getVersion(), idempotencyKey + ":index"));
            }
        }
        return toVO(updated, agentId);
    }

    @Transactional
    public void delete(long tenantId, long dispatchId, long agentId, long userId,
                       long documentId, String idempotencyKey) {
        MemoryStoreDO store = stores.getOrCreate(tenantId, "AGENT", agentId,
                "Agent " + agentId + " memory", userId);
        MemoryDocumentDO current = documents.findById(tenantId, documentId);
        if (current == null || !Objects.equals(current.getStoreId(), store.getId()) || "MEMORY.md".equals(current.getPath())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        if (current.getDeletedAt() != null) return;
        MemoryDocumentDO index = documents.findByPath(tenantId, store.getId(), "MEMORY.md", false);
        if (index != null) {
            String withoutEntry = removeIndexEntry(index.getContentMd(), current.getPath());
            if (!Objects.equals(withoutEntry, index.getContentMd())) {
                mutate(tenantId, dispatchId, agentId, userId, store.getId(), MemoryMutationRequest.update(
                        "MEMORY.md", withoutEntry, index.getVersion(), idempotencyKey + ":index"));
            }
        }
        mutate(tenantId, dispatchId, agentId, userId, store.getId(),
                MemoryMutationRequest.delete(current.getPath(), current.getVersion(), idempotencyKey + ":topic"));
    }

    private MemoryDocumentDO mutate(long tenantId, long dispatchId, long agentId, long userId, long storeId,
                                    MemoryMutationRequest request) {
        return mutations.mutateForExplicitAgent(tenantId, dispatchId, agentId, userId, storeId, request);
    }

    private String canonicalTopic(String path, String title, String type, String content) {
        String slug = path.substring(0, path.length() - 3);
        String description = retrievalHook(content);
        return "---\ntype: " + type + "\nname: " + slug + "\ndescription: " + yamlScalar(description)
                + "\ntitle: " + yamlScalar(title) + "\n---\n\n"
                + (content == null ? "" : content.trim()) + "\n";
    }

    private String appendIndexEntry(String content, String path, String entry) {
        String current = content == null ? "" : content;
        if (current.contains("](" + path + ")")) return current;
        return current + (current.isEmpty() || current.endsWith("\n") ? "" : "\n") + entry + "\n";
    }

    private String removeIndexEntry(String content, String path) {
        if (content == null) return "";
        return content.lines().filter(line -> !line.contains("](" + path + ")"))
                .reduce("", (left, line) -> left + line + "\n");
    }

    private String replaceIndexEntry(String content, String path, String entry) {
        if (content == null) return entry + "\n";
        boolean[] replaced = {false};
        String result = content.lines().map(line -> {
            if (line.contains("](" + path + ")")) {
                replaced[0] = true;
                return entry;
            }
            return line;
        }).reduce("", (left, line) -> left + line + "\n");
        return replaced[0] ? result : appendIndexEntry(result, path, entry);
    }

    private MemoryDocumentDO requiredDocument(long tenantId, long storeId, long documentId) {
        MemoryDocumentDO document = documents.findById(tenantId, documentId);
        if (document == null || !Objects.equals(document.getStoreId(), storeId)
                || "MEMORY.md".equals(document.getPath()) || document.getDeletedAt() != null) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return document;
    }

    private MemoryVO toVO(MemoryDocumentDO document, long agentId) {
        MemoryVO result = new MemoryVO();
        result.setId(document.getId());
        result.setScope("AGENT");
        result.setOwnerRef(agentId);
        result.setType(type(document));
        result.setTitle(title(document));
        result.setContentMd(body(document));
        result.setStatus("ADOPTED");
        result.setSource("MCP_DOCUMENT");
        result.setSourceRef(document.getSourceDispatchId() == null ? null : "dispatch:" + document.getSourceDispatchId());
        result.setVersion(document.getVersion());
        result.setGmtCreate(document.getGmtCreate());
        result.setGmtModified(document.getGmtModified());
        return result;
    }

    private String body(MemoryDocumentDO document) {
        String content = document.getContentMd() == null ? "" : document.getContentMd();
        int marker = content.indexOf("\n---\n", 4);
        return marker < 0 ? content : content.substring(marker + 5).stripLeading();
    }

    private String title(MemoryDocumentDO document) {
        String value = metadata(document.getContentMd(), "title");
        return value == null ? document.getPath().replaceFirst("\\.md$", "") : value;
    }

    private String type(MemoryDocumentDO document) {
        String value = metadata(document.getContentMd(), "type");
        return normalizeType(value);
    }

    private String metadata(String content, String key) {
        if (content == null) return null;
        for (String line : content.split("\\R")) {
            String directPrefix = key + ":";
            if (line.startsWith(directPrefix)) {
                return unquote(line.substring(directPrefix.length()).trim());
            }
            String prefix = "  " + key + ":";
            if (line.startsWith(prefix)) {
                return unquote(line.substring(prefix.length()).trim());
            }
        }
        return null;
    }

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private String cleanTitle(String value) {
        String cleaned = value.trim().replaceAll("[\\r\\n\\[\\]()`]", " ").replaceAll("\\s+", " ");
        return cleaned.isBlank() ? "Memory" : cleaned;
    }

    private String retrievalHook(String value) {
        String hook = value == null ? "Reusable memory from a prior task"
                : value.replaceAll("[\\r\\n`]+", " ").replaceAll("\\s+", " ").trim();
        if (hook.isEmpty()) hook = "Reusable memory from a prior task";
        int end = Math.min(hook.length(), 120);
        return hook.substring(0, end);
    }

    private String normalizeType(String value) {
        String type = value == null ? "reference" : value.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("memory type must be user, feedback, project, or reference");
        }
        return type;
    }

    private String yamlScalar(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
