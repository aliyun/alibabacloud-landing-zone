package com.aliyun.autowonder.artifact;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ArtifactSpringConstructorInjectionTest {

    @Test
    void springSelectsTheProductionRequirementDocumentServiceConstructor() {
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
        Constructor<?>[] candidates = processor.determineCandidateConstructors(
                RequirementDocumentService.class, RequirementDocumentService.class.getName());

        assertNotNull(candidates, "Spring found no injectable RequirementDocumentService constructor");
        assertEquals(1, candidates.length, "Spring found ambiguous RequirementDocumentService constructors");
        assertEquals(6, candidates[0].getParameterCount(),
                "Spring selected the test-only RequirementDocumentService constructor");
    }
}
