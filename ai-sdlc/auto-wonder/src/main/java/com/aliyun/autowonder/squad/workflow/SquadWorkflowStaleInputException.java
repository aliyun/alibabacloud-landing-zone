package com.aliyun.autowonder.squad.workflow;

/**
 * The caller generated against an input hash that no longer matches the online configuration.
 * The fresh view travels with the failure so the page can immediately render the current hash
 * instead of issuing a second round trip that could race the same publish again.
 */
public class SquadWorkflowStaleInputException extends RuntimeException {

    private final transient SquadWorkflowView current;

    public SquadWorkflowStaleInputException(SquadWorkflowView current) {
        super("squad workflow input changed since the caller read it");
        this.current = current;
    }

    public SquadWorkflowView getCurrent() {
        return current;
    }
}
