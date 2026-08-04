package com.aliyun.autowonder.configuration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigarNativeLoaderTest {

    @Test
    void supportsOnlyLinuxX8664() {
        assertTrue(SigarNativeLoader.isSupported("Linux", "amd64"));
        assertTrue(SigarNativeLoader.isSupported("Linux", "x86_64"));
        assertFalse(SigarNativeLoader.isSupported("Linux", "aarch64"));
        assertFalse(SigarNativeLoader.isSupported("Mac OS X", "x86_64"));
    }

    @Test
    void eachExtractionUsesAUniquePrivateDirectory() throws Exception {
        SigarNativeLoader loader = new SigarNativeLoader(target -> Files.writeString(target, "native"));

        Path first = loader.extract();
        Path second = loader.extract();

        assertNotEquals(first, second);
        assertTrue(Files.isRegularFile(first.resolve(SigarNativeLoader.LIBRARY)));
        assertTrue(Files.isRegularFile(second.resolve(SigarNativeLoader.LIBRARY)));
    }
}
