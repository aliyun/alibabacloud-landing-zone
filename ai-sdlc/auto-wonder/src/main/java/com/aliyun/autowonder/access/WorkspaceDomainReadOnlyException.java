package com.aliyun.autowonder.access;

public class WorkspaceDomainReadOnlyException extends RuntimeException {
    private final WorkspaceDomain domain;
    private final String action;
    private final String actionKey;

    public WorkspaceDomainReadOnlyException(WorkspaceDomain domain, String action, String actionKey) {
        super("该功能为只读，无法" + action);
        this.domain = domain;
        this.action = action;
        this.actionKey = actionKey;
    }

    public WorkspaceDomain getDomain() {
        return domain;
    }

    public String getAction() {
        return action;
    }

    public String getActionKey() {
        return actionKey;
    }
}
