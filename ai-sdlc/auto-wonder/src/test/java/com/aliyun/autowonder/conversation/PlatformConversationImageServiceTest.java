package com.aliyun.autowonder.conversation;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
class PlatformConversationImageServiceTest {
    @Test void imagesAreScopedToOwnerAndConversationAndResolveFreshRuntimeUrls() throws Exception {
        ObjectStorage storage=mock(ObjectStorage.class); Map<String,byte[]> objects=new HashMap<>();
        when(storage.put(anyString(),anyString(),any())).thenAnswer(i->{String ref=i.getArgument(0)+"/"+i.getArgument(1);byte[] data=i.getArgument(2);objects.put(ref,data);return new StoredObject(ref,"md5",data.length);});
        when(storage.get(anyString())).thenAnswer(i->objects.get(i.getArgument(0)));
        when(storage.presignGet(anyString(),eq(3600))).thenReturn("https://oss.example/signed-image");
        OssProperties properties=new OssProperties();properties.setArtifactBucket("artifacts");
        var service=new PlatformConversationImageService(storage,properties);
        byte[] png={(byte)137,80,78,71,13,10,26,10};
        var image=service.upload(1L,7L,100L,new MockMultipartFile("file","screen.png","image/png",png));
        assertEquals("image/png",image.contentType());
        assertThrows(BizException.class,()->service.resolve(1L,8L,100L,image.id()));
        assertThrows(BizException.class,()->service.resolve(1L,7L,101L,image.id()));
        assertThrows(BizException.class,()->service.resolve(2L,7L,100L,image.id()));
        assertThrows(BizException.class,()->service.resolve(1L,7L,100L,"../other"));
        String context=service.attach(null,1L,7L,100L,List.of(image.id()));
        assertFalse(context.contains("signed-image"));
        String prompt=service.runtimeContext(context,1L,7L,100L);
        assertTrue(prompt.contains("signed-image"));assertTrue(prompt.contains("image-reading tool"));
        assertThrows(BizException.class,()->service.attach(null,1L,7L,100L,List.of(image.id(),image.id())));
        assertThrows(BizException.class,()->service.upload(1L,7L,100L,new MockMultipartFile("file","bad.png","image/png","<html>".getBytes())));
    }
}
