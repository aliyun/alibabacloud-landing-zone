package com.aliyun.autowonder.auth.federation;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.xml.sax.InputSource;

/**
 * 可定制的 SAML Response 夹具：字段默认全部合法，用例只改需要破坏的一处。
 * 签名用 JDK 的 XML-DSig（enveloped，绑定断言 ID），与适配器的校验路径一致。
 */
final class SamlResponseFixture {
    private static final String SAML_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";

    String issuer = "https://adfs.corp.example.com/adfs/services/trust";
    String audience = "autowonder";
    String statusCode = "urn:oasis:names:tc:SAML:2.0:status:Success";
    String responseId = "_response-1";
    String assertionId = "_assertion-1";
    String nameId = "alice@corp.example.com";
    String inResponseTo = "_request-1";
    String recipient = "https://aw.example.com/api/auth/sso/7/acs";
    String subjectConfirmationMethod = "urn:oasis:names:tc:SAML:2.0:cm:bearer";
    String conditionsNotBefore = "-5m";
    String conditionsNotOnOrAfter = "+30m";
    String subjectNotOnOrAfter = "+5m";
    boolean includeAssertion = true;
    boolean includeConditions = true;
    boolean includeStatus = true;
    boolean includeStatusCode = true;
    boolean includeSubject = true;
    boolean includeSubjectConfirmationData = true;
    final Map<String, String> attributes = new LinkedHashMap<>();

    SamlResponseFixture() {
        attributes.put("displayName", "Alice Zhang");
        attributes.put("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
                "alice@corp.example.com");
    }

    String xml() {
        Instant now = Instant.now();
        StringBuilder xml = new StringBuilder();
        xml.append("<samlp:Response xmlns:samlp=\"").append(PROTOCOL_NS)
                .append("\" xmlns:saml=\"").append(SAML_NS)
                .append("\" ID=\"").append(responseId)
                .append("\" Version=\"2.0\" IssueInstant=\"").append(now).append('"');
        if (inResponseTo != null) {
            xml.append(" InResponseTo=\"").append(inResponseTo).append('"');
        }
        xml.append('>');
        xml.append("<saml:Issuer>").append(issuer).append("</saml:Issuer>");
        if (includeStatus) {
            xml.append("<samlp:Status>");
            if (includeStatusCode) {
                xml.append("<samlp:StatusCode Value=\"").append(statusCode).append("\"/>");
            }
            xml.append("</samlp:Status>");
        }
        if (includeAssertion) {
            appendAssertion(xml, now);
        }
        xml.append("</samlp:Response>");
        return xml.toString();
    }

    private void appendAssertion(StringBuilder xml, Instant now) {
        xml.append("<saml:Assertion ID=\"").append(assertionId)
                .append("\" Version=\"2.0\" IssueInstant=\"").append(now).append("\">");
        xml.append("<saml:Issuer>").append(issuer).append("</saml:Issuer>");
        if (includeSubject) {
            xml.append("<saml:Subject><saml:NameID>").append(nameId).append("</saml:NameID>");
            if (includeSubjectConfirmationData) {
                xml.append("<saml:SubjectConfirmation");
                if (subjectConfirmationMethod != null) {
                    xml.append(" Method=\"").append(subjectConfirmationMethod).append('"');
                }
                xml.append('>');
                xml.append("<saml:SubjectConfirmationData");
                if (inResponseTo != null) {
                    xml.append(" InResponseTo=\"").append(inResponseTo).append('"');
                }
                if (recipient != null) {
                    xml.append(" Recipient=\"").append(recipient).append('"');
                }
                xml.append(" NotOnOrAfter=\"").append(offset(subjectNotOnOrAfter)).append("\"/>");
                xml.append("</saml:SubjectConfirmation>");
            }
            xml.append("</saml:Subject>");
        }
        if (includeConditions) {
            xml.append("<saml:Conditions NotBefore=\"").append(offset(conditionsNotBefore))
                    .append("\" NotOnOrAfter=\"").append(offset(conditionsNotOnOrAfter)).append("\">");
            xml.append("<saml:AudienceRestriction><saml:Audience>").append(audience)
                    .append("</saml:Audience></saml:AudienceRestriction>");
            xml.append("</saml:Conditions>");
        }
        xml.append("<saml:AttributeStatement>");
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            xml.append("<saml:Attribute Name=\"").append(attribute.getKey()).append("\">")
                    .append("<saml:AttributeValue>").append(attribute.getValue())
                    .append("</saml:AttributeValue></saml:Attribute>");
        }
        xml.append("</saml:AttributeStatement>");
        xml.append("</saml:Assertion>");
    }

    /** 用给定私钥对断言做 enveloped 签名后整体 Base64：即 IdP 回传的 SAMLResponse 载荷。 */
    String encodedPayload(PrivateKey privateKey) throws Exception {
        Document document = parse(xml());
        Element assertion = (Element) document.getElementsByTagNameNS(SAML_NS, "Assertion").item(0);
        Element target = assertion != null ? assertion : document.getDocumentElement();
        signElement(target, target.getAttribute("ID"), privateKey);
        return Base64.getEncoder().encodeToString(serialize(document).getBytes("UTF-8"));
    }

    /** 签名落在 Response 上（覆盖整个响应）：断言自身可以没有 ID。 */
    String encodedPayloadSignedOnResponse(PrivateKey privateKey) throws Exception {
        Document document = parse(xml());
        Element response = document.getDocumentElement();
        signElement(response, responseId, privateKey);
        return Base64.getEncoder().encodeToString(serialize(document).getBytes("UTF-8"));
    }

    /** 先签名再篡改断言内容：签名值不再匹配。 */
    String encodedPayloadTamperedAfterSigning(PrivateKey privateKey) throws Exception {
        Document document = parse(xml());
        Element assertion = (Element) document.getElementsByTagNameNS(SAML_NS, "Assertion").item(0);
        signElement(assertion, assertion.getAttribute("ID"), privateKey);
        Element nameIdElement = (Element) assertion
                .getElementsByTagNameNS(SAML_NS, "NameID").item(0);
        nameIdElement.setTextContent("mallory@corp.example.com");
        return Base64.getEncoder().encodeToString(serialize(document).getBytes("UTF-8"));
    }

    static String encoded(String xml) {
        return Base64.getEncoder().encodeToString(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void signElement(Element target, String id, PrivateKey privateKey) throws Exception {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        // 无 schema 校验的 DOM 里 ID 属性不是 ID 类型，不注册则 "#id" 引用无法解析。
        target.setIdAttributeNS(null, "ID", true);
        Reference reference = factory.newReference("#" + id,
                factory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                null, null);
        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                        (C14NMethodParameterSpec) null),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                List.of(reference));
        factory.newXMLSignature(signedInfo, null).sign(new DOMSignContext(privateKey, target));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String serialize(Document document) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private static String offset(String expression) {
        if (expression == null || !expression.matches("[+-][0-9]+m")) {
            return expression;
        }
        char sign = expression.charAt(0);
        long minutes = Long.parseLong(expression.substring(1, expression.length() - 1));
        Instant instant = sign == '-' ? Instant.now().minus(minutes, ChronoUnit.MINUTES)
                : Instant.now().plus(minutes, ChronoUnit.MINUTES);
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
