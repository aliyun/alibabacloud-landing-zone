package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionAssetManifestQuery {
    private String assetType;
    private String contextKey;
    private Integer limit;
}
