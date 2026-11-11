package com.aliyun.autowonder.frontendproject.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** One detected frontend application: identity, stack, and the fact/inference/unknown split. */
@Getter
@Setter
public class FrontendProjectProfileVO {
    private Long id;
    private Long repoId;
    private Long probeId;
    private String branch;
    private String commitSha;
    private String appRoot;
    private String packageName;
    private String detectorVersion;
    private String language;
    private String packageManager;
    private boolean looksLikeLibrary;
    private boolean stale;

    private List<String> frameworks = new ArrayList<>();
    private List<String> buildTools = new ArrayList<>();
    private List<String> uiLibraries = new ArrayList<>();
    private List<String> routers = new ArrayList<>();
    private List<String> i18nLibraries = new ArrayList<>();
    private List<String> testTools = new ArrayList<>();

    private List<String> inferences = new ArrayList<>();
    private List<String> unknowns = new ArrayList<>();
    private List<EvidenceVO> evidence = new ArrayList<>();

    /** A fact paired with the file it was read from, so conclusions stay traceable. */
    @Getter
    @Setter
    public static class EvidenceVO {
        private String fact;
        private String filePath;
    }
}
