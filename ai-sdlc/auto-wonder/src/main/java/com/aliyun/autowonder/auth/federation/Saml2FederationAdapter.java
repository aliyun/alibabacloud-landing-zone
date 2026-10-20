package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;

/**
 * SAML 2.0 适配器（HTTP-Redirect 发起 + HTTP-POST 回调）。
 * 校验：断言签名（enveloped，绑定到被消费的断言/响应的 ID）、Issuer、Audience、
 * 生效窗口、InResponseTo 关联、Recipient、断言 ID 重放占用；全部通过才输出外部身份。
 */
@Component
public class Saml2FederationAdapter implements FederationProtocolAdapter {
    static final String SAML_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";
    static final String STATUS_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
    static final String BEARER_CONFIRMATION = "urn:oasis:names:tc:SAML:2.0:cm:bearer";

    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final int MIN_REPLAY_TTL_SECONDS = 60;
    private static final int MAX_REPLAY_TTL_SECONDS = 86400;

    private final FederationStateStore stateStore;
    private final FederationProperties properties;

    public Saml2FederationAdapter(FederationStateStore stateStore, FederationProperties properties) {
        this.stateStore = stateStore;
        this.properties = properties;
    }

    @Override
    public FederationProtocol protocol() {
        return FederationProtocol.SAML2;
    }

    @Override
    public AuthorizationRedirect buildAuthorizationRedirect(FederationConnectionDO connection,
                                                             String callbackUrl, String state) {
        requireConfigured(connection, connection.getSamlSsoUrl(), "samlSsoUrl");
        requireConfigured(connection, connection.getSamlCert(), "samlCert");
        requireConfigured(connection, connection.getIssuer(), "issuer");
        String requestId = "_" + UUID.randomUUID().toString().replace("-", "");
        String request = authnRequest(connection, callbackUrl, requestId);
        String encoded = Base64.getEncoder().encodeToString(deflate(request.getBytes(StandardCharsets.UTF_8)));
        String separator = connection.getSamlSsoUrl().contains("?") ? "&" : "?";
        String url = connection.getSamlSsoUrl() + separator
                + "SAMLRequest=" + urlEncode(encoded)
                + "&RelayState=" + urlEncode(state);
        return new AuthorizationRedirect(url, Map.of("requestId", requestId));
    }

    @Override
    public ExternalIdentity verifyCallback(FederationConnectionDO connection,
                                           FederationCallbackContext context,
                                           Map<String, String> stateAttributes) {
        String payload = context.parameter("SAMLResponse");
        if (payload == null || payload.isBlank()) {
            throw invalid(connection, "missing SAMLResponse", null);
        }
        Document document;
        try {
            document = parse(Base64.getDecoder().decode(payload.trim()));
        } catch (Exception e) {
            throw invalid(connection, "SAMLResponse cannot be parsed", e);
        }
        Element response = document.getDocumentElement();
        if (!PROTOCOL_NS.equals(response.getNamespaceURI()) || !"Response".equals(response.getLocalName())) {
            throw invalid(connection, "root element is not a SAML Response", null);
        }
        if (!STATUS_SUCCESS.equals(statusCode(response))) {
            throw invalid(connection, "response status is not success", null);
        }
        Element assertion = firstDescendant(response, SAML_NS, "Assertion");
        if (assertion == null) {
            throw invalid(connection, "response carries no assertion", null);
        }
        verifySignature(response, assertion, connection);
        verifyIssuer(assertion, connection);
        verifyConditions(assertion, connection);
        Element subject = firstChild(assertion, SAML_NS, "Subject");
        Element confirmationData = bearerConfirmationData(subject, connection);
        verifyCorrelation(response, confirmationData, stateAttributes, connection);
        verifyRecipient(confirmationData, context.callbackUrl(), connection);
        verifySubjectConfirmationWindow(confirmationData, connection);
        String assertionId = assertion.getAttribute("ID");
        if (assertionId == null || assertionId.isBlank()) {
            throw invalid(connection, "assertion has no ID and cannot be replay-protected", null);
        }
        if (!stateStore.claimAssertion(connection.getId(), assertionId,
                replayTtlSeconds(assertion, confirmationData))) {
            throw new BizException(ErrorCode.FEDERATION_REPLAY_DETECTED);
        }
        String nameId = textOf(firstChild(subject, SAML_NS, "NameID"));
        String subjectValue = resolveSubject(connection, assertion, nameId);
        if (subjectValue == null || subjectValue.isBlank()) {
            throw invalid(connection, "subject is empty after claim mapping", null);
        }
        return new ExternalIdentity(connection.getId(), connection.getIssuer(), subjectValue,
                attributeValue(assertion, connection.getNameClaim(), "displayName", "name"),
                attributeValue(assertion, connection.getEmailClaim(), "emailaddress", "email"));
    }

