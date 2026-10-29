package com.aliyun.autowonder.im.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured robot-identity conflict carried in the failed Result. Only Workspaces the current
 * user may view are listed; {@code restricted} says another, unreadable occupant exists.
 */
public class ImIdentityConflictView {
    private String reason;
    private List<ImConflictWorkspaceView> workspaces = new ArrayList<>();
    private boolean restricted;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<ImConflictWorkspaceView> getWorkspaces() {
        return workspaces;
    }

    public void setWorkspaces(List<ImConflictWorkspaceView> workspaces) {
        this.workspaces = workspaces == null ? new ArrayList<>() : workspaces;
    }

    public boolean isRestricted() {
        return restricted;
    }

    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }
}
