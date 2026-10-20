package com.aliyun.autowonder.common.i18n;

import com.aliyun.autowonder.common.error.ErrorCode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SystemMessagesResourceTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");

    @Test void pairedUtf8ResourcesCoverEveryEnumAndCoreMessage() throws Exception {
        var zh = read("zh_CN");
        var en = read("en_US");
        validate(zh, en);
        for (var code : ErrorCode.values()) {
            String key = "error." + code.name().toLowerCase(Locale.ROOT);
            assertTrue(zh.containsKey(key), key);
            assertEquals(code.getMessage(), zh.getProperty(key), key);
        }
        assertTrue(zh.keySet().containsAll(Set.of("validation.field_invalid", "error.conflict_retry",
                "request.method_not_supported", "access.workspace_denied")));
    }

    @Test void directEnglishMissingKeyCannotBeMaskedByChineseFallback() throws Exception {
        var zh = read("zh_CN");
        var en = read("en_US");
        en.remove("error.unauthorized");
        assertThrows(AssertionError.class, () -> validate(zh, en));
    }

    @Test void mismatchedParameterOrMalformedTemplateFailsGate() throws Exception {
        var zh = read("zh_CN");
        var en = read("en_US");
        en.setProperty("validation.field_invalid", "{1} is invalid");
        assertThrows(AssertionError.class, () -> validate(zh, en));
        en.setProperty("validation.field_invalid", "{name} is invalid");
        assertThrows(IllegalArgumentException.class, () -> validate(zh, en));
        en.setProperty("validation.field_invalid", "{0,number} is invalid");
        assertThrows(AssertionError.class, () -> validate(zh, en));
        en.setProperty("validation.field_invalid", "'{0}' is invalid");
        assertThrows(AssertionError.class, () -> validate(zh, en));
        en.setProperty("validation.field_invalid", " ");
        assertThrows(AssertionError.class, () -> validate(zh, en));
        en.setProperty("validation.field_invalid", "{0} is invalid");
        zh.setProperty("check.multiple", "{0} {1}");
        en.setProperty("check.multiple", "'{0}' {1}");
        assertThrows(AssertionError.class, () -> validate(zh, en));
    }

    private static Properties read(String locale) throws Exception {
        var properties = new Properties();
        var stream = SystemMessagesResourceTest.class.getResourceAsStream("/i18n/messages_" + locale + ".properties");
        assertNotNull(stream, locale);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void validate(Properties zh, Properties en) {
        assertEquals(zh.keySet(), en.keySet(), "Locale key sets");
        for (String key : zh.stringPropertyNames()) {
            assertTrue(key.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"), key);
            assertEquals(parameters(zh.getProperty(key)), parameters(en.getProperty(key)), key);
            assertFalse(en.getProperty(key).matches("(?s).*[\\p{IsHan}].*"), key);
        }
    }

    private static Set<Integer> parameters(String text) {
        assertNotNull(text);
        assertFalse(text.isBlank(), "Blank resource");
        var format = new MessageFormat(text, Locale.US);
        var matcher = PLACEHOLDER.matcher(text);
        Set<Integer> indexes = new HashSet<>();
        while (matcher.find()) indexes.add(Integer.parseInt(matcher.group(1)));
        assertFalse(PLACEHOLDER.matcher(text).replaceAll("").matches("(?s).*[{}].*"), text);
        // Quoted placeholders are legal MessageFormat syntax but fail to interpolate.
        int max = indexes.stream().mapToInt(Integer::intValue).max().orElse(-1);
        Object[] args = new Object[max + 1];
        for (int i = 0; i < args.length; i++) args[i] = "__argument_" + i + "__";
        String rendered = format.format(args);
        for (int index : indexes) assertTrue(rendered.contains((String) args[index]), "Inactive placeholder: " + text);
        return indexes;
    }
}
