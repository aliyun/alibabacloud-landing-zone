package com.aliyun.autowonder.branding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformBrandingSchemaContractTest {

    @Test
    void mcpEndpointIsDeploymentManagedRatherThanPersistedBrandingData() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String brandingTable = tableDefinition(schema, "platform_branding_config");
        String mapper = Files.readString(
                Path.of("src/main/resources/mapping/PlatformBrandingDao.xml"));

        assertFalse(brandingTable.contains("mcp_base_url"));
        assertFalse(mapper.contains("mcp_base_url"));
    }

    @Test
    void seededBrandingDomainIsUnconfiguredSoLinksUseDeploymentBaseUrl() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));

        int insertStart = schema.indexOf("INSERT INTO `platform_branding_config`");
        assertTrue(insertStart >= 0, "missing platform_branding_config seed insert");
        int insertEnd = schema.indexOf(";", insertStart);
        String seedInsert = schema.substring(insertStart, insertEnd);
        assertTrue(seedInsert.contains("NULL"),
                "seed domain must be NULL so unconfigured deployments fall back to public-base-url");
        assertFalse(seedInsert.contains("auto-wonder.alibaba.net"),
                "seed must not preset the internal default domain");
    }

    private static String tableDefinition(String schema, String table) {
        var matcher = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\((.*?)\\)\\s*ENGINE=")
                .matcher(schema);
        assertTrue(matcher.find(), "missing canonical table " + table);
        return matcher.group(1);
    }
}
