package com.aliyun.autowonder.access.dto;

import com.aliyun.autowonder.access.OrgAccessLevel;

public class OrgAccessDeniedVO {
    private final OrgAccessLevel current;
    private final OrgAccessLevel required;
    private final String action;

    public OrgAccessDeniedVO(OrgAccessLevel current, OrgAccessLevel required, String action) {
        this.current = current;
        this.required = required;
        this.action = action;
    }

    public OrgAccessLevel getCurrent() {
        return current;
    }

    public OrgAccessLevel getRequired() {
        return required;
    }

    public String getAction() {
        return action;
    }
}
