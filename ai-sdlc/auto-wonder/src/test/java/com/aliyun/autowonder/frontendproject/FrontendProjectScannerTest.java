package com.aliyun.autowonder.frontendproject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendProjectScannerTest {

    @Test
    void detectsSingleStandaloneReactApp(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                {
                  "name": "web-app",
                  "private": true,
                  "dependencies": { "react": "^18.2.0", "react-dom": "^18.2.0", "antd": "^5.19.0", "react-router-dom": "^6.24.0" },
                  "devDependencies": { "vite": "^4.0.0", "typescript": "^5.0.0", "vitest": "^1.0.0", "react-i18next": "^14.0.0" }
                }
                """);
        write(repo, "tsconfig.json", "{}");
        write(repo, "index.html", "<html></html>");
        write(repo, "package-lock.json", "{}");

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);

        assertEquals(1, result.candidates().size());
        FrontendProjectScanner.CandidateApp app = result.candidates().get(0);
        assertEquals(".", app.appRoot());
        assertEquals("web-app", app.packageName());
        assertEquals(List.of("React"), app.frameworks());
        assertEquals(List.of("Vite"), app.buildTools());
        assertEquals(List.of("Ant Design"), app.uiLibraries());
        assertEquals(List.of("React Router"), app.routers());
        assertEquals("TypeScript", app.language());
        assertEquals("npm", app.packageManager());
        assertTrue(app.privatePackage());
        assertFalse(app.looksLikeLibrary());
        assertTrue(app.unknowns().isEmpty(), "all key dimensions recognized so no unknowns: " + app.unknowns());
        assertFalse(result.monorepo());
    }

    @Test
    void distinguishesFactsInferencesAndUnknowns(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "bare", "private": true, "dependencies": { "react": "^18.0.0" } }
                """);

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);

        assertEquals(1, result.candidates().size());
        FrontendProjectScanner.CandidateApp app = result.candidates().get(0);

        // Facts carry the file they were read from, so every conclusion is traceable.
        assertFalse(app.evidence().isEmpty());
        assertTrue(app.evidence().stream()
                .allMatch(e -> e.fact() != null && !e.fact().isBlank() && e.filePath() != null));
        assertTrue(app.evidence().stream().anyMatch(e -> e.filePath().endsWith("package.json")));

        // Routing and UI kit are genuinely absent, and that is reported as unknown, not guessed.
        assertTrue(app.unknowns().stream().anyMatch(u -> u.contains("路由")));
        assertTrue(app.unknowns().stream().anyMatch(u -> u.contains("UI 组件库")));

        // No lock file anywhere: the manager is unknown rather than invented.
        assertNull(app.packageManager());
        assertTrue(app.inferences().isEmpty(),
                "a private package with no HTML entry carries no deployment inference: " + app.inferences());
    }

    @Test
    void detectsMultipleAppsInMonorepoAndComponentLibrary(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "monorepo", "private": true, "workspaces": ["apps/*", "packages/*"] }
                """);
        write(repo, "pnpm-workspace.yaml", "packages:\n  - 'apps/*'\n  - 'packages/*'\n");

        write(repo, "apps/admin/package.json", """
                { "name": "@acme/admin", "private": true,
                  "dependencies": { "vue": "^3.4.0", "vue-router": "^4.0.0", "element-plus": "^2.0.0" },
                  "devDependencies": { "vite": "^5.0.0" } }
                """);
        write(repo, "apps/admin/index.html", "<html></html>");

        write(repo, "apps/mobile/package.json", """
                { "name": "@acme/mobile", "private": true,
                  "dependencies": { "react": "^18.0.0", "next": "^14.0.0" } }
                """);

        write(repo, "packages/ui-kit/package.json", """
                { "name": "@acme/ui-kit", "main": "dist/index.js",
                  "dependencies": { "react": "^18.0.0" },
                  "scripts": { "build": "tsc" } }
                """);

        write(repo, "backend/package.json", """
                { "name": "backend", "private": true, "dependencies": { "express": "^4.0.0" } }
                """);

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);

        assertTrue(result.monorepo());
        assertEquals(List.of("apps/*", "packages/*", "apps/*", "packages/*"), result.workspaceGlobs());

        List<String> roots = result.candidates().stream()
                .map(FrontendProjectScanner.CandidateApp::appRoot).sorted().toList();
        assertEquals(List.of("apps/admin", "apps/mobile", "packages/ui-kit"), roots,
                "backend package has no frontend signal and must not be a candidate");

        FrontendProjectScanner.CandidateApp admin = find(result, "apps/admin");
        assertEquals(List.of("Vue"), admin.frameworks());
        assertEquals(List.of("Element Plus"), admin.uiLibraries());
        assertFalse(admin.looksLikeLibrary());

        FrontendProjectScanner.CandidateApp mobile = find(result, "apps/mobile");
        assertTrue(mobile.buildTools().contains("Next.js"));

        FrontendProjectScanner.CandidateApp uiKit = find(result, "packages/ui-kit");
        assertTrue(uiKit.looksLikeLibrary(), "published entry + no HTML entry => component library");
        assertFalse(uiKit.privatePackage());
    }

    @Test
    void detectsFrontendInsideMixedFullStackRepo(@TempDir Path repo) throws IOException {
        write(repo, "pom.xml", "<project/>");
        write(repo, "src/main/java/App.java", "class App {}");
        write(repo, "frontend/package.json", """
                { "name": "ui", "private": true, "dependencies": { "react": "^18.0.0" },
                  "devDependencies": { "webpack": "^5.0.0" } }
                """);
        write(repo, "frontend/public/index.html", "<html></html>");
        write(repo, "frontend/yarn.lock", "");

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);

        assertEquals(1, result.candidates().size());
        FrontendProjectScanner.CandidateApp app = result.candidates().get(0);
        assertEquals("frontend", app.appRoot());
        assertEquals(List.of("Webpack"), app.buildTools());
        assertEquals("yarn", app.packageManager());
        assertTrue(app.inferences().stream().anyMatch(i -> i.contains("独立部署")));
    }

    @Test
    void reportsUnknownWhenNoFrontendFound(@TempDir Path repo) throws IOException {
        write(repo, "README.md", "backend only");

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);

        assertTrue(result.candidates().isEmpty());
        assertTrue(result.unknowns().stream().anyMatch(u -> u.contains("未找到 package.json")));
    }

    @Test
    void skipsNodeModulesAndBuildOutput(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "real", "private": true, "dependencies": { "react": "^18.0.0" } }
                """);
        write(repo, "index.html", "<html></html>");
        write(repo, "node_modules/left-pad/package.json", """
                { "name": "left-pad", "dependencies": { "react": "^18.0.0" } }
                """);
        write(repo, "dist/bundle/package.json", """
                { "name": "bundle", "dependencies": { "react": "^18.0.0" } }
                """);

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);

        assertEquals(1, result.candidates().size());
        assertEquals("real", result.candidates().get(0).packageName());
    }

    @Test
    void toleratesMalformedPackageJson(@TempDir Path repo) throws IOException {
        write(repo, "package.json", "{ this is not json ");
        write(repo, "apps/ok/package.json", """
                { "name": "ok", "private": true, "dependencies": { "svelte": "^4.0.0" } }
                """);

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);

        assertEquals(1, result.candidates().size());
        assertEquals(List.of("Svelte"), result.candidates().get(0).frameworks());
    }

    @Test
    void detectsDeeplyNestedAppButStopsBeyondMaxDepth(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "root", "private": true }
                """);
        // depth 5 from repo root: a/b/c/d/e -> package.json is at depth 5
        write(repo, "a/b/c/d/e/package.json", """
                { "name": "deep", "private": true, "dependencies": { "react": "^18.0.0" } }
                """);
        // depth 7: beyond MAX_DEPTH (6), must not be scanned
        write(repo, "a/b/c/d/e/f/g/package.json", """
                { "name": "toodeep", "private": true, "dependencies": { "react": "^18.0.0" } }
                """);

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);

        List<String> names = result.candidates().stream()
                .map(FrontendProjectScanner.CandidateApp::packageName).toList();
        assertTrue(names.contains("deep"), "found: " + names);
        assertFalse(names.contains("toodeep"), "beyond max depth must be skipped: " + names);
    }

    @Test
    void evidenceIsSerializableToProfileJson(@TempDir Path repo) throws IOException {
        write(repo, "package.json", """
                { "name": "x", "private": true, "dependencies": { "react": "^18.0.0" } }
                """);

        FrontendProjectScanner.ScanResult result = FrontendProjectScanner.scan(repo);
        FrontendProjectScanner.Evidence evidence = result.candidates().get(0).evidence().get(0);

        assertNotNull(evidence.toJson().getString("fact"));
        assertNotNull(evidence.toJson().getString("filePath"));
    }

    private static FrontendProjectScanner.CandidateApp find(FrontendProjectScanner.ScanResult result, String appRoot) {
        return result.candidates().stream()
                .filter(c -> c.appRoot().equals(appRoot))
                .findFirst()
                .orElseThrow(() -> new AssertionError("candidate not found: " + appRoot));
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
