package com.aliyun.autowonder.build;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendPackagingPomTest {

    @Test
    void frontendBuildIsEnabledByDefaultForCiPackageBuilds() throws Exception {
        Document pom = parsePom();

        assertEquals("false", textOfFirst(pom, "skipFrontend"),
                "CI uses fixed Maven parameters, so the default package build must include frontend assets");
        assertEquals("v22.22.2", textOfFirst(pom, "node.version"),
                "The reproducible community build must use a supported Node.js runtime");
        assertEquals("10.9.7", textOfFirst(pom, "npm.version"),
                "The Maven frontend build must pin its npm version");
    }

    @Test
    void packageLifecycleFailsWhenFrontendStaticAssetsAreMissing() throws Exception {
        String xml = Files.readString(Path.of("pom.xml"));
        String packageJson = Files.readString(Path.of("frontend/package.json"));
        String packageLock = Files.readString(Path.of("frontend/package-lock.json"));

        assertTrue(xml.contains("<id>auto-wonder</id>"),
                "The default Maven profile must include the frontend build");
        assertTrue(xml.contains("ci --include=optional --cache ../target/npm-cache"),
                "npm ci must include optional dependencies and use a project-local cache because Vite/Rollup uses platform-specific optional packages");
        assertTrue(xml.contains("verify-frontend-static-assets"),
                "Maven package lifecycle should verify static frontend assets before producing deployable artifacts");
        assertTrue(xml.contains("target/classes/static/index.html"),
                "Verification must check that the embedded SPA entry exists in the packaged classpath");
        assertTrue(xml.contains("target/classes/static/assets"),
                "Verification must check that hashed Vite assets exist in the packaged classpath");
        assertTrue(packageJson.contains("\"vite\": \"6.4.3\""),
                "The frontend build must keep its tested Vite version pinned");
        assertTrue(packageLock.contains("\"node_modules/rollup\":") && packageLock.contains("\"version\": \"4.62.4\""),
                "The lockfile should resolve the tested Rollup 4 toolchain");
        assertTrue(packageLock.contains("@rollup/rollup-linux-x64-gnu"),
                "The community Linux x86_64 build requires Rollup's reviewed native package");
    }

    private Document parsePom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(new File("pom.xml"));
    }

    private String textOfFirst(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        assertTrue(nodes.getLength() > 0, "Missing POM tag: " + tagName);
        return nodes.item(0).getTextContent().trim();
    }
}
