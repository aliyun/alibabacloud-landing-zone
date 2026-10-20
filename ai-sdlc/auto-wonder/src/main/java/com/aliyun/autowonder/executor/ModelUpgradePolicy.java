package com.aliyun.autowonder.executor;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** Ordered, user-defined thresholds. No implicit upgrade threshold. */
@Getter
@Setter
public class ModelUpgradePolicy {
    private boolean enabled;
    private List<Level> levels;
    @Getter
    @Setter
    public static class Level {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = EntryThresholdDeserializer.class)
        private Integer entryCount;
        private String model;
    }

    /** Do not silently truncate JSON decimal thresholds to an integer. */
    public static class EntryThresholdDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
