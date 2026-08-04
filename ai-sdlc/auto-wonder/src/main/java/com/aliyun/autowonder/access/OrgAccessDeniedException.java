package com.aliyun.autowonder.access;

public class OrgAccessDeniedException extends RuntimeException {
    private final OrgAccessLevel current;
    private final OrgAccessLevel required;
    private final String action;

    public OrgAccessDeniedException(OrgAccessLevel current, OrgAccessLevel required, String action) {
        super("组织访问级别不足，无法" + action);
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
