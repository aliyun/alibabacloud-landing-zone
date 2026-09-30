package com.aliyun.autowonder.json;

public final class JSONValidator {
    private final String json;

    private JSONValidator(String json) {
        this.json = json;
    }

    public static JSONValidator from(String json) {
        return new JSONValidator(json);
    }

    public boolean validate() {
        return JSON.isValid(json);
    }
}
