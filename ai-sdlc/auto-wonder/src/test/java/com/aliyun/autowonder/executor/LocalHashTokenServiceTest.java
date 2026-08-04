package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalHashTokenServiceTest {

    @Test
    void issued_token_validates_and_ref_is_not_plaintext() {
        LocalHashTokenService svc = new LocalHashTokenService();
        TokenService.IssuedToken t = svc.issue(42L);

        assertNotNull(t.getPlaintext());
        assertNotNull(t.getTokenRef());
        assertNotEquals(t.getPlaintext(), t.getTokenRef());
        assertTrue(svc.validate(t.getTokenRef(), t.getPlaintext()));
    }

    @Test
    void wrong_plaintext_fails_validation() {
        LocalHashTokenService svc = new LocalHashTokenService();
        TokenService.IssuedToken t = svc.issue(42L);
        assertFalse(svc.validate(t.getTokenRef(), t.getPlaintext() + "x"));
        assertFalse(svc.validate(t.getTokenRef(), "totally-different"));
    }

    @Test
    void two_issues_differ() {
        LocalHashTokenService svc = new LocalHashTokenService();
        assertNotEquals(svc.issue(1L).getPlaintext(), svc.issue(1L).getPlaintext());
    }
}