    private String authnRequest(FederationConnectionDO connection, String callbackUrl, String requestId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<samlp:AuthnRequest xmlns:samlp=\"" + PROTOCOL_NS + "\" xmlns:saml=\"" + SAML_NS + "\""
                + " ID=\"" + requestId + "\" Version=\"2.0\" IssueInstant=\"" + INSTANT.format(Instant.now()) + "\""
                + " Destination=\"" + escape(connection.getSamlSsoUrl()) + "\""
                + " AssertionConsumerServiceURL=\"" + escape(callbackUrl) + "\""
                + " ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\">"
                + "<saml:Issuer>" + escape(properties.getSpEntityId()) + "</saml:Issuer>"
                + "<samlp:NameIDPolicy AllowCreate=\"true\""
                + " Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified\"/>"
                + "</samlp:AuthnRequest>";
    }

    private void verifySignature(Element response, Element assertion, FederationConnectionDO connection) {
        PublicKey key = certificateKey(connection);
        List<Element> signatures = descendants(response, DSIG_NS, "Signature");
        for (Element signature : signatures) {
            Node parent = signature.getParentNode();
            boolean coversAssertion = parent == assertion && referencesConfiguredId(signature, assertion);
            boolean coversResponse = parent == response && referencesConfiguredId(signature, response);
            if ((coversAssertion || coversResponse) && validate(signature, key)) {
                return;
            }
        }
        throw invalid(connection, "no trusted signature covers the assertion", null);
    }

    private boolean validate(Element signature, PublicKey key) {
        try {
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            DOMValidateContext context = new DOMValidateContext(key, signature);
            registerIdAttributes(signature.getOwnerDocument().getDocumentElement(), context);
            XMLSignature xmlSignature = factory.unmarshalXMLSignature(context);
            return xmlSignature.validate(context);
        } catch (Exception e) {
            return false;
        }
    }

    // SAML 的 ID 属性在无 schema 校验的 DOM 里不是 ID 类型，必须显式注册，否则引用无法解析。
    private void registerIdAttributes(Node node, DOMValidateContext context) {
        if (node instanceof Element element && element.hasAttribute("ID")) {
            context.setIdAttributeNS(element, null, "ID");
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            registerIdAttributes(children.item(i), context);
        }
    }

    private boolean referencesConfiguredId(Element signature, Element signed) {
        String id = signed.getAttribute("ID");
        if (id == null || id.isBlank()) {
            return false;
        }
        for (Element reference : descendants(signature, DSIG_NS, "Reference")) {
            if (("#" + id).equals(reference.getAttribute("URI"))) {
                return true;
            }
        }
        return false;
    }

