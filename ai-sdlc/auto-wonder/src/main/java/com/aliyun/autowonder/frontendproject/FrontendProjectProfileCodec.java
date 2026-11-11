package com.aliyun.autowonder.frontendproject;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a scanned {@link FrontendProjectScanner.CandidateApp} into the two JSON columns of a
 * profile snapshot and reads them back. This is the single place that defines the profile schema,
 * so the frozen {@code design/project-profile.json} handed to the executor and the JSON shown in
 * the UI are guaranteed to be the same versioned shape.
 */
public final class FrontendProjectProfileCodec {

    /** Schema of {@code profile_json}; bump when the structure changes. */
    public static final String PROFILE_SCHEMA_VERSION = "autoWonder.frontendProjectProfile.v1";

    private FrontendProjectProfileCodec() {}

    /** Build {@code profile_json}: identity, stack, and the fact/inference/unknown split. */
    public static String encodeProfile(FrontendProjectScanner.ScanResult scan,
                                       FrontendProjectScanner.CandidateApp app) {
        JSONObject json = new JSONObject();
        json.put("schemaVersion", PROFILE_SCHEMA_VERSION);
        json.put("detectorVersion", FrontendProjectScanner.DETECTOR_VERSION);
        json.put("appRoot", app.appRoot());
        json.put("packageName", app.packageName());
        json.put("language", app.language());
        json.put("packageManager", app.packageManager());
        json.put("privatePackage", app.privatePackage());
        json.put("looksLikeLibrary", app.looksLikeLibrary());
        json.put("monorepo", scan.monorepo());
        json.put("workspaceGlobs", scan.workspaceGlobs());

        JSONObject stack = new JSONObject();
        stack.put("frameworks", app.frameworks());
        stack.put("buildTools", app.buildTools());
        stack.put("uiLibraries", app.uiLibraries());
        stack.put("routers", app.routers());
        stack.put("i18nLibraries", app.i18nLibraries());
        stack.put("testTools", app.testTools());
        json.put("stack", stack);

        // The three-way split is the contract: facts are file-backed, inferences are judgement,
        // unknowns are open questions. They are never merged into one undifferentiated list.
        json.put("inferences", app.inferences());
        json.put("unknowns", app.unknowns());
        json.put("repoUnknowns", scan.unknowns());
        return JSON.toJSONString(json);
    }

    /** Build {@code evidence_json}: each fact paired with the file it was read from. */
    public static String encodeEvidence(FrontendProjectScanner.ScanResult scan,
                                        FrontendProjectScanner.CandidateApp app) {
        JSONObject json = new JSONObject();
        json.put("schemaVersion", PROFILE_SCHEMA_VERSION);
        JSONArray appEvidence = new JSONArray();
        for (FrontendProjectScanner.Evidence evidence : app.evidence()) {
            appEvidence.add(evidence.toJson());
        }
        json.put("appEvidence", appEvidence);
        JSONArray repoEvidence = new JSONArray();
        for (FrontendProjectScanner.Evidence evidence : scan.repoEvidence()) {
            repoEvidence.add(evidence.toJson());
        }
        json.put("repoEvidence", repoEvidence);
        return JSON.toJSONString(json);
    }

    /** Stable 64-hex cache-key component for an app root path. */
    public static String appRootHash(String appRoot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((appRoot == null ? "" : appRoot).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Read back the stack lists from {@code profile_json} for API responses. */
    public static ProfileView decodeProfile(String profileJson) {
        JSONObject json = profileJson == null ? new JSONObject() : JSON.parseObject(profileJson);
        JSONObject stack = json.getJSONObject("stack");
        if (stack == null) {
            stack = new JSONObject();
        }
        return new ProfileView(
                json.getString("schemaVersion"),
                json.getString("detectorVersion"),
                json.getString("appRoot"),
                json.getString("packageName"),
                json.getString("language"),
                json.getString("packageManager"),
                Boolean.TRUE.equals(json.getBoolean("looksLikeLibrary")),
                stringList(stack.getJSONArray("frameworks")),
                stringList(stack.getJSONArray("buildTools")),
                stringList(stack.getJSONArray("uiLibraries")),
                stringList(stack.getJSONArray("routers")),
                stringList(stack.getJSONArray("i18nLibraries")),
                stringList(stack.getJSONArray("testTools")),
                stringList(json.getJSONArray("inferences")),
                stringList(json.getJSONArray("unknowns")));
    }

    private static List<String> stringList(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            for (Object value : array) {
                if (value != null) {
                    out.add(String.valueOf(value));
                }
            }
        }
        return out;
    }

    /** Flattened, API-ready view of a stored profile. */
    public record ProfileView(String schemaVersion,
                              String detectorVersion,
                              String appRoot,
                              String packageName,
                              String language,
                              String packageManager,
                              boolean looksLikeLibrary,
                              List<String> frameworks,
                              List<String> buildTools,
                              List<String> uiLibraries,
                              List<String> routers,
                              List<String> i18nLibraries,
                              List<String> testTools,
                              List<String> inferences,
                              List<String> unknowns) {}
}
