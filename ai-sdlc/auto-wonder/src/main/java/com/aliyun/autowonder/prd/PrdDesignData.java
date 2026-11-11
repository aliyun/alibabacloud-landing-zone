package com.aliyun.autowonder.prd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The versioned structured design data. This is the single source of truth for a PRD version: the
 * HTML document is rendered from it by {@link PrdHtmlRenderer} and {@code requirements.json} is
 * serialized from it, so the two can never drift apart.
 *
 * <p>Requirement keys are stable across versions — an edit keeps the key of the requirement it
 * changes, and a deleted requirement keeps its key in history so acceptance results stay linked.
 */
public class PrdDesignData {

    /** Schema of {@code design_json}; bump when the structure changes. */
    public static final String SCHEMA_VERSION = "autoWonder.prdDesign.v1";

    private String schemaVersion = SCHEMA_VERSION;
    private String title;
    private String language = "zh-CN";
    private PrdProjectSnapshot projectSnapshot;
    private String background;
    private String goals;
    private List<String> userRoles = new ArrayList<>();
    private List<String> inScope = new ArrayList<>();
    private List<String> nonGoals = new ArrayList<>();
    private List<FeatureEntry> featureEntries = new ArrayList<>();
    private List<FlowStep> userFlows = new ArrayList<>();
    private List<PageDesign> pages = new ArrayList<>();
    private List<DataField> dataFields = new ArrayList<>();
    private List<InterfaceDependency> interfaces = new ArrayList<>();
    private String targetProjectAdaptation;
    private List<Requirement> requirements = new ArrayList<>();
    private List<OpenQuestion> openQuestions = new ArrayList<>();
    private List<String> risks = new ArrayList<>();
    private String changeLog;

    /** A functional entry point: where the capability is reached from. */
    public static class FeatureEntry {
        private String location;
        private String capability;

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getCapability() { return capability; }
        public void setCapability(String capability) { this.capability = capability; }
    }

    /** One step of a core user flow. */
    public static class FlowStep {
        private int order;
        private String actor;
        private String action;

        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }
        public String getActor() { return actor; }
        public void setActor(String actor) { this.actor = actor; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }

    /** A page layout and interaction prototype description, including its states. */
    public static class PageDesign {
        private String pageKey;
        private String name;
        private String route;
        private String layout;
        private List<String> interactions = new ArrayList<>();
        private String normalState;
        private String loadingState;
        private String emptyState;
        private String errorState;
        private String forbiddenState;
        private String mockHtml;

        public String getPageKey() { return pageKey; }
        public void setPageKey(String pageKey) { this.pageKey = pageKey; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
        public String getLayout() { return layout; }
        public void setLayout(String layout) { this.layout = layout; }
        public List<String> getInteractions() { return interactions; }
        public void setInteractions(List<String> interactions) { this.interactions = safeList(interactions); }
        public String getNormalState() { return normalState; }
        public void setNormalState(String normalState) { this.normalState = normalState; }
        public String getLoadingState() { return loadingState; }
        public void setLoadingState(String loadingState) { this.loadingState = loadingState; }
        public String getEmptyState() { return emptyState; }
        public void setEmptyState(String emptyState) { this.emptyState = emptyState; }
        public String getErrorState() { return errorState; }
        public void setErrorState(String errorState) { this.errorState = errorState; }
        public String getForbiddenState() { return forbiddenState; }
        public void setForbiddenState(String forbiddenState) { this.forbiddenState = forbiddenState; }
        public String getMockHtml() { return mockHtml; }
        public void setMockHtml(String mockHtml) { this.mockHtml = mockHtml; }
    }