    private PublicKey certificateKey(FederationConnectionDO connection) {
        String certificate = connection.getSamlCert();
        if (certificate == null || certificate.isBlank()) {
            throw configInvalid(connection, "samlCert is not configured");
        }
        String normalized = certificate
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            return x509.getPublicKey();
        } catch (Exception e) {
            throw configInvalid(connection, "samlCert cannot be parsed", e);
        }
    }

    private void verifyIssuer(Element assertion, FederationConnectionDO connection) {
        requireConfigured(connection, connection.getIssuer(), "issuer");
        String issuer = textOf(firstChild(assertion, SAML_NS, "Issuer"));
        if (!normalize(connection.getIssuer()).equals(normalize(issuer))) {
            throw invalid(connection, "issuer mismatch", null);
        }
    }

    private void verifyConditions(Element assertion, FederationConnectionDO connection) {
        Element conditions = firstChild(assertion, SAML_NS, "Conditions");
        if (conditions == null) {
            throw invalid(connection, "assertion carries no Conditions", null);
        }
        Instant now = Instant.now();
        long skew = properties.getClockSkewSeconds();
        String notBefore = conditions.getAttribute("NotBefore");
        if (!notBefore.isBlank() && parseInstant(notBefore, connection).isAfter(now.plusSeconds(skew))) {
            throw invalid(connection, "assertion is not yet valid", null);
        }
        String notOnOrAfter = conditions.getAttribute("NotOnOrAfter");
        if (!notOnOrAfter.isBlank() && !parseInstant(notOnOrAfter, connection).isAfter(now.minusSeconds(skew))) {
            throw invalid(connection, "assertion has expired", null);
        }
        List<String> audiences = new ArrayList<>();
        for (Element restriction : descendants(conditions, SAML_NS, "AudienceRestriction")) {
            for (Element audience : children(restriction, SAML_NS, "Audience")) {
                audiences.add(textOf(audience));
            }
        }
        boolean audienceMatched = audiences.stream()
                .anyMatch(audience -> normalize(properties.getSpEntityId()).equals(normalize(audience)));
        if (!audienceMatched) {
            throw invalid(connection, "assertion audience does not include the SP entity id", null);
        }
    }

    /**
     * 只接受 bearer 确认方式，且必须携带 SubjectConfirmationData：
     * holder-of-key 等其他方式缺少承载型约束，不能用于换发会话。
     */
    private Element bearerConfirmationData(Element subject, FederationConnectionDO connection) {
        if (subject == null) {
            throw invalid(connection, "assertion carries no Subject", null);
        }
        for (Element confirmation : children(subject, SAML_NS, "SubjectConfirmation")) {
            if (BEARER_CONFIRMATION.equals(confirmation.getAttribute("Method"))) {
                Element data = firstChild(confirmation, SAML_NS, "SubjectConfirmationData");
                if (data == null) {
                    throw invalid(connection, "bearer SubjectConfirmation carries no SubjectConfirmationData", null);
                }
                return data;
            }
        }
        throw invalid(connection, "assertion carries no bearer SubjectConfirmation", null);
    }

    private void verifyCorrelation(Element response, Element confirmationData,
                                   Map<String, String> stateAttributes, FederationConnectionDO connection) {
        String expected = stateAttributes == null ? null : stateAttributes.get("requestId");
        String actual = confirmationData.getAttribute("InResponseTo");
        if (actual == null || actual.isBlank()) {
            actual = response.getAttribute("InResponseTo");
        }
        if (expected == null || expected.isBlank() || !expected.equals(actual)) {
            throw invalid(connection, "InResponseTo does not match the login request", null);
        }
    }

    // Recipient 是 bearer 断言必填的约束：缺失或空白必须拒绝，不能当作「未配置」放行。
    private void verifyRecipient(Element confirmationData, String callbackUrl, FederationConnectionDO connection) {
        String recipient = confirmationData.getAttribute("Recipient");
        if (recipient.isBlank() || !normalize(recipient).equals(normalize(callbackUrl))) {
            throw invalid(connection, "assertion recipient does not match the callback address", null);
        }
    }

    private void verifySubjectConfirmationWindow(Element confirmationData, FederationConnectionDO connection) {
        String notOnOrAfter = confirmationData.getAttribute("NotOnOrAfter");
        if (notOnOrAfter.isBlank()) {
            throw invalid(connection, "SubjectConfirmationData carries no NotOnOrAfter", null);
        }
        if (!parseInstant(notOnOrAfter, connection).isAfter(Instant.now().minusSeconds(properties.getClockSkewSeconds()))) {
            throw invalid(connection, "subject confirmation has expired", null);
        }
    }

    private int replayTtlSeconds(Element assertion, Element confirmationData) {
        String expiry = confirmationData.getAttribute("NotOnOrAfter");
        if (expiry.isBlank()) {
            Element conditions = firstChild(assertion, SAML_NS, "Conditions");
            expiry = conditions == null ? "" : conditions.getAttribute("NotOnOrAfter");
        }
        long ttl = MIN_REPLAY_TTL_SECONDS;
        if (!expiry.isBlank()) {
            try {
                ttl = Instant.parse(expiry).getEpochSecond() - Instant.now().getEpochSecond();
            } catch (RuntimeException ignored) {
                ttl = MIN_REPLAY_TTL_SECONDS;
            }
        }
        return (int) Math.max(MIN_REPLAY_TTL_SECONDS, Math.min(MAX_REPLAY_TTL_SECONDS, ttl));
    }

    private String resolveSubject(FederationConnectionDO connection, Element assertion, String nameId) {
        String claim = connection.getSubjectClaim();
        if (claim == null || claim.isBlank() || "nameid".equalsIgnoreCase(claim.trim())) {
            return nameId;
        }
        String value = attributeValue(assertion, claim.trim());
        if (value == null || value.isBlank()) {
            throw invalid(connection, "subject claim " + claim.trim() + " is missing from the assertion", null);
        }
        return value;
    }

    private String attributeValue(Element assertion, String claim, String... fallbacks) {
        List<String> names = new ArrayList<>();
        if (claim != null && !claim.isBlank()) {
            names.add(claim.trim());
        }
        names.addAll(List.of(fallbacks));
        for (String name : names) {
            for (Element attribute : descendants(assertion, SAML_NS, "Attribute")) {
                if (matchesClaimName(attribute.getAttribute("Name"), name)) {
                    String value = textOf(firstChild(attribute, SAML_NS, "AttributeValue"));
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private boolean matchesClaimName(String configuredName, String wanted) {
        if (configuredName == null || configuredName.isBlank()) {
            return false;
        }
        if (configuredName.equals(wanted)) {
            return true;
        }
        int slash = configuredName.lastIndexOf('/');
        int colon = configuredName.lastIndexOf(':');
        int cut = Math.max(slash, colon);
        return cut >= 0 && cut + 1 < configuredName.length()
                && configuredName.substring(cut + 1).equals(wanted);
    }

    private Instant parseInstant(String value, FederationConnectionDO connection) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw invalid(connection, "unparseable timestamp in assertion", e);
        }
    }

    private Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {
                // Malformed input is expected on a public callback endpoint; diagnostics are not needed.
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return builder.parse(new InputSource(new StringReader(new String(xml, StandardCharsets.UTF_8))));
    }

    private String statusCode(Element response) {
        Element status = firstChild(response, PROTOCOL_NS, "Status");
        Element code = status == null ? null : firstChild(status, PROTOCOL_NS, "StatusCode");
        return code == null ? null : code.getAttribute("Value");
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element
                    && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static Element firstDescendant(Element root, String namespace, String localName) {
        List<Element> matches = descendants(root, namespace, localName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> matches = new ArrayList<>();
        if (parent == null) {
            return matches;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element
                    && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                matches.add(element);
            }
        }
        return matches;
    }

    private static List<Element> descendants(Node root, String namespace, String localName) {
        List<Element> matches = new ArrayList<>();
        collectDescendants(root, namespace, localName, matches);
        return matches;
    }

    private static void collectDescendants(Node node, String namespace, String localName, List<Element> matches) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                if (namespace.equals(element.getNamespaceURI()) && localName.equals(element.getLocalName())) {
                    matches.add(element);
                }
                collectDescendants(element, namespace, localName, matches);
            }
        }
    }

    private static String textOf(Element element) {
        return element == null ? null : element.getTextContent().trim();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private void requireConfigured(FederationConnectionDO connection, String value, String field) {
        if (value == null || value.isBlank()) {
            throw configInvalid(connection, field + " is not configured");
        }
    }

    private BizException invalid(FederationConnectionDO connection, String diagnostic, Throwable cause) {
        return BizException.keyed(ErrorCode.FEDERATION_ASSERTION_INVALID,
                ErrorCode.FEDERATION_ASSERTION_INVALID.getMessageKey(), null,
                "provider " + (connection == null ? "?" : connection.getId()) + ": " + diagnostic, cause);
    }

    private BizException configInvalid(FederationConnectionDO connection, String diagnostic) {
        return configInvalid(connection, diagnostic, null);
    }

    private BizException configInvalid(FederationConnectionDO connection, String diagnostic, Throwable cause) {
        return BizException.keyed(ErrorCode.FEDERATION_CONFIG_INVALID,
                ErrorCode.FEDERATION_CONFIG_INVALID.getMessageKey(), null,
                "provider " + (connection == null ? "?" : connection.getId()) + ": " + diagnostic, cause);
    }
}
