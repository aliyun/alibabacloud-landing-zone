package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** User-facing topic CRUD: one transaction for body, index and change receipts. */
@Service
public class MemoryTopicService {
    private final MemoryStoreDao stores;
    private final MemoryDocumentDao documents;
    private final MemoryChangeDao changes;
    private final MemoryStoreAclDao acls;
    private final MemoryDocumentService mutations;
    private final MemoryStoreAccessService access;
    private final AgentDao agents;
    private final SquadDao squads;
    private final ObjectMapper json = new ObjectMapper();

    public MemoryTopicService(MemoryStoreDao stores, MemoryDocumentDao documents, MemoryChangeDao changes,
            MemoryStoreAclDao acls, MemoryDocumentService mutations,
            MemoryStoreAccessService access, AgentDao agents, SquadDao squads) {
        this.stores = stores; this.documents = documents; this.changes = changes; this.acls = acls;
        this.mutations = mutations; this.access = access; this.agents = agents; this.squads = squads;
    }

    @Transactional
    public MemoryDocumentDO createForUser(long tenantId, long userId, boolean administrator,
                                          String scope, long ownerRef, MemoryTopicRequest request) {
        validate(request);
        String name = ownerName(tenantId, scope, ownerRef);
        long owner = "ORG".equals(scope) ? 0 : ownerRef;
        MemoryStoreDO store = stores.findActive(tenantId, scope, owner);
        if (store == null) {
            if (!administrator) throw new MemoryStoreApplicationService.MemoryAccessDeniedException();
            store = ensureStore(tenantId, scope, owner, name, userId);
        }
        store = writable(tenantId, userId, administrator, store.getId());
        String path = "manual_" + hash(request.idempotencyKey()).substring(0, 20) + ".md";
        String fingerprint = fingerprint("CREATE", scope, owner, request);
        var prior = replay(tenantId, store.getId(), request.idempotencyKey(), fingerprint);
        if (prior != null) return prior;
        return writeTopic(tenantId, store.getId(), userId, path, request, Map.of(), fingerprint, true, "USER");
    }

    @Transactional
    public MemoryDocumentDO updateForUser(long tenantId, long userId, boolean administrator,
                                          long storeId, MemoryTopicRequest request) {
        validate(request);
        writable(tenantId, userId, administrator, storeId);
        String fingerprint = fingerprint("UPDATE", "", storeId, request);
        var prior = replay(tenantId, storeId, request.idempotencyKey(), fingerprint);
        if (prior != null) return prior;
        var current = requiredTopic(tenantId, storeId, request.path());
        return writeTopic(tenantId, storeId, userId, current.getPath(), request,
                MemoryTopicFormat.parse(current.getContentMd()).metadata(), fingerprint, false, "USER");
    }

    @Transactional
    public void deleteForUser(long tenantId, long userId, boolean administrator, long storeId,
                               String path, int expectedVersion, String idempotencyKey) {
        requireKey(idempotencyKey);
        writable(tenantId, userId, administrator, storeId);
        String fingerprint = fingerprint("DELETE", path, expectedVersion, null);
        if (replay(tenantId, storeId, idempotencyKey, fingerprint) != null) return;
        var current = requiredTopic(tenantId, storeId, path);
        var actor = new MemoryDocumentService.Actor("USER", String.valueOf(userId), userId, null, null);
        var index = documents.findByPath(tenantId, storeId, "MEMORY.md", false);
        if (index != null) writeIndex(tenantId, storeId, actor, index,
                MemoryTopicFormat.removeIndex(index.getContentMd(), path), idempotencyKey);
        mutations.mutateWithFingerprint(tenantId, storeId, MemoryMutationRequest.delete(current.getPath(),
                expectedVersion, idempotencyKey + ":topic"), actor, fingerprint);
    }

    MemoryStoreDO ensureStore(long tenantId, String scope, long owner, String name, long userId) {
        MemoryStoreDO store = new MemoryStoreDO();
        store.setTenantId(tenantId); store.setScope(scope); store.setOwnerRef(owner);
        store.setName(name); store.setCreatorId(userId);
        int created = stores.insertIfAbsent(store);
        store = stores.findActive(tenantId, scope, owner);
        if (store == null) throw new IllegalArgumentException("memory owner has an archived store");
        store = stores.lockById(tenantId, store.getId());
        if (created > 0 && !"AGENT".equals(scope)) {
            var acl = new MemoryStoreAclDO(); acl.setTenantId(tenantId); acl.setStoreId(store.getId());
            acl.setSubjectType("ORG".equals(scope) ? "ROLE" : "SQUAD");
            acl.setSubjectRef("ORG".equals(scope) ? "ALL_AGENTS" : String.valueOf(owner));
            acl.setPermission("READ"); acl.setCreatorId(userId); acls.insert(acl);
        }
        return store;
    }

