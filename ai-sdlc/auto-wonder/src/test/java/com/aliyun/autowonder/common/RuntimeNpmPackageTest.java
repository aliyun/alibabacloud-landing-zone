package com.aliyun.autowonder.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeNpmPackageTest {
    @Test void preservesHistoricalPinsAndSelectsScopedPackageAtMigrationBoundary() {
        for (String version : new String[]{"0.2.152", "0.3.30", "0.3.31", "0.3.32"}) {
            assertEquals("autowonder@" + version, RuntimeNpmPackage.spec(version));
        }
        for (String version : new String[]{"0.3.33", "0.3.34", "0.4.0", "1.0.0", "0.9.9-rc.1"}) {
            assertEquals("@alicloud/autowonder@" + version, RuntimeNpmPackage.spec(version));
        }
    }

    @Test void rejectsTagsAndShellFragments() {
        for (String version : new String[]{"latest", "0.3.33;exit", "0.3.33 --tag beta"}) {
            assertThrows(IllegalArgumentException.class, () -> RuntimeNpmPackage.spec(version));
        }
    }
}
