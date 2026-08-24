package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExternalPrincipalRef {
    private String subjectId;
    private String displayName;

    public static ExternalPrincipalRef user(String subjectId, String displayName) {
        if (subjectId == null || subjectId.isBlank()) {
            return null;
        }
        ExternalPrincipalRef principal = new ExternalPrincipalRef();
        principal.setSubjectId(subjectId);
        principal.setDisplayName(displayName);
        return principal;
    }
}
