package com.aliyun.autowonder.branding;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.w3c.dom.Node;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.Set;
import java.util.regex.Pattern;

/** Accept static SVG artwork only; never resolve entities or external resources. */
final class SvgLogoValidator {
    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final Set<String> ELEMENTS = Set.of(
            "svg", "g", "defs", "title", "desc", "path", "rect", "circle", "ellipse", "line",
            "polyline", "polygon", "text", "tspan", "textPath", "linearGradient", "radialGradient",
            "stop", "clipPath", "mask", "pattern", "symbol", "use");
    private static final Set<String> PRESENTATION = Set.of(
            "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity",
            "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset",
            "opacity", "color", "stop-color", "stop-opacity", "clip-path", "clip-rule", "mask",
            "display", "visibility", "font-family", "font-size", "font-weight", "font-style",
            "text-anchor", "dominant-baseline", "letter-spacing", "word-spacing", "vector-effect",
            "paint-order", "shape-rendering");
    private static final Set<String> ATTRIBUTES = Set.of(
            "id", "version", "viewBox", "preserveAspectRatio", "width", "height", "x", "y", "x1", "y1",
            "x2", "y2", "cx", "cy", "r", "rx", "ry", "d", "points", "transform", "dx", "dy",
            "rotate", "textLength", "lengthAdjust", "startOffset", "offset", "gradientUnits",
            "gradientTransform", "spreadMethod", "fx", "fy", "fr", "clipPathUnits", "maskUnits",
            "maskContentUnits", "patternUnits", "patternContentUnits", "patternTransform");
    private static final Pattern LOCAL_URL = Pattern.compile("url\\(\\s*['\"]?#[\\w.-]+['\"]?\\s*\\)");

    static void validate(byte[] bytes) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            var document = builder.parse(new ByteArrayInputStream(bytes));
            if (!"svg".equals(document.getDocumentElement().getLocalName())) {
                throw new IllegalArgumentException();
            }
            checkNode(document, 0);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "SVG 必须为有效的静态图形，不支持脚本、外部资源、样式表或动画，请导出为使用行内样式的 SVG");
        }
    }

    private static void checkNode(Node node, int depth) {
        if (depth > 64 || node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
            throw new IllegalArgumentException();
        }
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            if (!SVG_NS.equals(node.getNamespaceURI()) || !ELEMENTS.contains(node.getLocalName())) {
                throw new IllegalArgumentException();
            }
            var attributes = node.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attribute = attributes.item(i);
                String name = attribute.getLocalName();
                String value = attribute.getNodeValue();
                String namespace = attribute.getNamespaceURI();
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)) continue;
                if ("href".equals(name) && (namespace == null || "http://www.w3.org/1999/xlink".equals(namespace))) {
                    if (!value.matches("#[\\w.-]+")) throw new IllegalArgumentException();
                } else if (namespace == null && "style".equals(name)) {
                    for (String declaration : value.split(";")) {
                        if (declaration.isBlank()) continue;
                        String[] parts = declaration.split(":", 2);
                        if (parts.length != 2 || !PRESENTATION.contains(parts[0].trim())) throw new IllegalArgumentException();
                        checkValue(parts[1]);
                    }
                } else if (namespace == null && (ATTRIBUTES.contains(name) || PRESENTATION.contains(name))) {
                    checkValue(value);
                } else {
                    throw new IllegalArgumentException();
                }
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            checkNode(child, depth + 1);
        }
    }

    private static void checkValue(String value) {
        // Remove local paint references before rejecting CSS escapes and resource URLs.
        String remaining = LOCAL_URL.matcher(value).replaceAll("");
        if (!remaining.matches("[\\p{L}\\p{N}\\s#.,%()+\\-'\"!*=_]*")
                || remaining.toLowerCase(java.util.Locale.ROOT).contains("url")) {
            throw new IllegalArgumentException();
        }
    }
}
