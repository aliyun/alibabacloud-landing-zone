package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledTaskDocumentControllerTest {

    @Test
    void exposesOnlyTheScheduledTaskDocumentRoutesWithRequiredAccess() throws Exception {
        RequestMapping root = ScheduledTaskDocumentController.class.getAnnotation(RequestMapping.class);
        RequireWorkspaceAccess classAccess = ScheduledTaskDocumentController.class.getAnnotation(RequireWorkspaceAccess.class);
        assertArrayEquals(new String[]{"/api/scheduled-tasks"}, root.value());
        assertEquals(WorkspaceAccessLevel.READ_ONLY, classAccess.value());

        Method list = ScheduledTaskDocumentController.class.getMethod("list", Long.class);
        Method upload = ScheduledTaskDocumentController.class.getMethod("upload", Long.class,
                org.springframework.web.multipart.MultipartFile[].class);
        Method delete = ScheduledTaskDocumentController.class.getMethod("delete", Long.class, Long.class);

        assertArrayEquals(new String[]{"/{id}/documents"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/{id}/documents"}, upload.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{id}/documents/{artifactId}"},
                delete.getAnnotation(DeleteMapping.class).value());
        assertEquals(WorkspaceAccessLevel.READ_WRITE, upload.getAnnotation(RequireWorkspaceAccess.class).value());
        assertEquals(WorkspaceAccessLevel.READ_WRITE, delete.getAnnotation(RequireWorkspaceAccess.class).value());
    }
}
