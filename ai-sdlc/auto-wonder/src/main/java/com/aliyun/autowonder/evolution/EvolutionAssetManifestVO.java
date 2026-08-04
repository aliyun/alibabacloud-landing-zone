package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class EvolutionAssetManifestVO {
    private String contextKey;
    private Integer limit;
    private List<EvolutionAssetManifestCardVO> cards = new ArrayList<>();
}
