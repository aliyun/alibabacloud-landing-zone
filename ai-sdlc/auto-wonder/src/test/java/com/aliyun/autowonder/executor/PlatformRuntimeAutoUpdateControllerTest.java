package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.setting.dto.RuntimeAutoUpdateVO;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformRuntimeAutoUpdateControllerTest {

    /**
     * Reading the switch needs no user-scoped decision any more: it lives in application.yml, so the
     * panel just renders whatever the server reports and every signed-in caller sees the same state.
     */
    @Test
    void viewReportsTheConfiguredSwitchState() {
        ExecutorUpdateService service = mock(ExecutorUpdateService.class);
        RuntimeAutoUpdateVO vo = new RuntimeAutoUpdateVO(true, "0.2.160");
        when(service.runtimeAutoUpdateView()).thenReturn(vo);

        RuntimeAutoUpdateVO data = new PlatformRuntimeAutoUpdateController(service).view().getData();

        assertSame(vo, data);
        assertTrue(data.isExecutorAutoUpdateEnabled(), "the application.yml default is on");
        assertEquals("0.2.160", data.getTargetVersion());
        verify(service).runtimeAutoUpdateView();
    }

    /**
     * Turning the switch off is a deployment change (edit application.yml and restart), never an API
     * call: a write mapping would put runtime mutation back on the server, which is exactly the carrier
     * this design dropped.
     */
    @Test
    void noWriteEndpointExistsForTheDeploymentSetting() {
        List<String> writers = Arrays.stream(PlatformRuntimeAutoUpdateController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(Method::getName)
                .collect(Collectors.toList());

        assertTrue(writers.isEmpty(),
                "the global auto-upgrade switch is configured in application.yml; found write mappings: " + writers);
    }
}
