package com.aliyun.autowonder.feature;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.feature.dto.FeatureSwitchVO;
import com.aliyun.autowonder.feature.dto.UpdateFeatureSwitchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformFeatureSwitchServiceTest {

    private PlatformFeatureSwitchDao dao;
    private PlatformFeatureSwitchService service;

    @BeforeEach
    void setUp() {
        dao = mock(PlatformFeatureSwitchDao.class);
        service = new PlatformFeatureSwitchService(dao);
    }

    @Test
    void missingRowMeansDisabledWithZeroVersion() {
        when(dao.findByKey(anyString())).thenReturn(null);

        FeatureSwitchVO vo = service.get();

        assertFalse(vo.isDuplicateWorkitemIntentEnabled());
        assertEquals(0, vo.getVersion());
        assertFalse(service.duplicateIntentEnabled());
    }

    @Test
    void storedRowDrivesValueAndVersion() {
        PlatformFeatureSwitchDO row = new PlatformFeatureSwitchDO();
        row.setEnabled(1);
        row.setVersion(4);
        when(dao.findByKey(anyString())).thenReturn(row);

        assertTrue(service.get().isDuplicateWorkitemIntentEnabled());
        assertEquals(4, service.get().getVersion());
    }

    @Test
    void missingVersionFallsBackToZero() {
        PlatformFeatureSwitchDO row = new PlatformFeatureSwitchDO();
        row.setEnabled(0);
        row.setVersion(null);
        when(dao.findByKey(anyString())).thenReturn(row);

        assertEquals(0, service.get().getVersion());
        assertFalse(service.get().isDuplicateWorkitemIntentEnabled());
    }

    @Test
    void updateWithoutExpectedVersionBehavesLikeFirstWrite() {
        when(dao.findByKey(anyString())).thenReturn(null).thenReturn(stored(1, 1));
        when(dao.updateEnabled(anyString(), anyInt(), anyInt(), anyLong())).thenReturn(0);

        UpdateFeatureSwitchRequest request = new UpdateFeatureSwitchRequest();
        request.setDuplicateWorkitemIntentEnabled(true);
        request.setExpectedVersion(null);

        assertTrue(service.update(7L, request).isDuplicateWorkitemIntentEnabled());
        verify(dao).updateEnabled(anyString(), anyInt(), eq(0), anyLong());
    }

    @Test
    void concurrentFirstWriteIsReportedAsVersionConflict() {
        when(dao.findByKey(anyString())).thenReturn(null);
        when(dao.updateEnabled(anyString(), anyInt(), anyInt(), anyLong())).thenReturn(0);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("dup"))
                .when(dao).insert(any(PlatformFeatureSwitchDO.class));

        UpdateFeatureSwitchRequest request = new UpdateFeatureSwitchRequest();
        request.setDuplicateWorkitemIntentEnabled(true);

        BizException error = assertThrows(BizException.class, () -> service.update(7L, request));
        assertEquals(ErrorCode.FEATURE_SWITCH_VERSION_CONFLICT.getCode(), error.getCode());
    }

    @Test
    void firstWriteInsertsWhenRowIsAbsent() {
        when(dao.findByKey(anyString())).thenReturn(null).thenReturn(stored(1, 1));
        when(dao.updateEnabled(anyString(), anyInt(), anyInt(), anyLong())).thenReturn(0);

        UpdateFeatureSwitchRequest request = new UpdateFeatureSwitchRequest();
        request.setDuplicateWorkitemIntentEnabled(true);
        request.setExpectedVersion(0);

        FeatureSwitchVO vo = service.update(7L, request);

        assertTrue(vo.isDuplicateWorkitemIntentEnabled());
        verify(dao).insert(any(PlatformFeatureSwitchDO.class));
    }

    @Test
    void staleVersionIsRejected() {
        when(dao.findByKey(anyString())).thenReturn(stored(0, 3));
        when(dao.updateEnabled(anyString(), anyInt(), anyInt(), anyLong())).thenReturn(0);

        UpdateFeatureSwitchRequest request = new UpdateFeatureSwitchRequest();
        request.setDuplicateWorkitemIntentEnabled(true);
        request.setExpectedVersion(2);

        BizException error = assertThrows(BizException.class, () -> service.update(7L, request));
        assertEquals(ErrorCode.FEATURE_SWITCH_VERSION_CONFLICT.getCode(), error.getCode());
        verify(dao, never()).insert(any(PlatformFeatureSwitchDO.class));
    }

    private static PlatformFeatureSwitchDO stored(int enabled, int version) {
        PlatformFeatureSwitchDO row = new PlatformFeatureSwitchDO();
        row.setEnabled(enabled);
        row.setVersion(version);
        return row;
    }

    @Test
    void registrationReviewDefaultsOffAndWorkspaceCreationDefaultsOn() {
        when(dao.findByKey(anyString())).thenReturn(null);

        FeatureSwitchVO vo = service.get();

        assertFalse(vo.isUserRegistrationReviewEnabled());
        assertEquals(0, vo.getUserRegistrationReviewVersion());
        assertTrue(vo.isWorkspaceCreationDefaultAllowed());
        assertEquals(0, vo.getWorkspaceCreationDefaultAllowedVersion());
        assertFalse(service.registrationReviewEnabled());
        assertTrue(service.workspaceCreationDefaultAllowed());
    }

    @Test
    void eachNewSwitchKeepsItsOwnVersion() {
        when(dao.findByKey(PlatformFeatureSwitchService.USER_REGISTRATION_REVIEW_KEY))
                .thenReturn(stored(1, 4));
        when(dao.findByKey(PlatformFeatureSwitchService.WORKSPACE_CREATION_DEFAULT_ALLOWED_KEY))
                .thenReturn(stored(0, 7));

        FeatureSwitchVO vo = service.get();

        assertTrue(vo.isUserRegistrationReviewEnabled());
        assertEquals(4, vo.getUserRegistrationReviewVersion());
        assertFalse(vo.isWorkspaceCreationDefaultAllowed());
        assertEquals(7, vo.getWorkspaceCreationDefaultAllowedVersion());
    }

    @Test
    void updatingOneNewSwitchNeverTouchesTheOtherSwitches() {
        when(dao.findByKey(anyString())).thenReturn(stored(0, 2));
        when(dao.updateEnabled(eq(PlatformFeatureSwitchService.USER_REGISTRATION_REVIEW_KEY),
                eq(1), eq(2), eq(7L))).thenReturn(1);

        UpdateFeatureSwitchRequest request = new UpdateFeatureSwitchRequest();
        request.setUserRegistrationReviewEnabled(true);
        request.setUserRegistrationReviewVersion(2);
        service.update(7L, request);

        verify(dao, never()).updateEnabled(eq(PlatformFeatureSwitchService.DUPLICATE_INTENT_KEY),
                anyInt(), anyInt(), anyLong());
        verify(dao, never()).updateEnabled(eq(PlatformFeatureSwitchService.WORKSPACE_CREATION_DEFAULT_ALLOWED_KEY),
                anyInt(), anyInt(), anyLong());
    }
}