    String ownerName(long tenantId, String scope, long ownerRef) {
        if ("ORG".equals(scope)) return "组织共享记忆";
        if ("AGENT".equals(scope)) {
            var agent = agents.findById(ownerRef);
            if (agent != null && Objects.equals(agent.getTenantId(), tenantId)
                    && !Integer.valueOf(1).equals(agent.getIsDeleted())) return agent.getName();
        } else if ("SQUAD".equals(scope)) {
            var squad = squads.findById(ownerRef);
            if (squad != null && Objects.equals(squad.getTenantId(), tenantId)
                    && !Integer.valueOf(1).equals(squad.getIsDeleted())) return squad.getName();
        }
        throw new IllegalArgumentException("memory owner does not exist in this workspace");
    }

    MemoryDocumentDO writeTopic(long tenantId, long storeId, long userId, String path,
            MemoryTopicRequest request, Map<String, Object> metadata, String fingerprint, boolean create, String actorType) {
        var actor = new MemoryDocumentService.Actor(actorType, String.valueOf(userId), userId, null, null);
        String content = MemoryTopicFormat.render(path.substring(0, path.length() - 3), request.type(),
                request.title(), request.description(), request.contentMd(), metadata);
        if (!create && request.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion required");
        var mutation = create ? MemoryMutationRequest.create(path, content, request.idempotencyKey() + ":topic")
                : MemoryMutationRequest.update(path, content, request.expectedVersion(), request.idempotencyKey() + ":topic");
        var topic = mutations.mutateWithFingerprint(tenantId, storeId, mutation, actor, fingerprint);
        var index = documents.findByPath(tenantId, storeId, "MEMORY.md", false);
        writeIndex(tenantId, storeId, actor, index, MemoryTopicFormat.upsertIndex(
                index == null ? "" : index.getContentMd(), path, request.title(), request.description()), request.idempotencyKey());
        return topic;
    }

    private void writeIndex(long tenantId, long storeId, MemoryDocumentService.Actor actor,
            MemoryDocumentDO index, String content, String key) {
        if (index != null && Objects.equals(index.getContentMd(), content)) return;
        mutations.mutate(tenantId, storeId, index == null
                ? MemoryMutationRequest.create("MEMORY.md", content, key + ":index")
                : MemoryMutationRequest.update("MEMORY.md", content, index.getVersion(), key + ":index"), actor);
    }

    private MemoryDocumentDO replay(long tenantId, long storeId, String key, String fingerprint) {
        var prior = changes.findByIdempotencyKey(tenantId, storeId, key + ":topic");
        if (prior == null) return null;
        if (!Objects.equals(prior.getRequestFingerprint(), fingerprint))
            throw new MemoryDocumentService.MemoryConflictException("idempotency key reused with different topic request");
        return documents.findById(tenantId, prior.getDocumentId());
    }

    private MemoryStoreDO writable(long tenantId, long userId, boolean administrator, long storeId) {
        var store = stores.lockById(tenantId, storeId);
        if (store == null || !"ACTIVE".equals(store.getStatus())) throw new IllegalArgumentException("active memory store not found");
        var subjects = new MemoryStoreAccessService.Subjects(userId, null, Set.of(), Set.of(), administrator);
        if (!access.resolve(store, acls.listByStore(tenantId, storeId), subjects).includes(MemoryPermission.WRITE))
            throw new MemoryStoreApplicationService.MemoryAccessDeniedException();
        return store;
    }

    private MemoryDocumentDO requiredTopic(long tenantId, long storeId, String path) {
        MemoryPathValidator.requireSafe(path);
        if ("MEMORY.md".equals(path)) throw new IllegalArgumentException("use index editor for MEMORY.md");
        var current = documents.findByPath(tenantId, storeId, path, false);
        if (current == null) throw new IllegalArgumentException("memory topic not found");
        return current;
    }

    private void validate(MemoryTopicRequest request) {
        if (request == null) throw new IllegalArgumentException("memory topic required");
        requireKey(request.idempotencyKey());
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 150) throw new IllegalArgumentException("invalid idempotency key");
    }

    private String fingerprint(String operation, String scope, long owner, MemoryTopicRequest request) {
        try { return hash(json.writeValueAsString(new Object[]{operation, scope, owner, request})); }
        catch (Exception e) { throw new IllegalArgumentException("invalid topic request", e); }
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
