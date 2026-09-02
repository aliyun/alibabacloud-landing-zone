package com.aliyun.autowonder.taskpackage;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TaskPackagerContractFixtureTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-08-06T04:00:00Z");
    private static final Path FIXTURE = Path.of(
            "src/test/resources/contract/server-task-package-v1.json");
    private static final String PRIVATE_KEY =
            "MC4CAQAwBQYDK2VwBCIEIJEun76AaWOlhDwx9Af4ln0VmCf8IzdX53TIxR3x4Fe5";
    private static final String PUBLIC_KEY =
            "MCowBQYDK2VwAyEA87WOWLBhzdtpwe/UUZM9F6oag0IZJTh/hQzqT1jbpnM=";

    @Test
    void realTaskPackagerMatchesRuntimeContractFixture() throws Exception {
        Fixture built = buildFixture();
        if (Boolean.getBoolean("updateContractFixture")) {
            Files.createDirectories(FIXTURE.getParent());
            Files.writeString(FIXTURE, built.json() + "\n", StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(FIXTURE), "generate the contract fixture first");
        assertEquals(Files.readString(FIXTURE, StandardCharsets.UTF_8).trim(), built.json());

        JSONObject fixture = JSON.parseObject(built.json());
        assertEquals("autoWonder.taskPackageFixture.v1", fixture.getString("schemaVersion"));
        assertEquals(TaskPackageSigner.ISSUER, fixture.getString("issuer"));
        assertEquals(TaskPackageSigner.ALGORITHM, fixture.getString("signatureAlgorithm"));
        assertTrue(fixture.getBooleanValue("allowCommit"));
        assertFalse(fixture.getBooleanValue("allowPush"));
        assertFalse(fixture.getBooleanValue("allowNetwork"));
        assertTrue(built.result().isRequiresHookProtocol());

        Map<String, byte[]> archive = unzip(built.zip());
        assertTrue(archive.containsKey("skills.json"));
        assertTrue(archive.containsKey("hooks.json"));
        assertTrue(archive.containsKey("capabilities/skills/review/SKILL.md"));
        assertTrue(archive.containsKey("capabilities/plugins/team-tools/.codex-plugin/plugin.json"));
        assertTrue(archive.containsKey("capabilities/hooks/step-shell/scripts/run.sh"));
        JSONObject policy = JSON.parseObject(new String(archive.get("policy.json"), StandardCharsets.UTF_8));
        assertTrue(policy.getBooleanValue("allowCommit"));
        assertFalse(policy.getBooleanValue("allowPush"));

        JSONObject manifest = JSON.parseObject(new String(
                archive.get("manifest.json"), StandardCharsets.UTF_8));
        assertEquals(fixture.getString("signatureRef"), manifest.getString("signatureRef"));
        assertEquals(fixture.getString("expiresAt"), manifest.getString("expiresAt"));
        assertTrue(manifest.getString("capabilityTreeDigest").startsWith("sha256:"));

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keyPair().getPublic());
        verifier.update(TaskPackageSigner.payload(
                new TaskPackageSigner.Envelope(fixture.getString("issuer"),
                        fixture.getString("signatureRef"), fixture.getString("expiresAt")),
                fixture.getString("packageId"), fixture.getString("sha256")));
        assertTrue(verifier.verify(Base64.getDecoder().decode(fixture.getString("signature"))));
    }

    private Fixture buildFixture() throws Exception {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        var skill = storage.put("capability", "review.zip", zip(Map.of(
                "SKILL.md", "---\nname: review\ndescription: Review changes\n---\nReview the implementation.")));
        var plugin = storage.put("capability", "team-tools.zip", zip(Map.of(
                ".codex-plugin/plugin.json", "{\"name\":\"team-tools\"}",
                "README.md", "Packaged plugin fixture.")));
        var beforeRepo = storage.put("capability", "before-repo.zip", hookZip(
                "before-repo", "beforeRepoPrepare", "bash",
                "printf 'before-repo\\n' >> \"$AUTOWONDER_STATE_DIR/order.txt\"\n",
                "once-per-attempt", "fail-task"));
        var stepShell = storage.put("capability", "step-shell.zip", hookZip(
                "step-shell", "beforeStep", "bash",
                "VALUE=packaged\nprintf '%s\\n' \"$VALUE:$AUTOWONDER_STEP_ID\" | tr '[:lower:]' '[:upper:]' > \"$AUTOWONDER_ARTIFACTS_OUTPUT_DIR/step.txt\"\n",
                "once-per-step", "fail-task"));
        var afterAgent = storage.put("capability", "after-agent.zip", hookZip(
                "after-agent", "afterAgentExit", "sh",
                "printf 'after-agent\\n' >> \"$AUTOWONDER_STATE_DIR/order.txt\"\n",
                "once-per-attempt", "continue"));
        var cleanup = storage.put("capability", "cleanup.zip", hookZip(
                "cleanup", "cleanup", "python3",
                "import os, pathlib\np = pathlib.Path(os.environ['AUTOWONDER_STATE_DIR']) / 'cleanup.txt'\np.write_text('cleaned')\n",
                "once-per-attempt", "continue"));

        PackageContext context = new PackageContext();
        context.setTenantId(100L);
        context.setWorkitemId(500L);
        context.setDispatchId(9001L);
        context.setAttempt(2);
        context.setIdempotencyKey("100:500:9001:2");
        context.setWorkType("REQUIREMENT");
        context.setSdlcId(600L);
        context.setSdlcStepId(700L);
        context.setAgentId(200L);
        context.setAgentVersionId(201L);
        context.setExecutorId(300L);
        context.setWorkitemTitle("Task package contract");
        context.setWorkitemContentMd("Consume packaged Git, Skill, Plugin, MCP, Shell and Hook declarations.");
        context.setIdentity(map("name", "contract-worker", "roleCode", "developer"));
        context.setSdlc(map("currentStepId", "RD", "steps", List.of("RD", "QA")));
        context.setRepos(List.of(map(
                "name", "source",
                "url", "https://example.invalid/source.git",
                "ref", "1111111111111111111111111111111111111111",
                "pinnedBaseCommit", "1111111111111111111111111111111111111111",
                "deterministicBranch", "autowonder/9001/attempt-2/source",
                "mode", "lazy",
                "allowCommit", true,
                "allowPush", false,
                "allowNetwork", false)));
        context.setSkills(List.of(
                capability(41, "SKILL", "review", 1, skill.getOssRef(), null),
                capability(42, "PLUGIN", "team-tools", 1, plugin.getOssRef(),
                        map("providers", List.of("qoder"))),
                capability(43, "MCP", "repo-tools", 1, null,
                        map("transport", "http", "url", "https://mcp.example.invalid/api", "authType", "none")),
                capability(44, "HOOK", "before-repo", 1, beforeRepo.getOssRef(), null),
                capability(45, "HOOK", "step-shell", 1, stepShell.getOssRef(), null),
                capability(46, "HOOK", "after-agent", 1, afterAgent.getOssRef(), null),
                capability(47, "HOOK", "cleanup", 1, cleanup.getOssRef(), null)));

        TaskPackager packager = new TaskPackager(storage, "task-packages",
                () -> "https://server.example.invalid/api/mcp",
                new TaskPackageSigner(keyPair()), Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
        TaskPackageResult result = packager.build(context);
        byte[] archive = storage.get(result.getOssRef());

        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("schemaVersion", "autoWonder.taskPackageFixture.v1");
        fixture.put("archiveBase64", Base64.getEncoder().encodeToString(archive));
        fixture.put("sha256", "sha256:" + result.getSha256());
        fixture.put("checksumAlgorithm", "sha256");
        fixture.put("checksumScope", "zip_archive");
        fixture.put("packageId", "pkg_9001");
        fixture.put("issuer", result.getIssuer());
        fixture.put("signatureRef", result.getSignatureRef());
        fixture.put("signature", result.getSignature());
        fixture.put("signatureAlgorithm", result.getSignatureAlgorithm());
        fixture.put("signaturePublicKey", result.getSignaturePublicKey());
        fixture.put("expiresAt", result.getExpiresAt());
        fixture.put("allowCommit", result.isAllowCommit());
        fixture.put("allowPush", result.isAllowPush());
        fixture.put("allowNetwork", result.isAllowNetwork());
        fixture.put("tenantId", "100");
        fixture.put("workitemId", "500");
        fixture.put("dispatchId", "9001");
        fixture.put("attempt", 2);
        fixture.put("idempotencyKey", "100:500:9001:2");
        fixture.put("workType", "REQUIREMENT");
        fixture.put("sdlcId", "600");
        fixture.put("sdlcStepId", "700");
        fixture.put("agentId", "200");
        fixture.put("agentVersionId", "201");
        fixture.put("executorId", "300");
        return new Fixture(JSON.toJSONString(fixture), archive, result);
    }

    private static Map<String, Object> capability(long id, String type, String name, int version,
                                                   String ossRef, Map<String, Object> config) {
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("id", id);
        capability.put("type", type);
        capability.put("name", name);
        capability.put("version", version);
        capability.put("required", true);
        if (ossRef != null) capability.put("packageOssRef", ossRef);
        if (config != null) capability.put("config", config);
        return capability;
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private static byte[] hookZip(String name, String trigger, String interpreter, String script,
                                  String onceScope, String failurePolicy) throws Exception {
        String descriptor = """
                schemaVersion: autowonder.hook.v1
                name: %s
                version: '1'
                trigger: %s
                interpreter: %s
                command: scripts/run.%s
                args: []
                cwd: workspace
                env:
                  FIXTURE_ENV: packaged
                timeoutSeconds: 30
                failurePolicy: %s
                onceScope: %s
                """.formatted(name, trigger, interpreter,
                "python3".equals(interpreter) ? "py" : "sh", failurePolicy, onceScope);
        return zip(new LinkedHashMap<>(Map.of(
                "hook.yaml", descriptor,
                "scripts/run." + ("python3".equals(interpreter) ? "py" : "sh"), script)));
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> file : new java.util.TreeMap<>(files).entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] raw) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private static KeyPair keyPair() throws Exception {
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(PRIVATE_KEY)));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(PUBLIC_KEY)));
        return new KeyPair(publicKey, privateKey);
    }

    private record Fixture(String json, byte[] zip, TaskPackageResult result) {
    }
}
