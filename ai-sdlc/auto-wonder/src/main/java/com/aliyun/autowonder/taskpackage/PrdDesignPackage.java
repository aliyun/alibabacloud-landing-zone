package com.aliyun.autowonder.taskpackage;

import java.util.Map;

/**
 * The frozen PRD inputs for one dispatch: the files to write under {@code design/} plus the manifest
 * block describing them.
 *
 * <p>Produced by {@code PrdDeliveryFreezeService} and consumed by {@code TaskPackager}. Keeping the
 * file map here (rather than letting the packager query design state itself) means packaging cannot
 * drift onto a different version: the bytes handed over were already resolved and digest-verified.
 *
 * @param files            archive entry path -> bytes, always including design/prd.html,
 *                         design/requirements.json and design/project-profile.json
 * @param manifest         the {@code design} block merged into the package manifest.json
 * @param designVersionId  the pinned version, recorded for traceability
 * @param versionNo        the pinned version number
 * @param designSha256     the pinned content digest
 */
public record PrdDesignPackage(Map<String, byte[]> files,
                               Map<String, Object> manifest,
                               Long designVersionId,
                               Integer versionNo,
                               String designSha256) {

    public static final String HTML_ENTRY = "design/prd.html";
    public static final String REQUIREMENTS_ENTRY = "design/requirements.json";
    public static final String PROJECT_PROFILE_ENTRY = "design/project-profile.json";
    public static final String MANIFEST_ENTRY = "design/manifest.json";
}
