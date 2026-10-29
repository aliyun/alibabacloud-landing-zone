package com.aliyun.autowonder.taskdata;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.taskdata.dto.TaskDataPageVO;
import com.aliyun.autowonder.taskdata.dto.TaskDataRecordVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class TaskDataService {

    static final int MAX_VALUE_BYTES = 64 * 1024;
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern RECORD_KEY = Pattern.compile("[A-Za-z0-9._:/-]{1,256}");

    private final TaskDataRecordDao recordDao;

    public TaskDataService(TaskDataRecordDao recordDao) {
        this.recordDao = recordDao;
    }

    public TaskDataRecordVO get(long workspaceId, String namespace, String recordKey) {
        validateNamespace(namespace);
        validateKey(recordKey);
        TaskDataRecordDO record = recordDao.findByKey(workspaceId, namespace, recordKey);
        if (record == null) {
            throw new TaskDataNotFoundException();
        }
        return toVO(record);
    }

    public TaskDataPageVO list(long workspaceId, String namespace, Long afterId, int limit) {
        validateNamespace(namespace);
        int size = Math.min(Math.max(limit, 1), 100);
        if (afterId != null && afterId < 0) {
            throw new IllegalArgumentException("afterId must not be negative");
        }
        List<TaskDataRecordDO> rows = recordDao.list(workspaceId, namespace, afterId, size + 1);
        TaskDataPageVO page = new TaskDataPageVO();
        int count = Math.min(rows.size(), size);
        for (int i = 0; i < count; i++) {
            page.getItems().add(toVO(rows.get(i)));
        }
        if (rows.size() > size) {
            page.setNextAfterId(rows.get(count - 1).getId());
        }
        return page;
    }

    @Transactional
    public TaskDataRecordVO put(long workspaceId, String namespace, String recordKey,
                                long ownerWorkitemId, Object value, Integer expectedVersion,
                                long actorId) {
        validateNamespace(namespace);
        validateKey(recordKey);
        if (ownerWorkitemId <= 0) {
            throw new IllegalArgumentException("ownerWorkitemId must be positive");
        }
        if (expectedVersion != null && expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        String valueJson = JSON.toJSONString(value);
        if (valueJson == null || valueJson.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("value must be valid JSON no larger than 64KB");
        }
        try {
            JSON.parse(valueJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("value must be valid JSON");
        }

        TaskDataRecordDO existing = recordDao.findByKey(workspaceId, namespace, recordKey);
        if (existing == null) {
            if (expectedVersion != null && expectedVersion != 0) {
                throw new TaskDataConflictException("record does not exist", null);
            }
            TaskDataRecordDO record = new TaskDataRecordDO();
            record.setWorkspaceId(workspaceId);
            record.setNamespace(namespace);
            record.setRecordKey(recordKey);
            record.setOwnerWorkitemId(ownerWorkitemId);
            record.setValueJson(valueJson);
            record.setCreatorId(actorId);
            try {
                recordDao.insert(record);
            } catch (DuplicateKeyException race) {
                TaskDataRecordDO current = recordDao.findByKey(workspaceId, namespace, recordKey);
                throw new TaskDataConflictException("record was created concurrently", current);
            }
            return toVO(recordDao.findByKey(workspaceId, namespace, recordKey));
        }
        if (!Long.valueOf(ownerWorkitemId).equals(existing.getOwnerWorkitemId())) {
            throw new SecurityException("record belongs to another workitem");
        }
        // MySQL's JSON column normalizes formatting and key order; compare
        // parsed values so a replay is idempotent regardless of encoding.
        if (Objects.equals(JSON.parse(existing.getValueJson()), JSON.parse(valueJson))) {
            return toVO(existing);
        }
        if (expectedVersion == null) {
            throw new TaskDataConflictException("expectedVersion is required for an existing record", existing);
        }
        if (!expectedVersion.equals(existing.getVersion())) {
            throw new TaskDataConflictException("record version changed", existing);
        }
        int rows = recordDao.update(workspaceId, namespace, recordKey, ownerWorkitemId,
                valueJson, expectedVersion, actorId);
        if (rows == 0) {
            throw new TaskDataConflictException("record was updated concurrently",
                    recordDao.findByKey(workspaceId, namespace, recordKey));
        }
        return toVO(recordDao.findByKey(workspaceId, namespace, recordKey));
    }

    private static void validateNamespace(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("namespace is invalid");
        }
    }

    private static void validateKey(String recordKey) {
        if (recordKey == null || !RECORD_KEY.matcher(recordKey).matches()) {
            throw new IllegalArgumentException("recordKey is invalid");
        }
    }

    private static TaskDataRecordVO toVO(TaskDataRecordDO record) {
        if (record == null) {
            throw new TaskDataNotFoundException();
        }
        TaskDataRecordVO vo = new TaskDataRecordVO();
        vo.setId(record.getId());
        vo.setNamespace(record.getNamespace());
        vo.setRecordKey(record.getRecordKey());
        vo.setOwnerWorkitemId(record.getOwnerWorkitemId());
        vo.setValue(JSON.parse(record.getValueJson()));
        vo.setVersion(record.getVersion());
        vo.setGmtCreate(record.getGmtCreate());
        vo.setGmtModified(record.getGmtModified());
        return vo;
    }

    static class TaskDataNotFoundException extends RuntimeException {
    }
}
