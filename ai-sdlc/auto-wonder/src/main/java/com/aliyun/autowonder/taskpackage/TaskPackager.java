package com.aliyun.autowonder.taskpackage;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Pure transform: PackageContext -> zip -> ObjectStorage. See detailed-design-02 §7.
 * B2 populates the context; this class only serializes, zips, uploads, and presigns.
 */
public class TaskPackager {

    private static final Logger log = LoggerFactory.getLogger(TaskPackager.class);
    private static final int DOWNLOAD_TTL_SECONDS = 600;
    private static final String CHECKPOINT_SOURCE_REVISION_SCHEMA = "autowonder.checkpointSourceRevision.v1";
    private static final String CHECKPOINT_FALLBACK = "checkpointFallback";

    private final ObjectStorage storage;
    private final String taskPkgBucket;
    private final Supplier<String> autowonderMcpUrlSupplier;

    public TaskPackager(ObjectStorage storage, String taskPkgBucket, String publicBaseUrl) {
        this(storage, taskPkgBucket, configuredMcpUrlSupplier(publicBaseUrl));
    }

    public TaskPackager(ObjectStorage storage, String taskPkgBucket, Supplier<String> autowonderMcpUrlSupplier) {
        this.storage = storage;
        this.taskPkgBucket = taskPkgBucket;
        if (autowonderMcpUrlSupplier == null) {
            throw new IllegalStateException("autowonder.public-base-url must be configured");
        }
        this.autowonderMcpUrlSupplier = autowonderMcpUrlSupplier;
    }

    private static Supplier<String> configuredMcpUrlSupplier(String publicBaseUrl) {
        String mcpUrl = normalizeBaseUrl(publicBaseUrl) + "/api/mcp";
        return () -> mcpUrl;
    }

    public TaskPackageResult build(PackageContext ctx) {
        log.info("taskpackage build start dispatchId={}", ctx.getDispatchId());
        byte[] zip = assembleZip(ctx);
        String sha256 = sha256Hex(zip);
        log.info("taskpackage zip created dispatchId={} size={} sha256={}", ctx.getDispatchId(), zip.length, sha256);
        String key = ctx.getTenantId() + "/" + ctx.getWorkitemId() + "/" + ctx.getDispatchId() + ".zip";
        StoredObject stored = storage.put(taskPkgBucket, key, zip);
        log.info("taskpackage uploaded dispatchId={} ossRef={}", ctx.getDispatchId(), stored.getOssRef());
        String url = storage.presignGet(stored.getOssRef(), DOWNLOAD_TTL_SECONDS);
        log.info("taskpackage download url ready dispatchId={} ossRef={} ttlSeconds={} downloadUrl={}",
                ctx.getDispatchId(), stored.getOssRef(), DOWNLOAD_TTL_SECONDS, url);
        return new TaskPackageResult(stored.getOssRef(), stored.getMd5(), stored.getSize(), url, sha256);
    }

    /** Build the capability-only package used by a workitem-independent conversation turn. */
    public TaskPackageResult buildConversationCapabilities(long tenantId, long conversationId,
            long turnId, long agentId, long agentVersionId, List<Map<String, Object>> capabilities) {
        return buildConversationCapabilities(tenantId, conversationId, turnId, agentId, agentVersionId,
                capabilities, List.of(), null);
    }

    public TaskPackageResult buildConversationCapabilities(long tenantId, long conversationId,
            long turnId, long agentId, long agentVersionId, List<Map<String, Object>> capabilities,
            List<Map<String, Object>> repos, Map<String, Object> repoMap) {
        PackageContext ctx = new PackageContext();
        ctx.setTenantId(tenantId);
        ctx.setWorkitemId(conversationId);
        ctx.setDispatchId(turnId);
        ctx.setAgentId(agentId);
        ctx.setAgentVersionId(agentVersionId);
        ctx.setSkills(capabilities);
        ctx.setRepos(repos);
        ctx.setRepoMap(repoMap);
        ConversationCapabilityArchive archive = assembleConversationCapabilityZip(ctx, conversationId, turnId);
        byte[] zip = archive.zip();
        String sha256 = sha256Hex(zip);
        String key = tenantId + "/conversations/" + conversationId + "/turns/" + turnId + "-v"
                + agentVersionId + ".zip";
        StoredObject stored = storage.put(taskPkgBucket, key, zip);
        String url = storage.presignGet(stored.getOssRef(), DOWNLOAD_TTL_SECONDS);
        return new TaskPackageResult(stored.getOssRef(), stored.getMd5(), stored.getSize(), url, sha256,
                archive.contentHash());
    }

