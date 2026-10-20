package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Personal MCP operations use the same owner directory and document permissions as the memory page. */
@Service
public class PersonalMcpMemoryService {
    private final MemoryDirectoryService directory;
    private final MemoryStoreApplicationService stores;
    private final MemoryDocumentDao documents;
    private final MemoryTopicService topics;

    public PersonalMcpMemoryService(MemoryDirectoryService directory, MemoryStoreApplicationService stores,
                                    MemoryDocumentDao documents, MemoryTopicService topics) {
        this.directory = directory;
        this.stores = stores;
        this.documents = documents;
        this.topics = topics;
    }

    public record MemoryView(Long id, Long storeId, String scope, long ownerRef, String path,
                             String type, String title, String description, String descriptionKind,
                             String contentMd, String body, Integer version) {}

    public List<MemoryView> search(long tenantId, long userId, boolean administrator, String keyword,
                                  String scope, Long ownerRef, String type, int page, int size) {
        String needle = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT).trim();
        int limit = Math.max(1, Math.min(size, 100));
        return directory.list(tenantId, userId, administrator).owners().stream()
                .filter(owner -> owner.storeId() != null)
                .filter(owner -> scope == null || scope.equals(owner.scope()))
                .filter(owner -> ownerRef == null || ownerRef == owner.ownerRef())
                .flatMap(owner -> stores.documentsForUser(tenantId, userId, administrator, owner.storeId())
                        .stream().map(document -> view(owner, document)))
                .filter(memory -> type == null || type.equals(memory.type()))
                .filter(memory -> needle.isEmpty() || matches(memory.title(), needle)
                        || matches(memory.contentMd(), needle) || matches(memory.path(), needle))
                .sorted(Comparator.comparing(MemoryView::storeId).thenComparing(MemoryView::path))
                .skip((long) (Math.max(page, 1) - 1) * limit).limit(limit).toList();
    }

    public MemoryView get(long tenantId, long userId, boolean administrator, long documentId) {
        MemoryDocumentDO document = documents.findById(tenantId, documentId);
        if (document == null || document.getDeletedAt() != null) throw new BizException(ErrorCode.NO_PERMISSION);
        MemoryDirectoryService.Owner owner = directory.list(tenantId, userId, administrator).owners().stream()
                .filter(item -> Objects.equals(item.storeId(), document.getStoreId()))
                .findFirst().orElseThrow(() -> new BizException(ErrorCode.NO_PERMISSION));
        return stores.documentsForUser(tenantId, userId, administrator, owner.storeId()).stream()
                .filter(item -> Objects.equals(item.getId(), documentId))
                .findFirst().map(item -> view(owner, item))
                .orElseThrow(() -> new BizException(ErrorCode.NO_PERMISSION));
    }

    public MemoryView create(long tenantId, long userId, boolean administrator, String scope, long ownerRef,
                             MemoryTopicRequest request) {
        var document = topics.createForUser(tenantId, userId, administrator, scope, ownerRef, request);
        return view(scope, "ORG".equals(scope) ? 0 : ownerRef, document);
    }

    public MemoryView update(long tenantId, long userId, boolean administrator, long id,
                             MemoryTopicRequest request) {
        var current = resolve(tenantId, userId, administrator, id);
        MemoryDocumentDO updated;
        if ("MEMORY.md".equals(current.path())) {
            updated = stores.mutateForUser(tenantId, userId, administrator, current.storeId(),
                    MemoryMutationRequest.update(current.path(), request.contentMd(),
                            request.expectedVersion(), request.idempotencyKey()));
        } else {
            updated = topics.updateForUser(tenantId, userId, administrator, current.storeId(),
                    new MemoryTopicRequest(current.path(), request.type(), request.title(), request.description(),
                            request.contentMd(), request.expectedVersion(), request.idempotencyKey()));
        }
        return view(current.scope(), current.ownerRef(), updated);
    }

    public void delete(long tenantId, long userId, boolean administrator, long id,
                       int expectedVersion, String idempotencyKey) {
        var document = resolve(tenantId, userId, administrator, id);
        topics.deleteForUser(tenantId, userId, administrator, document.storeId(), document.path(),
                expectedVersion, idempotencyKey);
    }

    private MemoryView resolve(long tenantId, long userId, boolean administrator, long id) {
        // Include tombstones: page mutations check write access before replaying their receipts.
        var document = documents.findById(tenantId, id);
        if (document == null) throw new BizException(ErrorCode.NO_PERMISSION);
        var owner = directory.list(tenantId, userId, administrator).owners().stream()
                .filter(item -> Objects.equals(item.storeId(), document.getStoreId()))
                .findFirst().orElseThrow(() -> new BizException(ErrorCode.NO_PERMISSION));
        return view(owner, document);
    }

    private MemoryView view(MemoryDirectoryService.Owner owner, MemoryDocumentDO document) {
        return view(owner.scope(), owner.ownerRef(), document);
    }

    private MemoryView view(String scope, long ownerRef, MemoryDocumentDO document) {
        MemoryDirectoryService.describe(document);
        return new MemoryView(document.getId(), document.getStoreId(), scope, ownerRef,
                document.getPath(), document.getMemoryType(), document.getTitle(), document.getDescription(),
                document.getDescriptionKind(), document.getContentMd(), document.getBody(), document.getVersion());
    }

    private boolean matches(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
