package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchDiagnosticServiceTest {
    @Test void pagesRawUnicodeAndKeepsDiagnosticsOutOfOrdinaryArtifacts() {
        DispatchDao dispatches=mock(DispatchDao.class); ArtifactDao artifacts=mock(ArtifactDao.class); ObjectStorage storage=mock(ObjectStorage.class);
        DispatchDO dispatch=new DispatchDO();dispatch.setId(44L);dispatch.setTenantId(1L);when(dispatches.findById(44L)).thenReturn(dispatch);
        ArtifactDO file=new ArtifactDO();file.setId(8L);file.setName(DispatchDiagnosticService.PREFIX+"a".repeat(64)+".json");file.setSize(100L);file.setOssRef("bucket/diagnostic");
        when(artifacts.listByDispatch(1L,44L)).thenReturn(List.of(file,file));
        when(storage.get("bucket/diagnostic")).thenReturn("中文原始错误🙂broken-json".getBytes(StandardCharsets.UTF_8));
        var service=new DispatchDiagnosticService(dispatches,artifacts,storage);
        assertEquals(1,service.list(1,44).size());
        var first=service.read(1,44,8,0,7);var second=service.read(1,44,8,first.nextOffset(),16000);
        assertEquals("中文原始错误🙂broken-json",first.text()+second.text());
        assertNull(second.nextOffset());
        assertThrows(BizException.class,()->service.read(1,44,8,-1,10));
        assertThrows(BizException.class,()->service.read(1,44,8,0,16001));
        assertThrows(BizException.class,()->service.read(1,44,999,0,10));
        assertThrows(BizException.class,()->service.list(2,44));
        verify(artifacts,never()).listByDispatch(2L,44L);
        when(storage.presignGet("bucket/diagnostic",600)).thenReturn("https://download.invalid/signed");
        assertEquals("https://download.invalid/signed",service.download(1,44,8));
    }
    @Test void noSnapshotIsAValidOldRuntimeState() {
        DispatchDao dispatches=mock(DispatchDao.class);ArtifactDao artifacts=mock(ArtifactDao.class);ObjectStorage storage=mock(ObjectStorage.class);
        DispatchDO dispatch=new DispatchDO();dispatch.setTenantId(1L);when(dispatches.findById(44L)).thenReturn(dispatch);
        when(artifacts.listByDispatch(1L,44L)).thenReturn(List.of());
        assertTrue(new DispatchDiagnosticService(dispatches,artifacts,storage).list(1,44).isEmpty());
        verifyNoInteractions(storage);
    }
}
