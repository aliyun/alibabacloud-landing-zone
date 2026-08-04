package com.aliyun.autowonder.im;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ImProviderRegistry {
    private final Map<String, ImProvider> providers;

    public ImProviderRegistry(List<ImProvider> providers) {
        Map<String, ImProvider> indexed = new LinkedHashMap<>();
        for (ImProvider provider : providers) {
            String name = normalize(provider.provider());
            if (indexed.putIfAbsent(name, provider) != null) {
                throw new IllegalStateException("Duplicate IM provider: " + name);
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public ImProvider require(String provider) {
        String normalized = normalize(provider);
        ImProvider selected = providers.get(normalized);
        if (selected == null) {
            throw new IllegalArgumentException("Unsupported IM provider: " + normalized);
        }
        return selected;
    }

    private static String normalize(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("IM provider is required");
        }
        return provider.trim().toUpperCase(Locale.ROOT);
    }
}
