package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class EvolutionDeltaIngestionResult {
    private int acceptedCount;
    private List<EvolutionOrchestrateResult> results = new ArrayList<>();
}
