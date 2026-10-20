package com.aliyun.autowonder.access;

public class WorkspaceAccessDeniedException extends RuntimeException {
    private final WorkspaceAccessLevel current;
    private final WorkspaceAccessLevel required;
    private final String action;
    private final String actionKey;

    public WorkspaceAccessDeniedException(WorkspaceAccessLevel current, WorkspaceAccessLevel required, String action) {
        this(current, required, action, null);
    }

    public WorkspaceAccessDeniedException(WorkspaceAccessLevel current, WorkspaceAccessLevel required,
                                          String action, String actionKey) {
        super("工作空间访问级别不足，无法" + action);
        this.current = current;
        this.required = required;
        this.action = action;
        this.actionKey = actionKey;
    }

    public WorkspaceAccessLevel getCurrent() {
        return current;
    }

    public WorkspaceAccessLevel getRequired() {
        return required;
    }

    public String getActionKey() { return actionKey; }

    public String getAction() {
        return action;
    }
}
