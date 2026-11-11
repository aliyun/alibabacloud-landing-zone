package com.aliyun.autowonder.frontendproject;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The profile codec defines the frozen {@code design/project-profile.json} schema, so a round trip
 * must be lossless: whatever the executor and the UI read back has to equal what the scanner saw.
 */
class FrontendProjectProfileCodecTest {

    @Test
    void roundTripsStackFactsInferencesAndUnknowns(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "web", "private": true,
                  "dependencies": { "react": "^18.0.0", "antd": "^5.0.0", "react-router-dom": "^6.0.0" },
                  "devDependencies": { "vite": "^5.0.0", "typescript": "^5.0.0", "vitest": "^1.0.0" } }
                """);
        write(repo, "tsconfig.json", "{}");
        write(repo, "index.html", "<html></html>");

        FrontendProjectScanner.ScanResult scan = FrontendProjectScanner.scan(repo);
        FrontendProjectScanner.CandidateApp app = scan.candidates().get(0);

        String profileJson = FrontendProjectProfileCodec.encodeProfile(scan, app);
        FrontendProjectProfileCodec.ProfileView view = FrontendProjectProfileCodec.decodeProfile(profileJson);

        assertEquals(FrontendProjectProfileCodec.PROFILE_SCHEMA_VERSION, view.schemaVersion());
        assertEquals(FrontendProjectScanner.DETECTOR_VERSION, view.detectorVersion());
        assertEquals("web", view.packageName());
        assertEquals("TypeScript", view.language());
        assertEquals(List.of("React"), view.frameworks());
        assertEquals(List.of("Ant Design"), view.uiLibraries());
        assertEquals(List.of("React Router"), view.routers());
        assertEquals(List.of("Vite"), view.buildTools());
        assertEquals(List.of("Vitest"), view.testTools());
        assertFalse(view.looksLikeLibrary());
        // Nothing was left unrecognized, so unknowns must come back empty rather than padded.
        assertTrue(view.unknowns().isEmpty(), "unexpected unknowns: " + view.unknowns());
    }

    @Test
    void profileKeepsTheThreeWaySplitSeparate(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "bare", "private": true, "dependencies": { "react": "^18.0.0" } }
                """);

        FrontendProjectScanner.ScanResult scan = FrontendProjectScanner.scan(repo);
        FrontendProjectScanner.CandidateApp app = scan.candidates().get(0);

        JSONObject json = JSON.parseObject(FrontendProjectProfileCodec.encodeProfile(scan, app));
        assertTrue(json.containsKey("unknowns"));
        assertTrue(json.containsKey("inferences"));
        // Facts live in the evidence column, never merged into the inference/unknown lists.
        assertFalse(json.containsKey("facts"), "facts belong to evidence_json, not profile_json");
        assertTrue(json.getJSONArray("unknowns").size() > 0);
    }

    @Test
    void evidenceCarriesFactAndSourceFile(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "web", "private": true, "dependencies": { "vue": "^3.0.0" } }
                """);

        FrontendProjectScanner.ScanResult scan = FrontendProjectScanner.scan(repo);
        FrontendProjectScanner.CandidateApp app = scan.candidates().get(0);

        JSONObject json = JSON.parseObject(FrontendProjectProfileCodec.encodeEvidence(scan, app));
        var appEvidence = json.getJSONArray("appEvidence");
        assertFalse(appEvidence.isEmpty());
        for (Object item : appEvidence) {
            JSONObject evidence = (JSONObject) item;
            assertFalse(String.valueOf(evidence.get("fact")).isBlank());
            assertFalse(String.valueOf(evidence.get("filePath")).isBlank());
        }
        assertTrue(appEvidence.toJSONString().contains("package.json"));
    }

    @Test
    void appRootHashIsStableAndDistinguishing() {
        String first = FrontendProjectProfileCodec.appRootHash("apps/web");
        String second = FrontendProjectProfileCodec.appRootHash("apps/web");
        String other = FrontendProjectProfileCodec.appRootHash("apps/admin");

        assertEquals(first, second, "same input must hash identically across runs");
        assertNotEquals(first, other);
        assertEquals(64, first.length(), "sha256 hex must fit the CHAR(64) unique key column");
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(FrontendProjectProfileCodec.appRootHash(""),
                FrontendProjectProfileCodec.appRootHash(null), "null app root hashes as empty");
    }

    @Test
    void decodeToleratesMissingStackSection() {
        FrontendProjectProfileCodec.ProfileView view =
                FrontendProjectProfileCodec.decodeProfile("{\"appRoot\":\"x\"}");

        assertEquals("x", view.appRoot());
        assertTrue(view.frameworks().isEmpty());
        assertTrue(view.unknowns().isEmpty());
    }

    @Test
    void monorepoFlagsSurviveTheRoundTrip(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "mono", "private": true, "workspaces": ["apps/*"] }
                """);
        write(repo, "apps/web/package.json", """
                { "name": "web", "private": true, "dependencies": { "react": "^18.0.0" } }
                """);

        FrontendProjectScanner.ScanResult scan = FrontendProjectScanner.scan(repo);
        FrontendProjectScanner.CandidateApp app = scan.candidates().get(0);

        JSONObject json = JSON.parseObject(FrontendProjectProfileCodec.encodeProfile(scan, app));
        assertTrue(json.getBoolean("monorepo"));
        assertEquals(List.of("apps/*"), json.getJSONArray("workspaceGlobs").toJavaList(String.class));
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
