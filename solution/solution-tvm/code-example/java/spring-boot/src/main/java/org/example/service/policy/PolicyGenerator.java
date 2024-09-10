package org.example.service.policy;

import com.samskivert.mustache.Mustache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PolicyGenerator {

    private final List<String> templates = new ArrayList<>();

    private final Map<String, String> data = new HashMap<>();

    public static PolicyGenerator generator() {
        return new PolicyGenerator();
    }

    public PolicyGenerator oss(String bucket, String dir) {
        templates.add("policy-templates/OssTemplate.json");
        data.put("bucket", bucket);
        data.put("dir", dir);
        return this;
    }

    public PolicyGenerator sls(String project, String logstore) {
        templates.add("policy-templates/SlsTemplate.json");
        data.put("project", project);
        data.put("logstore", logstore);
        return this;
    }

    public String generatePolicy() {
        if(templates.isEmpty()) {
            throw new RuntimeException("A scoped policy must contain at least one statement");
        }
        String statements = PolicyTemplateLoader.assemblePolicyTemplates(templates);
        String resolvedStatements = Mustache.compiler().compile(statements).execute(data);
        String policy = "{ \"Version\": \"1\",\n  \"Statement\": [\n" + resolvedStatements + " ]\n}";
        return policy.replaceAll("\\s+", "");
    }
}
