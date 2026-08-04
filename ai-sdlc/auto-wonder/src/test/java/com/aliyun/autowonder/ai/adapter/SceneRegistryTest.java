package com.aliyun.autowonder.ai.adapter;

import com.aliyun.autowonder.ai.AiConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SceneRegistryTest {

    @Test
    void findsAdapterByScene() {
        SceneAdapter adapter = mock(SceneAdapter.class);
        when(adapter.scene()).thenReturn(AiConstants.Scene.CLARIFICATION);
        SceneRegistry registry = new SceneRegistry(List.of(adapter));

        SceneAdapter found = registry.get(AiConstants.Scene.CLARIFICATION);
        assertSame(adapter, found);
    }

    @Test
    void returnsNullForUnknownScene() {
        SceneRegistry registry = new SceneRegistry(List.of());
        assertNull(registry.get("UNKNOWN"));
    }
}
