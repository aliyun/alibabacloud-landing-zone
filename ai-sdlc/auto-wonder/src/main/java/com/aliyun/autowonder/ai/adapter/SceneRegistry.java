package com.aliyun.autowonder.ai.adapter;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SceneRegistry {

    private final Map<String, SceneAdapter> adapters = new HashMap<>();

    public SceneRegistry(List<SceneAdapter> adapterList) {
        for (SceneAdapter a : adapterList) {
            adapters.put(a.scene(), a);
        }
    }

    public SceneAdapter get(String scene) {
        return adapters.get(scene);
    }
}
