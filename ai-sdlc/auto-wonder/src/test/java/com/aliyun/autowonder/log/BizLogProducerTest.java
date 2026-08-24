package com.aliyun.autowonder.log;

import com.aliyun.autowonder.configuration.SlsProperties;
import com.aliyun.openservices.log.common.LogItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BizLogProducerTest {

    private static final Set<String> CORE_FIELDS = Set.of(
            "RequestId", "UserId", "WorkspaceId", "Operation", "Path", "HttpMethod",
            "HttpStatus", "Success", "TotalUsedTimeMs", "RequestTime",
            "ErrorCode", "ErrorMsg");

    @Test
    void toLogItemContainsOnlyAutoWonderCoreFields() {
        LogItem item = ReflectionTestUtils.invokeMethod(
                new BizLogProducer(new SlsProperties()), "toLogItem", new BizLog());

        Set<String> keys = item.GetLogContents().stream()
                .map(content -> content.GetKey())
                .collect(Collectors.toSet());

        assertEquals(CORE_FIELDS, keys);
    }
}
