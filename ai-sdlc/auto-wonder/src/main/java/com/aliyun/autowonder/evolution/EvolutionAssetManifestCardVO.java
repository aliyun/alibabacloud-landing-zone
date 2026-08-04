package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionAssetManifestCardVO {
    private String assetType;
    private Long assetId;
    private String name;
    private String category;
    private String triggerHint;
    private String lazyLoadRef;
    private Integer version;
    private Double posteriorMean;
    private Double effectiveSampleSize;
}
