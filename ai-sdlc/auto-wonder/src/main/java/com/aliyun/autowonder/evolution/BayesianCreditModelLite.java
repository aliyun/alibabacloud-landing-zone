package com.aliyun.autowonder.evolution;

import org.springframework.stereotype.Component;

@Component
public class BayesianCreditModelLite {

    public CreditAssignment assign(BayesianEvidenceCommand cmd) {
		double credit = cmd.getWeight() == null ? 1.0 : clampWeight(cmd.getWeight());
		return new CreditAssignment(credit, cmd.getEvidenceJson());
    }

    private double clampWeight(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record CreditAssignment(double credit, String evidenceJson) {
    }
}
