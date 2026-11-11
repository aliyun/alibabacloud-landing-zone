package com.aliyun.autowonder.frontendproject;

import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic static scan of a checked-out repository into frontend application candidates.
 *
 * <p>Everything this class reports is a {@code fact}: a value read from a file that exists on
 * disk, paired with the relative path it was read from. It never runs repository scripts,
 * never installs dependencies and never reads credentials, so its output is safe to show to a
 * user as evidence. Judgement calls (which candidate is the right target, what a page does)
 * are deliberately not made here — those are {@code inference}s produced by the model layer
 * and are kept in a separate field so the two are never conflated.
 */
public final class FrontendProjectScanner {

    /** Bumped whenever the detection rules below change; profiles are cached per version. */
    public static final String DETECTOR_VERSION = "frontend-project-detector-v1";

    private static final int MAX_PACKAGE_JSON_BYTES = 512 * 1024;
    private static final int MAX_APP_DIRS = 200;
    private static final int MAX_DEPTH = 6;
    private static final Set<String> SKIPPED_DIRS = Set.of(
            "node_modules", ".git", "dist", "build", "out", "coverage", ".next", ".nuxt",
            ".output", ".turbo", ".cache", "target", "vendor", ".venv", "__pycache__");

    private static final Map<String, String> FRAMEWORK_MARKERS = ordered(
            "react", "React", "react-dom", "React",
            "next", "Next.js",
            "vue", "Vue",
            "nuxt", "Nuxt",
            "@angular/core", "Angular",
            "svelte", "Svelte",
            "@sveltejs/kit", "SvelteKit",
            "solid-js", "Solid",
            "preact", "Preact");

    private static final Map<String, String> BUILD_TOOL_MARKERS = ordered(
            "vite", "Vite",
            "webpack", "Webpack",
            "next", "Next.js",
            "nuxt", "Nuxt",
            "@rspack/core", "Rspack",
            "esbuild", "esbuild",
            "rollup", "Rollup",
            "parcel", "Parcel",
            "@vitejs/plugin-react", "Vite",
            "@vue/cli-service", "Vue CLI");

    private static final Map<String, String> UI_LIBRARY_MARKERS = ordered(
            "antd", "Ant Design",
            "@ant-design/icons", "Ant Design",
            "@mui/material", "Material UI",
            "element-plus", "Element Plus",
            "naive-ui", "Naive UI",
            "@chakra-ui/react", "Chakra UI",
            "@arco-design/web-react", "Arco Design",
            "@arco-design/web-vue", "Arco Design",
            "vuetify", "Vuetify",
            "bootstrap", "Bootstrap",
            "tailwindcss", "Tailwind CSS",
            "@radix-ui/react-dialog", "Radix UI");

    private static final Map<String, String> ROUTER_MARKERS = ordered(
            "react-router", "React Router",
            "react-router-dom", "React Router",
            "@tanstack/react-router", "TanStack Router",
            "vue-router", "Vue Router",
            "@angular/router", "Angular Router",
            "next", "Next.js file-based routing",
            "nuxt", "Nuxt file-based routing",
            "@sveltejs/kit", "SvelteKit file-based routing");

    private static final Map<String, String> I18N_MARKERS = ordered(
            "react-i18next", "react-i18next",
            "i18next", "i18next",
            "vue-i18n", "vue-i18n",
            "@formatjs/intl", "FormatJS",
            "react-intl", "react-intl",
            "@angular/localize", "Angular localize",
            "svelte-i18n", "svelte-i18n");

    private static final Map<String, String> TEST_MARKERS = ordered(
            "vitest", "Vitest",
            "jest", "Jest",
            "@playwright/test", "Playwright",
            "playwright", "Playwright",
            "cypress", "Cypress",
            "@testing-library/react", "Testing Library",
            "@vue/test-utils", "Vue Test Utils");

    private FrontendProjectScanner() {}

    /** Scan result: candidate applications plus repository-level facts and unknowns. */
    public record ScanResult(List<CandidateApp> candidates,
                             List<Evidence> repoEvidence,
                             List<String> unknowns,
                             String packageManager,
                             boolean monorepo,
                             List<String> workspaceGlobs) {}

