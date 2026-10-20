package com.aliyun.autowonder.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single status/display-name vocabulary shared by the admin list, the detail drawer and every
 * display path that resolves a possibly-deleted user. A wrong branch here leaks the release
 * placeholder ({@code #deleted-{id}}) into the UI or hides the deletion marker entirely.
 */
class UserAccountStatesTest {

    @Test
    void aNormalUserIsNeitherDeletedNorDeactivated() {
        UserDO user = user("alice");

        assertFalse(UserAccountStates.isDeleted(user));
        assertFalse(UserAccountStates.isLegacyDeactivated(user));
        assertEquals(UserAccountStates.NORMAL, UserAccountStates.statusOf(user));
    }

    @Test
    void theNullUserIsNeitherDeletedNorDeactivatedAndHasNoDisplayName() {
        assertFalse(UserAccountStates.isDeleted(null));
        assertFalse(UserAccountStates.isLegacyDeactivated(null));
        assertEquals(UserAccountStates.NORMAL, UserAccountStates.statusOf(null));
        assertNull(UserAccountStates.displayName(null));
    }

    @Test
    void aLogicallyDeletedRowIsRecognisedByTheIsDeletedFlagAlone() {
        UserDO user = user("#deleted-7");
        user.setIsDeleted(1);
        user.setDeletedUsername("alice");

        assertTrue(UserAccountStates.isDeleted(user));
        assertEquals(UserAccountStates.DELETED, UserAccountStates.statusOf(user));
        assertEquals("alice", UserAccountStates.displayName(user));
    }

    @Test
    void theAnonymisedLegacyAccountIsRecognisedOnlyWithBothMarkers() {
        UserDO user = user("ghost");
        user.setStatus(1);
        user.setPasswordHash(UserAccountStates.LEGACY_DEACTIVATED_HASH);

        assertTrue(UserAccountStates.isLegacyDeactivated(user));
        assertEquals(UserAccountStates.DEACTIVATED, UserAccountStates.statusOf(user));
        // A deactivated account is not deleted: no snapshot, no recycle-bin entry.
        assertFalse(UserAccountStates.isDeleted(user));
        assertEquals("ghost", UserAccountStates.displayName(user));
    }

    @Test
    void aDisabledAccountWithARealPasswordIsNotMistakenForTheLegacySentinel() {
        UserDO user = user("alice");
        user.setStatus(1);
        user.setPasswordHash("$2a$10$someRealHash");

        assertFalse(UserAccountStates.isLegacyDeactivated(user));
        assertEquals(UserAccountStates.NORMAL, UserAccountStates.statusOf(user));
    }

    @Test
    void anActiveAccountThatKeptTheSentinelHashIsNotMistakenEither() {
        UserDO user = user("alice");
        user.setStatus(0);
        user.setPasswordHash(UserAccountStates.LEGACY_DEACTIVATED_HASH);

        assertFalse(UserAccountStates.isLegacyDeactivated(user));
        assertEquals(UserAccountStates.NORMAL, UserAccountStates.statusOf(user));
    }

    @Test
    void theNicknameWinsOverTheUsernameWhenItIsPresent() {
        UserDO user = user("alice");
        user.setNickname("爱丽丝");

        assertEquals("爱丽丝", UserAccountStates.displayName(user));
    }

    @Test
    void aMissingOrBlankNicknameFallsBackToTheUsername() {
        UserDO user = user("alice");
        assertNull(user.getNickname());
        assertEquals("alice", UserAccountStates.displayName(user));

        user.setNickname("   ");
        assertEquals("alice", UserAccountStates.displayName(user));
    }

    @Test
    void aDeletedRowPrefersTheSnapshotEvenWhenANicknameSurvives() {
        UserDO user = user("#deleted-7");
        user.setNickname("爱丽丝");
        user.setIsDeleted(1);
        user.setDeletedUsername("alice");

        // §5: deleted users always render the pre-deletion name, never the nickname that the
        // preserved row still carries.
        assertEquals("alice", UserAccountStates.displayName(user));
    }

    private UserDO user(String username) {
        UserDO user = new UserDO();
        user.setId(7L);
        user.setUsername(username);
        user.setStatus(0);
        return user;
    }
}
