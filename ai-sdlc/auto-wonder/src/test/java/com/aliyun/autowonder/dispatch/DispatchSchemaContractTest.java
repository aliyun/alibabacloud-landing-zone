package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchSchemaContractTest {

    private static final Pattern CANONICAL_WIDTH = Pattern.compile(
            "(?is)CREATE TABLE IF NOT EXISTS `dispatch`\\s*\\(.*?"
                    + "`status`\\s+VARCHAR\\((\\d+)\\)");

    @Test
    void dispatchStatusStorageCanHoldEveryDefinedStatus() throws Exception {
        int requiredWidth = Arrays.stream(DispatchStatus.class.getDeclaredFields())
                .filter(field -> field.getType() == String.class)
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .mapToInt(this::statusLength)
                .max()
                .orElseThrow();

        assertCapacity(Files.readString(Path.of("docs/autowonder-schema.sql")),
                CANONICAL_WIDTH, requiredWidth, "canonical dispatch schema");
    }

    private int statusLength(Field field) {
        try {
            return ((String) field.get(null)).length();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertCapacity(String sql, Pattern pattern, int requiredWidth, String source) {
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), source + " must declare dispatch.status width");
        int actualWidth = Integer.parseInt(matcher.group(1));
        assertTrue(actualWidth >= requiredWidth,
                source + " width " + actualWidth + " is smaller than required " + requiredWidth);
    }
}
