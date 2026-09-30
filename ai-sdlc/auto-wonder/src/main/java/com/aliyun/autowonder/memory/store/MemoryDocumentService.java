package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryDocumentService {
    private final MemoryStoreDao storeDao;
    private final MemoryDocumentDao documentDao;
    private final MemoryChangeDao changeDao;
    private final Clock clock;

    private static final Pattern MODIFIED_FIELD = Pattern.compile("(?m)^modified\\s*:\\s*.*$");
    private static final Pattern SHARED_SECRET = Pattern.compile(
            "(?is)(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
                    + "(?:password|passwd|secret|token|api[_-]?key|access[_-]?key)\\s*[:=]\\s*[^\\s]{6,}|"
                    + "\\b(?:sk|ghp|glpat)-[A-Za-z0-9_-]{12,})");

    public record Actor(String type, String ref, Long userId, Long dispatchId, String providerSessionId) {
    }

    @Autowired
    public MemoryDocumentService(MemoryStoreDao storeDao, MemoryDocumentDao documentDao,
                                 MemoryChangeDao changeDao) {
        this(storeDao, documentDao, changeDao, Clock.systemUTC());
    }

    MemoryDocumentService(MemoryStoreDao storeDao, MemoryDocumentDao documentDao,
                          MemoryChangeDao changeDao, Clock clock) {
        this.storeDao = storeDao;
        this.documentDao = documentDao;
        this.changeDao = changeDao;
        this.clock = clock;
    }

    @Transactional
    public MemoryDocumentDO mutate(long tenantId, long storeId, MemoryMutationRequest request, Actor actor) {
        requireRequest(request, actor);
        return mutateWithFingerprint(tenantId, storeId, request, actor, requestFingerprint(request));
    }

    /** Semantic topic operations have one stable identity across their topic and index transaction. */
    @Transactional
    public MemoryDocumentDO mutateWithFingerprint(long tenantId, long storeId, MemoryMutationRequest request,
                                                  Actor actor, String requestFingerprint) {
        requireRequest(request, actor);
        MemoryChangeDO prior = changeDao.findByIdempotencyKey(tenantId, storeId, request.idempotencyKey());
        if (prior != null) {
            if (prior.getRequestFingerprint() != null
                    && !prior.getRequestFingerprint().equals(requestFingerprint)) {
                throw new MemoryConflictException("idempotency key was already used for a different memory mutation");
            }
            return documentDao.findById(tenantId, prior.getDocumentId());
        }
        MemoryStoreDO store = storeDao.findById(tenantId, storeId);
        if (store == null || !"ACTIVE".equals(store.getStatus()) && store.getStatus() != null) {
            throw new IllegalArgumentException("active memory store not found");
        }

        return switch (request.operation()) {
            case CREATE -> create(tenantId, store, request, requestFingerprint, actor);
            case UPDATE -> update(tenantId, store, request, requestFingerprint, actor);
            case DELETE -> delete(tenantId, store, request, requestFingerprint, actor);
        };
    }

    private MemoryDocumentDO create(long tenantId, MemoryStoreDO store, MemoryMutationRequest request,
                                    String requestFingerprint, Actor actor) {
        String path = MemoryPathValidator.requireSafe(request.path());
        String content = normalizedContent(store, path, request.contentMd());
        MemoryDocumentDO existing = documentDao.findByPath(tenantId, store.getId(), path, true);
        if (existing != null && existing.getDeletedAt() == null) {
            throw new MemoryConflictException("memory document already exists");
        }
        if (existing != null) {
            int changed = documentDao.update(existing.getId(), tenantId, store.getId(), path, content,
                    sha256(content), (long) content.getBytes(StandardCharsets.UTF_8).length,
                    existing.getVersion(), actor.userId());
            if (changed != 1) {
                throw new MemoryConflictException("memory document version changed");
            }
            existing.setContentMd(content);
            existing.setContentSha256(sha256(content));
            existing.setByteSize((long) content.getBytes(StandardCharsets.UTF_8).length);
            existing.setDeletedAt(null);
            existing.setModifiedAt(Date.from(clock.instant()));
            existing.setVersion(existing.getVersion() + 1);
            advanceStore(tenantId, store, actor.userId());
            appendChange(tenantId, store, existing, "REVIVE", request.idempotencyKey(), requestFingerprint, actor);
            return existing;
        }
        MemoryDocumentDO document = new MemoryDocumentDO();
        populate(document, tenantId, store.getId(), path, content, actor);
        document.setVersion(1);
        documentDao.insert(document);
        advanceStore(tenantId, store, actor.userId());
        appendChange(tenantId, store, document, "CREATE", request.idempotencyKey(), requestFingerprint, actor);
        return document;
    }

    private MemoryDocumentDO update(long tenantId, MemoryStoreDO store, MemoryMutationRequest request,
                                    String requestFingerprint, Actor actor) {
        requireExpectedVersion(request);
        String path = MemoryPathValidator.requireSafe(request.path());
        String newPath = MemoryPathValidator.requireSafe(request.newPath());
        String content = normalizedContent(store, newPath, request.contentMd());
        MemoryDocumentDO current = requiredDocument(tenantId, store.getId(), path);
        if (!path.equals(newPath)) {
            requireUnreferenced(tenantId, store.getId(), path);
        }
        String digest = sha256(content);
        long size = content.getBytes(StandardCharsets.UTF_8).length;
        int changed = documentDao.update(current.getId(), tenantId, store.getId(), newPath,
                content, digest, size, request.expectedVersion(), actor.userId());
        if (changed != 1) {
            throw new MemoryConflictException("memory document version changed");
        }
        current.setPath(newPath);
        current.setContentMd(content);
        current.setContentSha256(digest);
        current.setByteSize(size);
        current.setModifiedAt(Date.from(clock.instant()));
        current.setVersion(request.expectedVersion() + 1);
        advanceStore(tenantId, store, actor.userId());
        appendChange(tenantId, store, current, "UPDATE", request.idempotencyKey(), requestFingerprint, actor);
        return current;
    }

    private MemoryDocumentDO delete(long tenantId, MemoryStoreDO store, MemoryMutationRequest request,
                                    String requestFingerprint, Actor actor) {
        requireExpectedVersion(request);
        String path = MemoryPathValidator.requireSafe(request.path());
        MemoryDocumentDO current = requiredDocument(tenantId, store.getId(), path);
        requireUnreferenced(tenantId, store.getId(), path);
        int changed = documentDao.eraseAndDelete(current.getId(), tenantId, store.getId(),
                request.expectedVersion(), actor.userId());
        if (changed != 1) {
            throw new MemoryConflictException("memory document version changed");
        }
        current.setVersion(request.expectedVersion() + 1);
        current.setDeletedAt(new Date());
        current.setContentMd("");
        current.setContentSha256("");
        current.setByteSize(0L);
        advanceStore(tenantId, store, actor.userId());
        appendChange(tenantId, store, current, "DELETE", request.idempotencyKey(), requestFingerprint, actor);
        return current;
    }

    private MemoryDocumentDO requiredDocument(long tenantId, long storeId, String path) {
        MemoryDocumentDO document = documentDao.findByPath(tenantId, storeId, path, false);
        if (document == null) {
            throw new IllegalArgumentException("memory document not found");
        }
        return document;
    }

    private void requireUnreferenced(long tenantId, long storeId, String path) {
        if ("MEMORY.md".equals(path)) return;
        MemoryDocumentDO index = documentDao.findByPath(tenantId, storeId, "MEMORY.md", false);
        if (index != null && index.getContentMd() != null
                && index.getContentMd().contains("](" + path + ")")) {
            throw new IllegalArgumentException("memory topic is still referenced by MEMORY.md");
        }
    }

    private void advanceStore(long tenantId, MemoryStoreDO store, Long modifierId) {
        if (storeDao.advanceRevision(tenantId, store.getId(), store.getVersion(), modifierId) != 1) {
            throw new MemoryConflictException("memory store revision changed");
        }
        store.setCurrentRevision(store.getCurrentRevision() + 1);
        store.setVersion(store.getVersion() + 1);
    }

    private void appendChange(long tenantId, MemoryStoreDO store, MemoryDocumentDO document,
                              String operation, String idempotencyKey, String requestFingerprint, Actor actor) {
        MemoryChangeDO change = new MemoryChangeDO();
        change.setTenantId(tenantId);
        change.setStoreId(store.getId());
        change.setDocumentId(document.getId());
        change.setStoreRevision(store.getCurrentRevision());
        change.setOperation(operation);
        change.setPath(document.getPath());
        change.setDocumentVersion(document.getVersion());
        change.setContentSha256("DELETE".equals(operation) ? null : document.getContentSha256());
        change.setActorType(actor.type());
        change.setActorRef(actor.ref());
        change.setDispatchId(actor.dispatchId());
        change.setProviderSessionId(actor.providerSessionId());
        change.setIdempotencyKey(idempotencyKey);
        change.setRequestFingerprint(requestFingerprint);
        changeDao.insert(change);
    }

    private String requestFingerprint(MemoryMutationRequest request) {
        return sha256(request.operation() + "\u0000" + request.path() + "\u0000"
                + String.valueOf(request.newPath()) + "\u0000" + String.valueOf(request.expectedVersion())
                + "\u0000" + String.valueOf(request.contentMd()));
    }

    private void populate(MemoryDocumentDO document, long tenantId, long storeId, String path,
                          String content, Actor actor) {
        document.setTenantId(tenantId);
        document.setStoreId(storeId);
        document.setPath(path);
        document.setContentMd(content);
        document.setContentSha256(sha256(content));
        document.setByteSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        document.setModifiedAt(Date.from(clock.instant()));
        document.setCreatorType(actor.type());
        document.setCreatorRef(actor.ref());
        document.setSourceDispatchId(actor.dispatchId());
        document.setSourceSessionId(actor.providerSessionId());
        document.setCreatorId(actor.userId());
    }

    private void requireRequest(MemoryMutationRequest request, Actor actor) {
        if (request == null || request.operation() == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("operation and idempotency key are required");
        }
        if (actor == null || actor.type() == null || actor.ref() == null) {
            throw new IllegalArgumentException("memory mutation actor is required");
        }
    }

    private void requireExpectedVersion(MemoryMutationRequest request) {
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("expected document version is required");
        }
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String normalizedContent(MemoryStoreDO store, String path, String content) {
        MemoryDocumentValidator.requireValid(path, content);
        if ("MEMORY.md".equals(path)) {
            Set<String> livePaths = documentDao.listLive(store.getTenantId(), store.getId()).stream()
                    .map(MemoryDocumentDO::getPath)
                    .collect(Collectors.toUnmodifiableSet());
            var violations = MemoryIndexPolicy.validateIndex(content,
                    target -> !"MEMORY.md".equals(target) && livePaths.contains(target));
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException("invalid memory index: " + violations);
            }
        } else {
            MemoryDocumentValidator.requireCanonicalTopic(path, content);
        }
        if (!"AGENT".equals(store.getScope()) && SHARED_SECRET.matcher(content).find()) {
            throw new IllegalArgumentException("secret-like content is not allowed in shared memory");
        }
        if (!content.startsWith("---\n")) {
            return content;
        }
        int closing = content.indexOf("\n---", 4);
        if (closing < 0) {
            throw new IllegalArgumentException("memory frontmatter is not closed");
        }
        String frontmatter = content.substring(4, closing);
        String modified = "modified: " + Instant.now(clock);
        Matcher matcher = MODIFIED_FIELD.matcher(frontmatter);
        frontmatter = matcher.find() ? matcher.replaceFirst(Matcher.quoteReplacement(modified))
                : frontmatter + (frontmatter.endsWith("\n") ? "" : "\n") + modified;
        return "---\n" + frontmatter + content.substring(closing);
    }

    public static class MemoryConflictException extends RuntimeException {
        public MemoryConflictException(String message) {
            super(message);
        }
    }
}
