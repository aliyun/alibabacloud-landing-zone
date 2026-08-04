package com.aliyun.autowonder.im;

import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.im.dto.UpdateUserImIdentityRequest;
import org.junit.jupiter.api.Test;

import javax.validation.Valid;
import javax.validation.Validation;
import javax.validation.Validator;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void dingTalkRequestEnforcesDatabaseColumnLengths() {
        UpdateDingTalkChannelRequest request = new UpdateDingTalkChannelRequest();
        request.setAppKey("a".repeat(129));
        request.setRobotCode("r".repeat(129));
        request.setAppSecret("s".repeat(1025));
        request.setBaseUrl("https://" + "b".repeat(505));

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertEquals(Set.of("appKey", "robotCode", "appSecret", "baseUrl"), invalidFields);
    }

    @Test
    void identityRequestEnforcesDatabaseColumnLength() {
        UpdateUserImIdentityRequest request = new UpdateUserImIdentityRequest();
        request.setExternalUserId("u".repeat(257));

        assertEquals(Set.of("externalUserId"), validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet()));
    }

    @Test
    void updateControllersTriggerBeanValidation() throws Exception {
        Method channelUpdate = PlatformImChannelConfigController.class.getMethod(
                "updateDingTalk", UpdateDingTalkChannelRequest.class);
        Method identityUpdate = UserImIdentityController.class.getMethod(
                "updateDingTalk", UpdateUserImIdentityRequest.class);

        assertTrue(channelUpdate.getParameters()[0].isAnnotationPresent(Valid.class));
        assertTrue(identityUpdate.getParameters()[0].isAnnotationPresent(Valid.class));
    }
}
