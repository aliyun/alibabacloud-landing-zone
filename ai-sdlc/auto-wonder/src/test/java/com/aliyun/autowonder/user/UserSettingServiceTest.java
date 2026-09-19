package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.dto.UserSettingVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSettingServiceTest {

    private static final long USER_ID = 9L;
    private static final String SEND_MODE_KEY = "clarification_send_mode";

    private UserSettingDao dao;
    private UserSettingService service;

    @BeforeEach
    void setUp() {
        dao = mock(UserSettingDao.class);
        service = new UserSettingService(dao);
    }

    @Test
    void getReturnsStoredValue() {
        when(dao.findLive(USER_ID, SEND_MODE_KEY)).thenReturn(row(1L, "\"shift-enter\"", 0));

        UserSettingVO vo = service.get(USER_ID, SEND_MODE_KEY);

        assertEquals(SEND_MODE_KEY, vo.getKey());
        assertEquals("\"shift-enter\"", vo.getValueJson());
    }

    @Test
    void getReturnsNullValueRatherThanNullVoWhenNeverSet() {
        when(dao.findLive(USER_ID, SEND_MODE_KEY)).thenReturn(null);

        UserSettingVO vo = service.get(USER_ID, SEND_MODE_KEY);

        assertEquals(SEND_MODE_KEY, vo.getKey(), "key must survive so the caller can tell this apart from a failed call");
        assertNull(vo.getValueJson());
    }

    @Test
    void getIgnoresSoftDeletedRowSoDeleteReallyResetsToDefault() {
        // 读取必须走带 is_deleted = 0 过滤的语句，否则「恢复默认」之后旧偏好还会被读回来。
        when(dao.findLive(USER_ID, SEND_MODE_KEY)).thenReturn(null);

        assertNull(service.get(USER_ID, SEND_MODE_KEY).getValueJson());
        verify(dao, never()).findByUk(anyLong(), anyString());
    }

    @Test
    void listByUserMapsEveryLiveRow() {
        when(dao.listByUser(USER_ID)).thenReturn(List.of(
                row(1L, "\"shift-enter\"", 0),
                namedRow(2L, "clarification_input_rows", "6", 0)));

        List<UserSettingVO> result = service.listByUser(USER_ID);

        assertEquals(2, result.size());
        assertEquals(SEND_MODE_KEY, result.get(0).getKey());
        assertEquals("\"shift-enter\"", result.get(0).getValueJson());
        assertEquals("clarification_input_rows", result.get(1).getKey());
        assertEquals("6", result.get(1).getValueJson());
    }

    @Test
    void listByUserReturnsEmptyListWhenUserHasNoPreference() {
        when(dao.listByUser(USER_ID)).thenReturn(List.of());

        assertEquals(List.of(), service.listByUser(USER_ID));
    }

    @Test
    void upsertInsertsWhenKeyIsNew() {
        when(dao.findByUk(USER_ID, SEND_MODE_KEY)).thenReturn(null);

        UserSettingVO vo = service.upsert(USER_ID, SEND_MODE_KEY, "\"shift-enter\"");

        verify(dao).insert(argThat(s ->
                s.getUserId() == USER_ID
                        && SEND_MODE_KEY.equals(s.getSettingKey())
                        && "\"shift-enter\"".equals(s.getValueJson())
                        && s.getCreatorId() == USER_ID
                        && s.getModifierId() == USER_ID));
        verify(dao, never()).update(anyLong(), anyLong(), any(), anyLong());
        assertEquals("\"shift-enter\"", vo.getValueJson());
    }

    @Test
    void upsertUpdatesInPlaceWhenKeyAlreadyExists() {
        when(dao.findByUk(USER_ID, SEND_MODE_KEY)).thenReturn(row(7L, "\"shift-enter\"", 0));

        service.upsert(USER_ID, SEND_MODE_KEY, "\"enter\"");

        verify(dao).update(7L, USER_ID, "\"enter\"", USER_ID);
        verify(dao, never()).insert(any());
    }

    @Test
    void upsertRevivesSoftDeletedRowInsteadOfInsertingADuplicateKey() {
        // uk_user_setting(user_id, setting_key) 不含 is_deleted，软删行仍占着唯一键槽位。
        // 若这里走 insert，用户删除偏好后再改一次发送方式就会撞唯一键报 500。
        when(dao.findByUk(USER_ID, SEND_MODE_KEY)).thenReturn(row(7L, "\"enter\"", 1));

        service.upsert(USER_ID, SEND_MODE_KEY, "\"shift-enter\"");

        verify(dao).update(7L, USER_ID, "\"shift-enter\"", USER_ID);
        verify(dao, never()).insert(any());
    }

    @Test
    void upsertAcceptsNullValueToClearASetting() {
        when(dao.findByUk(USER_ID, SEND_MODE_KEY)).thenReturn(row(7L, "\"enter\"", 0));

        UserSettingVO vo = service.upsert(USER_ID, SEND_MODE_KEY, null);

        verify(dao).update(7L, USER_ID, null, USER_ID);
        assertNull(vo.getValueJson());
    }

    @Test
    void upsertAcceptsObjectJsonSoFuturePreferencesAreNotLimitedToScalars() {
        when(dao.findByUk(USER_ID, "clarification_layout")).thenReturn(null);

        assertDoesNotThrow(() -> service.upsert(USER_ID, "clarification_layout", "{\"rows\":6,\"pinned\":true}"));

        verify(dao).insert(argThat(s -> "{\"rows\":6,\"pinned\":true}".equals(s.getValueJson())));
    }

    @Test
    void upsertRejectsMalformedJsonBeforeItReachesTheJsonColumn() {
        BizException ex = assertThrows(BizException.class,
                () -> service.upsert(USER_ID, SEND_MODE_KEY, "shift-enter"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(dao, never()).insert(any());
        verify(dao, never()).update(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void upsertRejectsBlankValue() {
        BizException ex = assertThrows(BizException.class,
                () -> service.upsert(USER_ID, SEND_MODE_KEY, "   "));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(dao, never()).insert(any());
    }

    @Test
    void upsertRejectsOversizedValue() {
        String oversized = "\"" + "x".repeat(UserSettingService.MAX_VALUE_JSON_LENGTH) + "\"";

        BizException ex = assertThrows(BizException.class,
                () -> service.upsert(USER_ID, SEND_MODE_KEY, oversized));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(dao, never()).insert(any());
    }

    @Test
    void upsertAcceptsValueAtTheSizeLimit() {
        String atLimit = "\"" + "x".repeat(UserSettingService.MAX_VALUE_JSON_LENGTH - 2) + "\"";
        assertEquals(UserSettingService.MAX_VALUE_JSON_LENGTH, atLimit.length());
        when(dao.findByUk(USER_ID, SEND_MODE_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> service.upsert(USER_ID, SEND_MODE_KEY, atLimit));
    }

    @Test
    void blankKeyIsRejectedOnEveryEntryPoint() {
        assertThrows(BizException.class, () -> service.get(USER_ID, "  "));
        assertThrows(BizException.class, () -> service.upsert(USER_ID, "", "\"enter\""));
        assertThrows(BizException.class, () -> service.delete(USER_ID, null));
        verify(dao, never()).findLive(anyLong(), anyString());
        verify(dao, never()).insert(any());
        verify(dao, never()).softDelete(anyLong(), anyLong());
    }

    @Test
    void keyLongerThanTheColumnIsRejected() {
        String tooLong = "k".repeat(UserSettingService.MAX_KEY_LENGTH + 1);

        BizException ex = assertThrows(BizException.class, () -> service.get(USER_ID, tooLong));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void keyAtTheColumnLimitIsAccepted() {
        String atLimit = "k".repeat(UserSettingService.MAX_KEY_LENGTH);
        when(dao.findLive(USER_ID, atLimit)).thenReturn(null);

        assertDoesNotThrow(() -> service.get(USER_ID, atLimit));
    }

    @Test
    void deleteSoftDeletesTheCallersOwnRow() {
        when(dao.findByUk(USER_ID, SEND_MODE_KEY)).thenReturn(row(7L, "\"enter\"", 0));

        service.delete(USER_ID, SEND_MODE_KEY);

        verify(dao).softDelete(7L, USER_ID);
    }

    @Test
    void deleteIsIdempotentWhenNothingWasEverStored() {
        when(dao.findByUk(USER_ID, SEND_MODE_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> service.delete(USER_ID, SEND_MODE_KEY));

        verify(dao, never()).softDelete(anyLong(), anyLong());
    }

    @Test
    void everyStatementIsScopedByTheUserIdPassedInSoNobodyCanReachAnotherUsersPreference() {
        when(dao.findByUk(USER_ID, SEND_MODE_KEY)).thenReturn(row(7L, "\"enter\"", 0));

        service.get(USER_ID, SEND_MODE_KEY);
        service.listByUser(USER_ID);
        service.upsert(USER_ID, SEND_MODE_KEY, "\"shift-enter\"");
        service.delete(USER_ID, SEND_MODE_KEY);

        verify(dao).findLive(USER_ID, SEND_MODE_KEY);
        verify(dao).listByUser(USER_ID);
        verify(dao).update(7L, USER_ID, "\"shift-enter\"", USER_ID);
        verify(dao).softDelete(7L, USER_ID);
    }

    private UserSettingDO row(Long id, String valueJson, int isDeleted) {
        return namedRow(id, SEND_MODE_KEY, valueJson, isDeleted);
    }

    private UserSettingDO namedRow(Long id, String key, String valueJson, int isDeleted) {
        UserSettingDO row = new UserSettingDO();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setSettingKey(key);
        row.setValueJson(valueJson);
        row.setIsDeleted(isDeleted);
        return row;
    }
}
