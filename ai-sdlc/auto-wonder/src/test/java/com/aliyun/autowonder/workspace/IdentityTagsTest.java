package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityTagsTest {

    @Test
    void nullNormalizesToEmpty() {
        assertEquals(List.of(), IdentityTags.normalize(null));
    }

    @Test
    void normalizationTrimsDropsEmptyDeduplicatesAndPreservesFirstSeenOrder() {
        List<String> normalized = IdentityTags.normalize(
                List.of(" reviewer ", "", "developer", "reviewer", "  ", "developer ", "owner"));

        assertEquals(List.of("reviewer", "developer", "owner"), normalized);
    }

    @Test
    void normalizationDropsNullElements() {
        assertEquals(List.of("reviewer"),
                IdentityTags.normalize(java.util.Arrays.asList(null, " reviewer ", null)));
    }

    @Test
    void normalizationAllowsEightUniqueTags() {
        assertEquals(8, IdentityTags.normalize(List.of(
                "one", "two", "three", "four", "five", "six", "seven", "eight")).size());
    }

    @Test
    void normalizationRejectsMoreThanEightUniqueTags() {
        BizException exception = assertThrows(BizException.class, () -> IdentityTags.normalize(List.of(
                "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("8"));
    }

    @Test
    void normalizationMeasuresMaximumTagLengthAfterTrimmingInJavaCharacters() {
        String allowed = "x".repeat(32);
        assertEquals(List.of(allowed), IdentityTags.normalize(List.of(" " + allowed + " ")));

        BizException exception = assertThrows(BizException.class,
                () -> IdentityTags.normalize(List.of("x".repeat(33))));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("32"));
    }

    @Test
    void jsonSerializationIsDeterministicAndUsesNormalizedOrder() {
        assertEquals("[]", IdentityTags.toJson(null));
        assertEquals("[\"reviewer\",\"developer\"]",
                IdentityTags.toJson(List.of(" reviewer ", "developer", "reviewer")));
    }

    @Test
    void dbNullAndJsonNullDeserializeToEmpty() {
        assertEquals(List.of(), IdentityTags.fromJson(null));
        assertEquals(List.of(), IdentityTags.fromJson("null"));
        assertEquals(List.of(), IdentityTags.fromJson("  null  "));
    }

    @Test
    void jsonDeserializationNormalizesTags() {
        assertEquals(List.of("reviewer", "developer"),
                IdentityTags.fromJson("[\" reviewer \",\"developer\",\"reviewer\",\"\"]"));
    }

    @Test
    void malformedOrWronglyTypedPersistedJsonFailsClosed() {
        assertMalformed("");
        assertMalformed("{\"tag\":\"reviewer\"}");
        assertMalformed("[\"reviewer\",1]");
        assertMalformed("[");
    }

    @Test
    void persistedArrayRejectsNullElements() {
        assertMalformed("[\"a\",null]");
    }

    @Test
    void persistedJsonRejectsTrailingCommas() {
        assertMalformed("[\"a\",]");
    }

    @Test
    void persistedJsonRejectsSingleQuotedStrings() {
        assertMalformed("['a']");
    }

    @Test
    void persistedJsonStillEnforcesTagLimits() {
        BizException tooMany = assertThrows(BizException.class,
                () -> IdentityTags.fromJson(
                        "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\"]"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), tooMany.getCode());

        BizException tooLong = assertThrows(BizException.class,
                () -> IdentityTags.fromJson("[\"" + "x".repeat(33) + "\"]"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), tooLong.getCode());
    }

    private static void assertMalformed(String json) {
        BizException exception = assertThrows(BizException.class, () -> IdentityTags.fromJson(json));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), exception.getCode());
        assertTrue(exception.getMessage().toLowerCase().contains("identity tags"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "normalizeLength|Identity tags must not exceed 32 Java characters per tag|每个身份标签不能超过 32 个 Java 字符|Identity tags must not exceed 32 Java characters per tag.",
        "toJsonLength|Identity tags must not exceed 32 Java characters per tag|每个身份标签不能超过 32 个 Java 字符|Identity tags must not exceed 32 Java characters per tag.",
        "fromJsonLength|Identity tags must not exceed 32 Java characters per tag|每个身份标签不能超过 32 个 Java 字符|Identity tags must not exceed 32 Java characters per tag.",
        "normalizeCount|Identity tags must not contain more than 8 tags|身份标签不能超过 8 个|Identity tags must not contain more than 8 tags.",
        "toJsonCount|Identity tags must not contain more than 8 tags|身份标签不能超过 8 个|Identity tags must not contain more than 8 tags.",
        "fromJsonCount|Identity tags must not contain more than 8 tags|身份标签不能超过 8 个|Identity tags must not contain more than 8 tags.",
        "persistedCorruption|Invalid persisted identity tags JSON|持久化的身份标签 JSON 无效|Invalid persisted identity tags JSON."
    })
    void tagValidationLocalizesOnlyOwnedDetailWithRawJsonAndUtf16RulesIntact(
            String branch, String diagnostic, String chinese, String english) {
        List<String> tags = branch.endsWith("Length") ? List.of("😀".repeat(17))
                : List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");
        String json = branch.equals("persistedCorruption") ? "[\"<b>raw</b>\",null]"
                : com.aliyun.autowonder.json.JSON.toJSONString(tags);
        BizException error = assertThrows(BizException.class, () -> {
            if (branch.startsWith("normalize")) IdentityTags.normalize(tags);
            else if (branch.startsWith("toJson")) IdentityTags.toJson(tags);
            else IdentityTags.fromJson(json);
        });
        assertEquals(BizException.class, error.getClass());
        assertEquals("10001", error.getCode());
        assertEquals(diagnostic, error.getMessage());
        org.junit.jupiter.api.Assertions.assertNull(error.getCause());
        assertEquals(400, new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error).getStatusCodeValue());
        assertEquals(chinese, com.aliyun.autowonder.common.i18n.SystemMessages.render(
                error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.CHINA));
        assertEquals(english, com.aliyun.autowonder.common.i18n.SystemMessages.render(
                error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.US));
    }

}