    private ConversationCapabilityArchive assembleConversationCapabilityZip(PackageContext ctx,
            long conversationId, long turnId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Map<String, String> fileDigests = new LinkedHashMap<>();
        String contentHash;
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putEntry(zos, "skills.json", JSON.toJSONString(writeCapabilities(zos, ctx, fileDigests)), fileDigests);
            if (ctx.getRepos() != null && !ctx.getRepos().isEmpty()) {
                putEntry(zos, "repos.json", JSON.toJSONString(ctx.getRepos()), fileDigests);
            }
            if (ctx.getRepoMap() != null) {
                putEntry(zos, "repo-map.json", JSON.toJSONString(ctx.getRepoMap()), fileDigests);
            }
            contentHash = sha256Hex(JSON.toJSONString(new TreeMap<>(fileDigests))
                    .getBytes(StandardCharsets.UTF_8));
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", "autoWonder.taskPackage.v1");
            manifest.put("packageId", "conversation-capabilities:" + conversationId + ":" + turnId);
            manifest.put("tenantId", str(ctx.getTenantId()));
            manifest.put("workitemId", "conversation:" + conversationId);
            manifest.put("dispatchId", "conversation-turn:" + turnId);
            manifest.put("attempt", 1);
            manifest.put("agentId", str(ctx.getAgentId()));
            manifest.put("agentVersionId", str(ctx.getAgentVersionId()));
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("fileDigests", fileDigests);
            putEntry(zos, "manifest.json", JSON.toJSONString(manifest), fileDigests);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PACKAGE_BUILD_FAILED, e);
        }
        return new ConversationCapabilityArchive(baos.toByteArray(), contentHash);
    }

    private record ConversationCapabilityArchive(byte[] zip, String contentHash) {
    }

    private byte[] assembleZip(PackageContext ctx) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Map<String, String> fileDigests = new LinkedHashMap<>();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            List<Map<String, Object>> teammatesManifest = new ArrayList<>();
            writeTeammates(zos, ctx, teammatesManifest, fileDigests);
            List<Map<String, Object>> requirementDocumentsManifest = writeRequirementDocuments(zos, ctx, fileDigests);

            putEntry(zos, "workitem.md", nz(ctx.getWorkitemTitle()) + "\n\n" + nz(ctx.getWorkitemContentMd()), fileDigests);
            if (ctx.getClarificationMd() != null && !ctx.getClarificationMd().isBlank()) {
                putEntry(zos, "clarification.md", ctx.getClarificationMd(), fileDigests);
            }
            if (ctx.getCommentsMd() != null && !ctx.getCommentsMd().isBlank()) {
                putEntry(zos, "comments.md", ctx.getCommentsMd(), fileDigests);
            }
            if (ctx.getInteractionContextMd() != null && !ctx.getInteractionContextMd().isBlank()) {
                putEntry(zos, "interaction-context.md", ctx.getInteractionContextMd(), fileDigests);
            }
            putEntry(zos, "identity.json", JSON.toJSONString(orEmptyMap(ctx.getIdentity())), fileDigests);
            putEntry(zos, "repos.json", JSON.toJSONString(Map.of("repos", resolveRepos(ctx))), fileDigests);
            if (ctx.getRepoMap() != null) {
                putEntry(zos, "repo-map.json", JSON.toJSONString(ctx.getRepoMap()), fileDigests);
            }
            putEntry(zos, "skills.json", JSON.toJSONString(writeCapabilities(zos, ctx, fileDigests)), fileDigests);
            putEntry(zos, "sdlc.json", JSON.toJSONString(orEmptyMap(ctx.getSdlc())), fileDigests);
            if (ctx.getRoster() != null) {
                putEntry(zos, "roster.json", JSON.toJSONString(ctx.getRoster()), fileDigests);
            }
            if (ctx.getWorkitemStatus() != null && !ctx.getWorkitemStatus().isEmpty()) {
                putEntry(zos, "workitem-status.json", JSON.toJSONString(ctx.getWorkitemStatus()), fileDigests);
            }
            if (ctx.getMemory() != null) {
                for (Map.Entry<String, String> e : ctx.getMemory().entrySet()) {
                    putEntry(zos, "memory/" + e.getKey() + ".md", nz(e.getValue()), fileDigests);
                }
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", "autoWonder.taskPackage.v1");
            manifest.put("packageId", "pkg_" + ctx.getDispatchId());
            manifest.put("tenantId", str(ctx.getTenantId()));
            manifest.put("workitemId", str(ctx.getWorkitemId()));
            manifest.put("workType", nz(ctx.getWorkType()));
			manifest.put("taskPatternKey", nz(ctx.getTaskPatternKey()));
			manifest.put("sessionRole", nz(ctx.getSessionRole()));
			manifest.put("trialId", nz(ctx.getTrialId()));
			manifest.put("trialArm", nz(ctx.getTrialArm()));
            manifest.put("dispatchId", str(ctx.getDispatchId()));
            manifest.put("sourceDispatchId", str(ctx.getSourceDispatchId()));
            manifest.put("attempt", ctx.getAttempt() == null ? 1 : ctx.getAttempt());
            manifest.put("idempotencyKey", nz(ctx.getIdempotencyKey()));
            manifest.put("sdlcId", str(ctx.getSdlcId()));
            manifest.put("sdlcStepId", str(ctx.getSdlcStepId()));
            manifest.put("agentId", str(ctx.getAgentId()));
            manifest.put("agentVersionId", str(ctx.getAgentVersionId()));
            manifest.put("executorId", str(ctx.getExecutorId()));
            manifest.put("roleCode", nz(ctx.getRoleCode()));
            manifest.put("roleName", nz(ctx.getRoleName()));
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("fileDigests", fileDigests);
            manifest.put("teammates", teammatesManifest);
            manifest.put("requirementDocuments", requirementDocumentsManifest);
            putEntry(zos, "manifest.json", JSON.toJSONString(manifest), fileDigests);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PACKAGE_BUILD_FAILED, e);
        }
        return baos.toByteArray();
    }

    private Map<String, Object> writeCapabilities(ZipOutputStream zos, PackageContext ctx,
                                                   Map<String, String> fileDigests) throws Exception {
        List<Map<String, Object>> skills = new ArrayList<>();
        List<Map<String, Object>> plugins = new ArrayList<>();
        List<Map<String, Object>> mcpServers = new ArrayList<>();
        mcpServers.add(builtinAutowonderMcp());
        Set<String> names = new HashSet<>();
        names.add("MCP:autowonder");

        for (Map<String, Object> capability : ctx.getSkills() == null ? List.<Map<String, Object>>of() : ctx.getSkills()) {
            String type = requiredString(capability, "type").toUpperCase(Locale.ROOT);
            String name = requiredCapabilityName(capability);
            // The platform-provided AutoWonder MCP is authoritative. An explicit binding with
            // the reserved name must not override it or make package creation fail.
            if ("MCP".equals(type) && "autowonder".equalsIgnoreCase(name)) {
                continue;
            }
            if (!names.add(type + ":" + name)) {
                throw new IllegalArgumentException("duplicate capability " + type + ":" + name);
            }
            if ("MCP".equals(type)) {
                mcpServers.add(mcpDescriptor(capability, name));
                continue;
            }
            if (!"SKILL".equals(type) && !"PLUGIN".equals(type)) {
                throw new IllegalArgumentException("unsupported capability type " + type);
            }
            String base = "capabilities/" + ("SKILL".equals(type) ? "skills/" : "plugins/") + name;
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("id", String.valueOf(capability.get("id")));
            descriptor.put("name", name);
            descriptor.put("path", base);
            descriptor.put("version", String.valueOf(capability.getOrDefault("version", 0)));
            descriptor.put("required", !Boolean.FALSE.equals(capability.get("required")));
            String ossRef = stringValue(capability.get("packageOssRef"));
            if (ossRef.isEmpty()) {
                if (!"SKILL".equals(type)) {
                    throw new IllegalArgumentException("plugin package is required: " + name);
                }
                byte[] generated = generatedSkill(capability, name);
                putEntryBytes(zos, base + "/SKILL.md", generated, fileDigests);
                descriptor.put("sha256", sha256Hex(generated));
            } else {
                byte[] archive = storage.get(ossRef);
                if (archive == null) {
                    if ("SKILL".equals(type) && !storage.exists(ossRef)) {
                        log.warn("skip deleted skill package capabilityId={} name={} ossRef={}",
                                capability.get("id"), name, ossRef);
                        continue;
                    }
                    throw new IllegalArgumentException("capability package is unavailable: " + name);
                }
                extractCapability(zos, archive, base, fileDigests, "SKILL".equals(type));
                descriptor.put("sha256", sha256Hex(archive));
            }
            if ("PLUGIN".equals(type)) {
                Map<String, Object> config = config(capability);
                Object providers = config.get("providers");
                if (!(providers instanceof Collection) || ((Collection<?>) providers).isEmpty()) {
                    throw new IllegalArgumentException("plugin providers are required: " + name);
                }
                descriptor.put("providers", providers);
                plugins.add(descriptor);
            } else {
                skills.add(descriptor);
            }
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", "autowonder.capabilities.v1");
        document.put("skills", skills);
        document.put("mcpServers", mcpServers);
        document.put("plugins", plugins);
        return document;
    }

    private Map<String, Object> builtinAutowonderMcp() {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "autowonder");
        server.put("transport", "http");
        server.put("url", normalizeMcpUrl(autowonderMcpUrlSupplier.get()));
        server.put("authType", "autowonder-dispatch");
        server.put("required", true);
        return server;
    }

    private static String normalizeBaseUrl(String publicBaseUrl) {
        String configured = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        if (configured.isEmpty()) {
            throw new IllegalStateException("autowonder.public-base-url must be configured");
        }
        try {
            URI uri = URI.create(configured);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null
                    || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return configured.replaceAll("/+$", "");
        } catch (Exception e) {
            throw new IllegalStateException("autowonder.public-base-url must be an absolute http(s) URL", e);
        }
    }

    private static String normalizeMcpUrl(String mcpUrl) {
        String configured = mcpUrl == null ? "" : mcpUrl.trim();
        if (configured.isEmpty()) {
            throw new IllegalStateException("autowonder MCP URL must be configured");
        }
        return configured.replaceAll("/+$", "");
    }

    private static Map<String, Object> mcpDescriptor(Map<String, Object> capability, String name) {
        Map<String, Object> descriptor = new LinkedHashMap<>(config(capability));
        descriptor.put("name", name);
        descriptor.put("required", !Boolean.FALSE.equals(capability.get("required")));
        return descriptor;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> capability) {
        Object value = capability.get("config");
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("capability config is required: " + capability.get("name"));
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private void extractCapability(ZipOutputStream output, byte[] archive, String base,
                                   Map<String, String> fileDigests, boolean requireSkillMd) throws Exception {
        int entries = 0;
		int files = 0;
        long expanded = 0;
        boolean hasSkillMd = false;
        try (ZipInputStream input = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 500) {
                    throw new IllegalArgumentException("capability package has too many entries");
                }
                String rawName = entry.isDirectory() && entry.getName().endsWith("/")
                        ? entry.getName().substring(0, entry.getName().length() - 1) : entry.getName();
                String name = safeArchivePath(rawName);
                if (entry.isDirectory()) {
                    continue;
                }
				files++;
                ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > 50L * 1024L * 1024L) {
                        throw new IllegalArgumentException("capability package is too large");
                    }
                    entryBytes.write(buffer, 0, read);
                }
                byte[] bytes = entryBytes.toByteArray();
                if ("SKILL.md".equals(name)) {
                    hasSkillMd = true;
                }
                putEntryBytes(output, base + "/" + name, bytes, fileDigests);
            }
        }
		if (files == 0 || (requireSkillMd && !hasSkillMd)) {
            throw new IllegalArgumentException(requireSkillMd
                    ? "skill package must contain root SKILL.md" : "plugin package is empty");
        }
    }

    private static String safeArchivePath(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("/") || raw.startsWith("\\") || raw.contains("\\")) {
            throw new IllegalArgumentException("unsafe capability archive path");
        }
        java.nio.file.Path normalized = java.nio.file.Path.of(raw).normalize();
        String path = normalized.toString().replace('\\', '/');
        if (path.equals(".") || path.equals("..") || path.startsWith("../") || !path.equals(raw)) {
            throw new IllegalArgumentException("unsafe capability archive path " + raw);
        }
        return path;
    }

    private static String requiredCapabilityName(Map<String, Object> capability) {
        String name = requiredString(capability, "name");
        if (name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            return name;
        }
        String id = requiredString(capability, "id");
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid capability id " + id);
        }
        return "capability-" + id;
    }

    private static String requiredString(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        String string = raw == null ? "" : String.valueOf(raw).trim();
        if (string.isEmpty()) {
            throw new IllegalArgumentException("capability " + key + " is required");
        }
        return string;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static byte[] generatedSkill(Map<String, Object> capability, String name) {
        String description = stringValue(capability.get("description"));
        Object rawConfig = capability.get("config");
        String instructions = rawConfig instanceof Map
                ? stringValue(((Map<?, ?>) rawConfig).get("instructions")) : "";
        if (instructions.isEmpty()) {
            instructions = description;
        }
        String markdown = "---\nname: " + yamlString(name) + "\ndescription: "
                + yamlString(description.isEmpty() ? name : description.replace("\n", " "))
                + "\n---\n\n# " + name + "\n\n" + instructions + "\n";
        return markdown.getBytes(StandardCharsets.UTF_8);
    }

    private static String yamlString(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private void writeTeammates(ZipOutputStream zos, PackageContext ctx,
                               List<Map<String, Object>> manifestOut,
                               Map<String, String> digests) throws Exception {
        if (ctx.getTeammates() == null || ctx.getTeammates().isEmpty()) {
            return;
        }
        Set<String> usedDirs = new HashSet<>();
        for (TeammateOutput t : ctx.getTeammates()) {
            String base = t.getRoleName() == null || t.getRoleName().isBlank() ? "unknown" : t.getRoleName();
            String dir = base;
            if (usedDirs.contains(dir)) {
                dir = base + "__" + last6(t.getDispatchId());
            }
            usedDirs.add(dir);

            putEntry(zos, "teammates/" + dir + "/conclusion.md", nz(t.getConclusionMd()), digests);
            if (t.getArtifacts() != null) {
                for (TaskArtifactRef ref : t.getArtifacts()) {
                    byte[] bytes = storage.get(ref.getOssRef());
                    if (bytes != null) {
                        putEntryBytes(zos, "teammates/" + dir + "/artifacts/" + ref.getName(), bytes, digests);
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roleName", t.getRoleName());
            m.put("agentId", t.getAgentId());
            m.put("dispatchId", t.getDispatchId());
            m.put("dir", dir);
            manifestOut.add(m);
        }
    }

    private List<Map<String, Object>> writeRequirementDocuments(ZipOutputStream zos, PackageContext ctx,
                                                                 Map<String, String> digests) throws Exception {
        List<Map<String, Object>> manifest = new ArrayList<>();
        if (ctx.getRequirementDocuments() == null || ctx.getRequirementDocuments().isEmpty()) {
            return manifest;
        }
        Set<String> written = new HashSet<>();
        for (TaskArtifactRef ref : ctx.getRequirementDocuments()) {
            String entryName = requirementEntryName(ref.getName());
            if (!written.add(entryName)) {
                throw new IllegalArgumentException("duplicate requirement document " + entryName);
            }
            byte[] bytes = storage.get(ref.getOssRef());
            if (bytes == null) {
                log.error("requirement document missing dispatchId={} workitemId={} name={} ossRef={}",
                        ctx.getDispatchId(), ctx.getWorkitemId(), ref.getName(), ref.getOssRef());
                throw new IllegalArgumentException("requirement document is unavailable: " + ref.getName());
            }
            putEntryBytes(zos, entryName, bytes, digests);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entryName);
            item.put("size", bytes.length);
            item.put("sha256", sha256(bytes));
            manifest.add(item);
        }
        return manifest;
    }

    private static String requirementEntryName(String rawName) {
        String normalized = safeArchivePath(rawName == null ? "" : rawName.replace('\\', '/'));
        String filename = normalized.startsWith("requirements/")
                ? normalized.substring("requirements/".length()) : normalized;
        if (filename.isBlank() || filename.contains("/")) {
            throw new IllegalArgumentException("unsafe requirement document path " + rawName);
        }
        return "requirements/" + filename;
    }

    private List<Map<String, Object>> resolveRepos(PackageContext ctx) {
        List<Map<String, Object>> repos = new ArrayList<>();
        if (ctx.getRepos() != null) {
            for (Map<String, Object> repo : ctx.getRepos()) {
                repos.add(new LinkedHashMap<>(repo));
            }
        }
        Map<String, Map<String, String>> revisions = loadSourceRevisions(ctx);
        for (Map<String, Object> repo : repos) {
            Map<String, String> revision = revisions.get(String.valueOf(repo.get("name")));
            if (revision == null || !validCommit(revision.get("headCommit"))) {
                continue;
            }
            repo.put("ref", revision.get("headCommit"));
            if (!Boolean.parseBoolean(revision.get(CHECKPOINT_FALLBACK))
                    && validCommit(revision.get("baseCommit"))) {
                repo.put("deliveryBaseCommit", revision.get("baseCommit"));
            }
            if (validBranch(revision.get("branch"))) {
                repo.put("deliveryBranch", revision.get("branch"));
            }
        }
        return repos;
    }

    private Map<String, Map<String, String>> loadSourceRevisions(PackageContext ctx) {
        Map<String, Map<String, String>> revisions = new LinkedHashMap<>();
        List<TaskArtifactRef> refs = ctx.getSourceRevisionArtifacts();
        if (refs == null || refs.isEmpty()) {
            refs = new ArrayList<>();
            if (ctx.getTeammates() != null) {
                for (TeammateOutput teammate : ctx.getTeammates()) {
                    if (teammate.getArtifacts() != null) {
                        refs.addAll(teammate.getArtifacts());
                    }
                }
            }
        }
        for (TaskArtifactRef ref : refs) {
            String name = ref.getName() == null ? "" : ref.getName().replace('\\', '/');
            if (!name.endsWith("deliverables/runtime-source-revision.json")) {
                continue;
            }
            byte[] raw = storage.get(ref.getOssRef());
            if (raw == null) {
                continue;
            }
            try {
                var document = JSON.parseObject(new String(raw, StandardCharsets.UTF_8));
                boolean checkpointFallback = CHECKPOINT_SOURCE_REVISION_SCHEMA.equals(
                        document.getString("schemaVersion"));
                var repositories = document.getJSONArray("repositories");
                if (repositories == null) {
                    continue;
                }
                for (int i = 0; i < repositories.size(); i++) {
                    var revision = repositories.getJSONObject(i);
                    String repoName = revision.getString("name");
                    if (repoName == null || repoName.isBlank()) {
                        continue;
                    }
                    Map<String, String> values = revisions.computeIfAbsent(repoName,
                            ignored -> new LinkedHashMap<>());
                    if (!validCommit(values.get("headCommit"))
                            && validCommit(revision.getString("headCommit"))) {
                        values.put("headCommit", revision.getString("headCommit"));
                        values.put(CHECKPOINT_FALLBACK, Boolean.toString(checkpointFallback));
                    }
                    if (!Boolean.parseBoolean(values.get(CHECKPOINT_FALLBACK))
                            && !validCommit(values.get("baseCommit"))
                            && validCommit(revision.getString("baseCommit"))) {
                        values.put("baseCommit", revision.getString("baseCommit"));
                    }
                    if (!validBranch(values.get("branch"))
                            && validBranch(revision.getString("branch"))) {
                        values.put("branch", revision.getString("branch"));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("invalid runtime source revision ignored artifact={}", name);
            }
        }
        return revisions;
    }

    private boolean validCommit(String commit) {
        return commit != null && commit.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");
    }

    private boolean validBranch(String branch) {
        if (branch == null || branch.isBlank() || branch.length() > 255
                || branch.startsWith("-") || branch.startsWith("/")
                || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock")
                || branch.contains("..") || branch.contains("@{") || branch.contains("//")
                || "@".equals(branch)) {
            return false;
        }
        for (int i = 0; i < branch.length(); i++) {
            char c = branch.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)
                    || "~^:?*[\\".indexOf(c) >= 0) {
                return false;
            }
        }
        for (String part : branch.split("/")) {
            if (part.isEmpty() || part.startsWith(".") || part.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    private static String last6(String id) {
        String s = id == null || id.isBlank() ? "0" : id;
        return s.length() <= 6 ? s : s.substring(s.length() - 6);
    }

    private void putEntry(ZipOutputStream zos, String name, String content,
                          Map<String, String> digests) throws Exception {
        putEntryBytes(zos, name, content.getBytes(StandardCharsets.UTF_8), digests);
    }

    private void putEntryBytes(ZipOutputStream zos, String name, byte[] bytes,
                               Map<String, String> digests) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
        if (digests != null && !name.equals("manifest.json")) {
            digests.put(name, sha256(bytes));
        }
    }

    private static String str(Long v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String sha256(byte[] bytes) {
        return "sha256:" + sha256Hex(bytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PACKAGE_BUILD_FAILED);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, Object> orEmptyMap(Map<String, Object> m) {
        return m == null ? Map.of() : m;
    }
}
