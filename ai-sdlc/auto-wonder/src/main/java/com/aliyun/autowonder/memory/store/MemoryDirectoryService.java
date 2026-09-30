package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MemoryDirectoryService {
    private final AgentDao agents;
    private final SquadDao squads;
    private final SquadMemberDao members;
    private final MemoryStoreApplicationService stores;
    private final LegacyMemoryMigrationService migration;

    public MemoryDirectoryService(AgentDao agents, SquadDao squads, SquadMemberDao members,
            MemoryStoreApplicationService stores, LegacyMemoryMigrationService migration) {
        this.agents = agents; this.squads = squads; this.members = members; this.stores = stores; this.migration = migration;
    }

    public record Owner(String scope, long ownerRef, String name, List<String> squadNames, Long storeId,
                        String permission, Long currentRevision, boolean canCreate) {}
    public record Directory(List<Owner> owners, LegacyMemoryMigrationService.Progress migration) {}

    public Directory list(long tenantId, long userId, boolean administrator) {
        var visible = stores.listForUser(tenantId, userId, administrator).stream()
                .collect(Collectors.toMap(s -> s.getScope() + ":" + s.getOwnerRef(), Function.identity()));
        var squadList = squads.listByTenant(tenantId);
        var squadNames = squadList.stream().collect(Collectors.toMap(s -> s.getId(), s -> s.getName()));
        var agentList = agents.listByTenant(tenantId);
        var agentIds = agentList.stream().map(a -> a.getId()).toList();
        var memberList = agentIds.isEmpty() ? List.<com.aliyun.autowonder.squad.SquadMemberDO>of()
                : members.listByAgentIds(tenantId, agentIds);
        List<Owner> result = new ArrayList<>();
        result.add(owner("ORG", 0, "组织共享记忆", List.of(), visible, administrator));
        for (var squad : squadList) result.add(owner("SQUAD", squad.getId(), squad.getName(), List.of(), visible, administrator));
        for (var agent : agentList) {
            List<String> names = memberList.stream().filter(m -> Objects.equals(m.getAgentId(), agent.getId()))
                    .map(m -> squadNames.get(m.getSquadId())).filter(Objects::nonNull).distinct().toList();
            result.add(owner("AGENT", agent.getId(), agent.getName(), names, visible, administrator));
        }
        return new Directory(List.copyOf(result), administrator ? migration.progress(tenantId) : null);
    }

    private Owner owner(String scope, long id, String name, List<String> squads,
                         Map<String, MemoryStoreDO> visible, boolean administrator) {
        var store = visible.get(scope + ":" + id);
        return new Owner(scope, id, name, squads, store == null ? null : store.getId(),
                store == null ? null : store.getPermission(), store == null ? null : store.getCurrentRevision(),
                store == null ? administrator : "ADMIN".equals(store.getPermission()) || "WRITE".equals(store.getPermission()));
    }

    static void describe(MemoryDocumentDO document) {
        document.setTitle(document.getPath());
        document.setBody(document.getContentMd());
        if ("MEMORY.md".equals(document.getPath())) return;
        try {
            var topic = MemoryTopicFormat.parse(document.getContentMd());
            var metadata = topic.metadata();
            document.setTitle(text(metadata, "title", text(metadata, "name", document.getPath())));
            Object nestedType = metadata.get("metadata") instanceof Map<?, ?> nested ? nested.get("type") : null;
            document.setMemoryType(text(metadata, "type",
                    nestedType instanceof String type && !type.isBlank() ? type : "reference"));
            document.setDescription(text(metadata, "description", ""));
            document.setBody(topic.body());
        } catch (IllegalArgumentException | org.yaml.snakeyaml.error.YAMLException error) {
            // Existing malformed files remain viewable as-is; do not invent metadata.
            document.setDescription("元信息无法解析，可在文件详情中查看原文");
        }
    }

    private static String text(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
}
