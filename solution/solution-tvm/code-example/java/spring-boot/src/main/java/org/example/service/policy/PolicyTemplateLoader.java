package org.example.service.policy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class PolicyTemplateLoader {

    public static String retrieveTemplate(final String policyTemplate) throws IOException {
        InputStream inputStream = PolicyTemplateLoader.class.getResourceAsStream(policyTemplate);
        if (inputStream == null) {
            inputStream = PolicyTemplateLoader.class.getClassLoader().getResourceAsStream(policyTemplate);
        }
        InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        BufferedReader reader = new BufferedReader(inputStreamReader);
        StringBuilder sb = new StringBuilder();
        String str;
        while((str = reader.readLine())!= null){
            sb.append(str).append("\n");
        }
        return sb.toString();
    }

    public static String assemblePolicyTemplates(List<String> templates) {
        List<String> statements = new ArrayList<>();

        for (String template : templates) {
            String policy;
            try {
                policy = retrieveTemplate(template);
            } catch (IOException e) {
                throw new RuntimeException("Unable to locate template for " + template);
            }
            statements.add(policy);
        }
        return String.join(",", statements);
    }
}
