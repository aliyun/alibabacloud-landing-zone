package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.json.JSONException;
import com.aliyun.autowonder.util.MessageDigestUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Serializes {@link PrdDesignData} into the stored {@code design_json} and derives both artifacts a
 * version must carry: the HTML PRD (via {@link PrdHtmlRenderer}) and {@code requirements.json}.
 *
 * <p>Both derivatives come from this one parsed model, which is what makes the spec's "禁止分别生成
 * 后产生内容偏差" requirement structurally true rather than a promise: there is no second source for
 * either document. Requirement keys are normalized here so they stay stable across versions and
 * acceptance results keep pointing at the same requirement after an edit.
 */
public final class PrdDesignCodec {

    // Models may emit literal newlines inside mockHtml. Preserve the string's
    // content while decoding this embedded design; serialize() still writes
    // canonical JSON. Do not relax the application's shared JSON parser.
    private static final ObjectReader DESIGN_READER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readerFor(PrdDesignData.class)
            .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());

    /** Key prefix for requirements the model did not number explicitly. */
    static final String REQUIREMENT_KEY_PREFIX = "REQ-";
    /** Key prefix for open questions the model did not number explicitly. */
    static final String QUESTION_KEY_PREFIX = "Q-";

    private PrdDesignCodec() {}

    public static String serialize(PrdDesignData data) {
        return JSON.toJSONString(data);
    }

    public static PrdDesignData parse(String designJson) {
        if (designJson == null || designJson.isBlank()) {
            return new PrdDesignData();
        }
        try {
            PrdDesignData data = DESIGN_READER.readValue(designJson);
            return data == null ? new PrdDesignData() : data;
        } catch (JsonProcessingException exception) {
            throw new JSONException("Unable to parse PRD design JSON", exception);
        }
    }

    /** The digest bound at confirmation and re-verified before delivery. */
    public static String digest(String designJson) {
        return MessageDigestUtil.sha256Hex(designJson == null ? "" : designJson);
    }

    /**
     * Assign missing keys and reject duplicates. Keys already present are never rewritten, so a
     * requirement keeps its identity — and its acceptance history — across versions.
     *
     * @return descriptions of key problems that normalization could not fix
     */
    public static List<String> normalizeKeys(PrdDesignData data) {
        List<String> problems = new ArrayList<>();
        assignKeys(data.getRequirements(), REQUIREMENT_KEY_PREFIX, problems);
        assignKeys(data.getOpenQuestions(), QUESTION_KEY_PREFIX, problems);
        return problems;
    }

    private static void assignKeys(List<? extends Keyed> items, String prefix, List<String> problems) {
        Set<String> seen = new LinkedHashSet<>();
        int next = 1;
        for (Keyed item : items) {
            if (item == null) {
                continue;
            }
            String key = item.getKey();
            if (key == null || key.isBlank()) {
                while (seen.contains(prefix + String.format("%03d", next))) {
                    next++;
                }
                key = prefix + String.format("%03d", next);
                item.setKey(key);
                seen.add(key);
            } else {
                key = key.trim();
                item.setKey(key);
                if (!seen.add(key)) {
                    problems.add(problem("duplicate_key", new Object[]{key}));
                }
            }
        }
    }

    private static String problem(String key, Object[] args) {
        return com.aliyun.autowonder.common.i18n.SystemMessages.render(
                "prd.validation." + key, args, key);
    }

    /** Interface for the two numbered collections so they share one key-assignment rule. */
    public interface Keyed {
        String getKey();
        void setKey(String key);
    }

    /**
     * Build {@code requirements.json} from the same model the HTML was rendered from. Contains the
     * stable keys plus acceptance criteria, which is what delivery reports results against.
     */
    public static String exportRequirements(PrdDesignData data) {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", "autoWonder.prdRequirements.v1");
        root.put("designSchemaVersion", data.getSchemaVersion());
        root.put("title", data.getTitle());

        JSONArray requirements = new JSONArray();
        for (PrdDesignData.Requirement requirement : data.getRequirements()) {
            if (requirement == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("key", requirement.getKey());
            item.put("title", requirement.getTitle());
            item.put("description", requirement.getDescription());
            item.put("priority", requirement.getPriority());
            item.put("excluded", requirement.isExcluded());
            JSONArray criteria = new JSONArray();
            criteria.addAll(requirement.getAcceptanceCriteria());
            item.put("acceptanceCriteria", criteria);
            requirements.add(item);
        }
        root.put("requirements", requirements);

        JSONArray questions = new JSONArray();
        for (PrdDesignData.OpenQuestion question : data.getOpenQuestions()) {
            if (question == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("key", question.getKey());
            item.put("question", question.getQuestion());
            item.put("blocking", question.isBlocking());
            item.put("resolved", question.isResolved());
            questions.add(item);
        }
        root.put("openQuestions", questions);
        return JSON.toJSONString(root);
    }

    /**
     * Confirmation gate from spec §7: requirements with criteria, and no unresolved blocking
     * question. Returns human-readable problems; an empty list means the version can be confirmed.
     */
    public static List<String> validateForConfirmation(PrdDesignData data) {
        return validateForConfirmation(data, false);
    }

    public static List<String> validateForConfirmation(PrdDesignData data, boolean ignoreOpenQuestions) {
        List<String> problems = new ArrayList<>();
        if (data.getTitle() == null || data.getTitle().isBlank()) {
            problems.add(problem("title_missing", new Object[]{}));
        }
        List<PrdDesignData.Requirement> active = data.activeRequirements();
        if (active.isEmpty()) {
            problems.add(problem("scope_empty", new Object[]{}));
        }
        for (PrdDesignData.Requirement requirement : active) {
            if (requirement.getAcceptanceCriteria().isEmpty()) {
                problems.add(problem("criteria_missing", new Object[]{requirement.getKey()}));
            }
        }
        for (PrdDesignData.OpenQuestion question : ignoreOpenQuestions
                ? List.<PrdDesignData.OpenQuestion>of() : data.unresolvedBlockingQuestions()) {
            problems.add(problem("question_open", new Object[]{question.getKey(), question.getQuestion()}));
        }
        return problems;
    }
}
