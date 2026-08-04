package com.aliyun.autowonder.executor;

import lombok.Getter;

public interface TokenService {

    IssuedToken issue(long executorId);

    boolean validate(String tokenRef, String plaintext);

    String resolve(String tokenRef);

    @Getter
    class IssuedToken {
        private final String plaintext;
        private final String tokenRef;

        public IssuedToken(String plaintext, String tokenRef) {
            this.plaintext = plaintext;
            this.tokenRef = tokenRef;
        }
    }
}
