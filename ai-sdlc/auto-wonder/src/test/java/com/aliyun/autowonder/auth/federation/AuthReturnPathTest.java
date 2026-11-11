package com.aliyun.autowonder.auth.federation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthReturnPathTest {

    @Test
    void acceptsOnlyAbsoluteInSitePathsAndKeepsQueryAndHash() {
        assertEquals("/workitems/42", AuthReturnPath.sanitize("/workitems/42"));
        assertEquals("/workitems/42?tab=discussion#comment-9",
                AuthReturnPath.sanitize(" /workitems/42?tab=discussion#comment-9 "));
    }

    @Test
    void rejectsExternalProtocolRelativeAndBackslashTargets() {
        assertNull(AuthReturnPath.sanitize(null));
        assertNull(AuthReturnPath.sanitize(""));
        assertNull(AuthReturnPath.sanitize("workitems/42"));
        assertNull(AuthReturnPath.sanitize("https://evil.example.com/steal"));
        assertNull(AuthReturnPath.sanitize("//evil.example.com/steal"));
        assertNull(AuthReturnPath.sanitize("/\\evil.example.com/steal"));
        assertNull(AuthReturnPath.sanitize("javascript:alert(1)"));
        assertNull(AuthReturnPath.sanitize("/workitems/\u0000/42"));
    }

    @Test
    void rejectsNormalizedProtocolRelativePathsAndAuthEntryPages() {
        assertNull(AuthReturnPath.sanitize("/..//evil.example.com"));
        assertNull(AuthReturnPath.sanitize("/login"));
        assertNull(AuthReturnPath.sanitize("/login?local=1"));
        assertNull(AuthReturnPath.sanitize("/register"));
        assertNull(AuthReturnPath.sanitize("/sso/complete"));
        assertEquals("/workspaces", AuthReturnPath.sanitize("/workspaces"));
    }

    @Test
    void rejectsOverlongTargets() {
        String longPath = "/workitems/" + "a".repeat(AuthReturnPath.MAX_LENGTH);
        assertNull(AuthReturnPath.sanitize(longPath));
    }
}