    /** A data field with its validation rules. */
    public static class DataField {
        private String name;
        private String type;
        private boolean required;
        private String validation;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public String getValidation() { return validation; }
        public void setValidation(String validation) { this.validation = validation; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /**
     * An interface the design depends on. {@code status} distinguishes an existing capability from
     * one that is still planned, so the PRD never presents a guess as something that already works.
     */
    public static class InterfaceDependency {
        private String name;
        private String method;
        private String path;
        /** EXISTING / PLANNED / TO_BE_CONFIRMED */
        private String status;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /** A numbered requirement with its acceptance criteria. The key is stable across versions. */
    public static class Requirement implements PrdDesignCodec.Keyed {
        private String key;
        private String title;
    private String language = "zh-CN";
    private PrdProjectSnapshot projectSnapshot;
        private String description;
        private String priority;
        private List<String> acceptanceCriteria = new ArrayList<>();
        /** True when the requirement is explicitly excluded from this delivery round. */
        private boolean excluded;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public PrdProjectSnapshot getProjectSnapshot() { return projectSnapshot; }
    public void setProjectSnapshot(PrdProjectSnapshot snapshot) { this.projectSnapshot = snapshot; }
    public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public List<String> getAcceptanceCriteria() { return acceptanceCriteria; }
        public void setAcceptanceCriteria(List<String> acceptanceCriteria) {
            this.acceptanceCriteria = safeList(acceptanceCriteria);
        }
        public boolean isExcluded() { return excluded; }
        public void setExcluded(boolean excluded) { this.excluded = excluded; }
    }

    /** An open question. A blocking one must be resolved or excluded before confirmation. */
    public static class OpenQuestion implements PrdDesignCodec.Keyed {
        private String key;
        private String question;
        private boolean blocking;
        private boolean resolved;
        private String resolution;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public boolean isBlocking() { return blocking; }
        public void setBlocking(boolean blocking) { this.blocking = blocking; }
        public boolean isResolved() { return resolved; }
        public void setResolved(boolean resolved) { this.resolved = resolved; }
        public String getResolution() { return resolution; }
        public void setResolution(String resolution) { this.resolution = resolution; }
    }

    /** Requirement keys that are neither resolved nor explicitly excluded — the confirmation gate. */
    public List<OpenQuestion> unresolvedQuestions() {
        List<OpenQuestion> out = new ArrayList<>();
        for (OpenQuestion question : safeObjects(openQuestions)) {
            if (question != null && !question.isResolved()) {
                out.add(question);
            }
        }
        return out;
    }

    public List<OpenQuestion> unresolvedBlockingQuestions() {
        List<OpenQuestion> out = new ArrayList<>();
        for (OpenQuestion question : unresolvedQuestions()) {
            if (question.isBlocking()) {
                out.add(question);
            }
        }
        return out;
    }

    /** Requirements in delivery scope, i.e. not explicitly excluded. */
    public List<Requirement> activeRequirements() {
        List<Requirement> out = new ArrayList<>();
        for (Requirement requirement : safeObjects(requirements)) {
            if (requirement != null && !requirement.isExcluded()) {
                out.add(requirement);
            }
        }
        return out;
    }

    /** Requirement keys, de-duplicated and in declaration order. */
    public Set<String> requirementKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Requirement requirement : safeObjects(requirements)) {
            if (requirement != null && requirement.getKey() != null) {
                keys.add(requirement.getKey());
            }
        }
        return keys;
    }

    private static List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public PrdProjectSnapshot getProjectSnapshot() { return projectSnapshot; }
    public void setProjectSnapshot(PrdProjectSnapshot snapshot) { this.projectSnapshot = snapshot; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public String getGoals() { return goals; }
    public void setGoals(String goals) { this.goals = goals; }
    public List<String> getUserRoles() { return userRoles; }
    public void setUserRoles(List<String> userRoles) { this.userRoles = safeList(userRoles); }
    public List<String> getInScope() { return inScope; }
    public void setInScope(List<String> inScope) { this.inScope = safeList(inScope); }
    public List<String> getNonGoals() { return nonGoals; }
    public void setNonGoals(List<String> nonGoals) { this.nonGoals = safeList(nonGoals); }
    public List<FeatureEntry> getFeatureEntries() { return featureEntries; }
    public void setFeatureEntries(List<FeatureEntry> featureEntries) {
        this.featureEntries = safeObjects(featureEntries);
    }
    public List<FlowStep> getUserFlows() { return userFlows; }
    public void setUserFlows(List<FlowStep> userFlows) { this.userFlows = safeObjects(userFlows); }
    public List<PageDesign> getPages() { return pages; }
    public void setPages(List<PageDesign> pages) { this.pages = safeObjects(pages); }
    public List<DataField> getDataFields() { return dataFields; }
    public void setDataFields(List<DataField> dataFields) { this.dataFields = safeObjects(dataFields); }
    public List<InterfaceDependency> getInterfaces() { return interfaces; }
    public void setInterfaces(List<InterfaceDependency> interfaces) { this.interfaces = safeObjects(interfaces); }
    public String getTargetProjectAdaptation() { return targetProjectAdaptation; }
    public void setTargetProjectAdaptation(String targetProjectAdaptation) {
        this.targetProjectAdaptation = targetProjectAdaptation;
    }
    public List<Requirement> getRequirements() { return requirements; }
    public void setRequirements(List<Requirement> requirements) { this.requirements = safeObjects(requirements); }
    public List<OpenQuestion> getOpenQuestions() { return openQuestions; }
    public void setOpenQuestions(List<OpenQuestion> openQuestions) {
        this.openQuestions = safeObjects(openQuestions);
    }
    public List<String> getRisks() { return risks; }
    public void setRisks(List<String> risks) { this.risks = safeList(risks); }
    public String getChangeLog() { return changeLog; }
    public void setChangeLog(String changeLog) { this.changeLog = changeLog; }

    private static <T> List<T> safeObjects(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
