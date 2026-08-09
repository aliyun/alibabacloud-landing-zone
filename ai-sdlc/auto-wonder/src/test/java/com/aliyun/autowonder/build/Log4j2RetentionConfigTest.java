package com.aliyun.autowonder.build;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class Log4j2RetentionConfigTest {

    @Test
    void localFileAppenderBoundsArchivedLogsByAgeAndSize() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Path.of("src/main/resources/log4j2.xml").toFile());

        Element rolling = findByName(document, "RollingFile", "LocalFile");
        assertEquals("logs/auto-wonder.log", rolling.getAttribute("fileName"));
        assertEquals("logs/auto-wonder-%d{yyyy-MM-dd}-%i.log.gz", rolling.getAttribute("filePattern"));

        Element policies = child(rolling, "Policies");
        assertEquals("50MB", child(policies, "SizeBasedTriggeringPolicy").getAttribute("size"));
        Element time = child(policies, "TimeBasedTriggeringPolicy");
        assertEquals("1", time.getAttribute("interval"));
        assertEquals("true", time.getAttribute("modulate"));

        Element strategy = child(rolling, "DefaultRolloverStrategy");
        Element delete = child(strategy, "Delete");
        assertEquals("logs", delete.getAttribute("basePath"));
        assertEquals("1", delete.getAttribute("maxDepth"));
        assertEquals("false", delete.getAttribute("followLinks"));
        assertEquals("true", child(delete, "SortByModificationTime").getAttribute("recentFirst"));

        Element fileName = child(delete, "IfFileName");
        assertEquals("auto-wonder-*.log.gz", fileName.getAttribute("glob"));
        Element any = child(fileName, "IfAny");
        assertEquals("P14D", child(any, "IfLastModified").getAttribute("age"));
        assertEquals("5 GB", child(any, "IfAccumulatedFileSize").getAttribute("exceeds"));
    }

    private static Element findByName(Document document, String tagName, String name) {
        for (int index = 0; index < document.getElementsByTagName(tagName).getLength(); index++) {
            Element element = (Element) document.getElementsByTagName(tagName).item(index);
            if (name.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        return fail("Missing " + tagName + " named " + name);
    }

    private static Element child(Element parent, String tagName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return fail("Missing direct child " + tagName + " under " + parent.getNodeName());
    }
}