    /** One frontend application rooted at {@code appRoot} (repo-relative, "" for the root). */
    public record CandidateApp(String appRoot,
                               String packageName,
                               List<String> frameworks,
                               List<String> buildTools,
                               List<String> uiLibraries,
                               List<String> routers,
                               List<String> i18nLibraries,
                               List<String> testTools,
                               String language,
                               String packageManager,
                               boolean privatePackage,
                               boolean looksLikeLibrary,
                               List<Evidence> evidence,
                               List<String> inferences,
                               List<String> unknowns) {}

    /** A fact together with the file it was read from, so conclusions stay traceable. */
    public record Evidence(String fact, String filePath) {
        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("fact", fact);
            json.put("filePath", filePath);
            return json;
        }
    }

    public static ScanResult scan(Path repoRoot) throws IOException {
        List<Path> packageJsonFiles = new ArrayList<>();
        collectPackageJsonFiles(repoRoot, repoRoot, 0, packageJsonFiles);

        List<Evidence> repoEvidence = new ArrayList<>();
        List<String> unknowns = new ArrayList<>();
        String rootManager = detectPackageManager(repoRoot, repoRoot, repoEvidence);
        List<String> workspaceGlobs = detectWorkspaceGlobs(repoRoot, repoEvidence);
        boolean monorepo = !workspaceGlobs.isEmpty()
                || Files.exists(repoRoot.resolve("pnpm-workspace.yaml"))
                || Files.exists(repoRoot.resolve("lerna.json"))
                || Files.exists(repoRoot.resolve("nx.json"))
                || Files.exists(repoRoot.resolve("turbo.json"));
        if (monorepo) {
            repoEvidence.add(new Evidence("仓库按 monorepo 组织（存在 workspace 配置）", "."));
        }
        if (packageJsonFiles.isEmpty()) {
            unknowns.add("仓库内未找到 package.json，无法确认是否存在前端应用");
        }

        List<CandidateApp> candidates = new ArrayList<>();
        for (Path packageJson : packageJsonFiles) {
            if (candidates.size() >= MAX_APP_DIRS) {
                unknowns.add("前端应用目录超过 " + MAX_APP_DIRS + " 个，仅扫描了前 " + MAX_APP_DIRS + " 个");
                break;
            }
            CandidateApp candidate = scanApp(repoRoot, packageJson, rootManager);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return new ScanResult(candidates, repoEvidence, unknowns, rootManager, monorepo, workspaceGlobs);
    }

    private static CandidateApp scanApp(Path repoRoot, Path packageJson, String rootManager) {
        Path appRoot = packageJson.getParent();
        if (appRoot == null) {
            return null;
        }
        JSONObject pkg = readPackageJson(packageJson);
        if (pkg == null) {
            return null;
        }
        String packageJsonRel = relative(repoRoot, packageJson);
        Map<String, Object> dependencies = dependenciesOf(pkg);
        if (!hasFrontendSignal(dependencies, appRoot)) {
            return null;
        }

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("存在 Node 包描述文件", packageJsonRel));

        String packageName = pkg.getString("name");
        if (packageName != null && !packageName.isBlank()) {
            evidence.add(new Evidence("包名为 " + packageName, packageJsonRel));
        }
        boolean privatePackage = Boolean.TRUE.equals(pkg.getBoolean("private"));
        List<String> inferences = new ArrayList<>();
        boolean looksLikeLibrary = looksLikeLibrary(pkg, appRoot, privatePackage, inferences);

        List<String> frameworks = match(dependencies, FRAMEWORK_MARKERS, "前端框架", packageJsonRel, evidence);
        List<String> buildTools = match(dependencies, BUILD_TOOL_MARKERS, "构建工具", packageJsonRel, evidence);
        List<String> uiLibraries = match(dependencies, UI_LIBRARY_MARKERS, "UI 组件库", packageJsonRel, evidence);
        List<String> routers = match(dependencies, ROUTER_MARKERS, "路由方案", packageJsonRel, evidence);
        List<String> i18nLibraries = match(dependencies, I18N_MARKERS, "多语言方案", packageJsonRel, evidence);
        List<String> testTools = match(dependencies, TEST_MARKERS, "测试框架", packageJsonRel, evidence);

        String appRootRel = relative(repoRoot, appRoot);
        String language = detectLanguage(appRoot, dependencies, evidence, appRootRel);
        String manager = detectPackageManager(repoRoot, appRoot, evidence);
        if (manager == null) {
            manager = rootManager;
            if (manager != null) {
                inferences.add("自身目录没有锁文件，推断沿用仓库根的包管理器 " + manager);
            }
        }

        List<String> unknowns = new ArrayList<>();
        if (frameworks.isEmpty()) {
            unknowns.add("未能从依赖中识别前端框架");
        }
        if (routers.isEmpty()) {
            unknowns.add("未能识别路由方案，页面结构需人工确认");
        }
        if (uiLibraries.isEmpty()) {
            unknowns.add("未能识别 UI 组件库或主题方案");
        }

        return new CandidateApp(appRootRel, packageName, frameworks, buildTools, uiLibraries,
                routers, i18nLibraries, testTools, language, manager, privatePackage,
                looksLikeLibrary, evidence, inferences, unknowns);
    }

    /**
     * Whether this package looks like a reusable component library rather than a deployable app.
     * This is an inference from deterministic signals (a published entry point and no HTML
     * entry), never a fact, and it is only a hint — the user makes the final target choice.
     */
    private static boolean looksLikeLibrary(JSONObject pkg, Path appRoot,
                                            boolean privatePackage, List<String> inferences) {
        boolean hasHtmlEntry = Files.exists(appRoot.resolve("index.html"))
                || Files.exists(appRoot.resolve("public/index.html"));
        boolean published = !privatePackage && pkg.getString("main") != null;
        boolean isLibrary = published && !hasHtmlEntry;
        if (isLibrary) {
            inferences.add("声明了 main 入口且无 HTML 页面入口，推断为可复用组件库而非独立应用");
        } else if (hasHtmlEntry) {
            inferences.add("存在 HTML 页面入口，推断为可独立部署的前端应用");
        }
        return isLibrary;
    }

    private static Map<String, Object> dependenciesOf(JSONObject pkg) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String field : List.of("dependencies", "devDependencies", "peerDependencies")) {
            JSONObject section = pkg.getJSONObject(field);
            if (section != null) {
                for (String key : section.keySet()) {
                    merged.put(key, section.getString(key));
                }
            }
        }
        return merged;
    }

    private static boolean hasFrontendSignal(Map<String, Object> dependencies, Path appRoot) {
        for (String marker : FRAMEWORK_MARKERS.keySet()) {
            if (dependencies.containsKey(marker)) {
                return true;
            }
        }
        for (String marker : UI_LIBRARY_MARKERS.keySet()) {
            if (dependencies.containsKey(marker)) {
                return true;
            }
        }
        // A directory with an HTML entry point is a frontend app even without recognized deps.
        return Files.exists(appRoot.resolve("index.html"))
                || Files.exists(appRoot.resolve("public/index.html"));
    }

    private static List<String> match(Map<String, Object> dependencies, Map<String, String> markers,
                                      String label, String filePath, List<Evidence> evidence) {
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, String> marker : markers.entrySet()) {
            if (dependencies.containsKey(marker.getKey())) {
                found.add(marker.getValue());
            }
        }
        for (String value : found) {
            evidence.add(new Evidence(label + "为 " + value, filePath));
        }
        return new ArrayList<>(found);
    }

    private static String detectLanguage(Path appRoot, Map<String, Object> dependencies,
                                         List<Evidence> evidence, String appRootRel) {
        boolean typescript = dependencies.containsKey("typescript")
                || Files.exists(appRoot.resolve("tsconfig.json"));
        if (typescript) {
            if (Files.exists(appRoot.resolve("tsconfig.json"))) {
                evidence.add(new Evidence("存在 tsconfig.json，使用 TypeScript", appRootRel + "/tsconfig.json"));
            } else {
                evidence.add(new Evidence("依赖 typescript，使用 TypeScript", appRootRel + "/package.json"));
            }
            return "TypeScript";
        }
        evidence.add(new Evidence("未发现 TypeScript 配置，推断为 JavaScript", appRootRel + "/package.json"));
        return "JavaScript";
    }

    private static String detectPackageManager(Path repoRoot, Path dir, List<Evidence> evidence) {
        String rel = relative(repoRoot, dir);
        if (Files.exists(dir.resolve("pnpm-lock.yaml"))) {
            evidence.add(new Evidence("存在 pnpm-lock.yaml，包管理器为 pnpm", join(rel, "pnpm-lock.yaml")));
            return "pnpm";
        }
        if (Files.exists(dir.resolve("yarn.lock"))) {
            evidence.add(new Evidence("存在 yarn.lock，包管理器为 yarn", join(rel, "yarn.lock")));
            return "yarn";
        }
        if (Files.exists(dir.resolve("bun.lockb")) || Files.exists(dir.resolve("bun.lock"))) {
            evidence.add(new Evidence("存在 bun 锁文件，包管理器为 bun", rel));
            return "bun";
        }
        if (Files.exists(dir.resolve("package-lock.json"))) {
            evidence.add(new Evidence("存在 package-lock.json，包管理器为 npm", join(rel, "package-lock.json")));
            return "npm";
        }
        return null;
    }

    private static String join(String dir, String fileName) {
        return ".".equals(dir) ? fileName : dir + "/" + fileName;
    }

    private static List<String> detectWorkspaceGlobs(Path repoRoot, List<Evidence> evidence) {
        List<String> globs = new ArrayList<>();
        JSONObject pkg = readPackageJson(repoRoot.resolve("package.json"));
        if (pkg != null) {
            Object workspaces = pkg.get("workspaces");
            JSONArray array = null;
            if (workspaces instanceof JSONArray) {
                array = (JSONArray) workspaces;
            } else if (workspaces instanceof JSONObject object) {
                array = object.getJSONArray("packages");
            }
            if (array != null) {
                for (int i = 0; i < array.size(); i++) {
                    String glob = String.valueOf(array.get(i));
                    globs.add(glob);
                    evidence.add(new Evidence("workspace 匹配范围 " + glob, "package.json"));
                }
            }
        }
        Path pnpmWorkspace = repoRoot.resolve("pnpm-workspace.yaml");
        if (Files.exists(pnpmWorkspace)) {
            evidence.add(new Evidence("存在 pnpm-workspace.yaml", "pnpm-workspace.yaml"));
            for (String line : readLines(pnpmWorkspace)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- ")) {
                    String glob = trimmed.substring(2).trim().replace("'", "").replace("\"", "");
                    if (!glob.isEmpty() && !glob.startsWith("#")) {
                        globs.add(glob);
                        evidence.add(new Evidence("pnpm workspace 匹配范围 " + glob, "pnpm-workspace.yaml"));
                    }
                }
            }
        }
        return globs;
    }

    private static JSONObject readPackageJson(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            if (Files.size(path) > MAX_PACKAGE_JSON_BYTES) {
                return null;
            }
            return com.aliyun.autowonder.json.JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static void collectPackageJsonFiles(Path repoRoot, Path dir, int depth, List<Path> out) throws IOException {
        if (depth > MAX_DEPTH) {
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream.sorted().toList();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (SKIPPED_DIRS.contains(name) || name.startsWith(".")) {
                        continue;
                    }
                    collectPackageJsonFiles(repoRoot, entry, depth + 1, out);
                } else if ("package.json".equals(name)) {
                    out.add(entry);
                }
            }
        }
    }

    private static List<String> readLines(Path path) {
        try {
            if (Files.size(path) > MAX_PACKAGE_JSON_BYTES) {
                return List.of();
            }
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String relative(Path repoRoot, Path path) {
        String rel = repoRoot.relativize(path).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
