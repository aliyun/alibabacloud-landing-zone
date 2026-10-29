package com.aliyun.autowonder.taskdata;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.taskdata.dto.TaskDataPageVO;
import com.aliyun.autowonder.taskdata.dto.TaskDataRecordVO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskDataServiceTest {

    @Test
    void createReadAndPageWithinWorkspaceNamespace() {
        InMemoryDao dao = new InMemoryDao();
        TaskDataService service = new TaskDataService(dao);

        TaskDataRecordVO first = service.put(1, "links", "a:1", 10, Map.of("pr", 1), 0, 7);
        TaskDataRecordVO second = service.put(1, "links", "a:2", 10, Map.of("pr", 2), 0, 7);
        service.put(1, "other", "a:3", 10, Map.of("pr", 3), 0, 7);
        service.put(2, "links", "a:4", 10, Map.of("pr", 4), 0, 7);

        assertEquals(0, first.getVersion());
        assertEquals(0, second.getVersion());
        assertEquals(Map.of("pr", 2), service.get(1, "links", "a:2").getValue());

        TaskDataPageVO page = service.list(1, "links", null, 1);
        assertEquals(1, page.getItems().size());
        assertEquals("a:1", page.getItems().getFirst().getRecordKey());
        assertNotNull(page.getNextAfterId());

        TaskDataPageVO next = service.list(1, "links", page.getNextAfterId(), 10);
        assertEquals(1, next.getItems().size());
        assertEquals("a:2", next.getItems().getFirst().getRecordKey());
        assertNull(next.getNextAfterId());
    }

    @Test
    void updateRequiresCurrentVersionAndOwner() {
        InMemoryDao dao = new InMemoryDao();
        TaskDataService service = new TaskDataService(dao);
        TaskDataRecordVO created = service.put(1, "links", "a:1", 10, Map.of("state", "ACTIVE"), 0, 7);

        TaskDataRecordVO updated = service.put(1, "links", "a:1", 10,
                Map.of("state", "REPLACED"), created.getVersion(), 8);
        assertEquals(1, updated.getVersion());

        TaskDataConflictException stale = assertThrows(TaskDataConflictException.class,
                () -> service.put(1, "links", "a:1", 10, Map.of("state", "ACTIVE"), created.getVersion(), 8));
        assertEquals(1, stale.current().getVersion());

        assertThrows(SecurityException.class,
                () -> service.put(1, "links", "a:1", 11, Map.of("state", "ACTIVE"), 1, 8));
        assertThrows(TaskDataConflictException.class,
                () -> service.put(1, "links", "a:1", 10, Map.of("state", "ACTIVE"), null, 8));
    }

    @Test
    void equivalentJsonWithDifferentOrderingIsIdempotent() {
        InMemoryDao dao = new InMemoryDao();
        TaskDataService service = new TaskDataService(dao);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", 2);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("b", 2);
        reordered.put("a", 1);

        TaskDataRecordVO created = service.put(1, "links", "a:1", 10, first, 0, 7);
        TaskDataRecordVO replay = service.put(1, "links", "a:1", 10, reordered, 0, 8);

        assertEquals(created.getVersion(), replay.getVersion());
        assertEquals(first, replay.getValue());
    }

    @Test
    void duplicateCreateAndInvalidInputRemainVisible() {
        InMemoryDao dao = new InMemoryDao();
        TaskDataService service = new TaskDataService(dao);
        TaskDataRecordVO created = service.put(1, "links", "a:1", 10, Map.of("pr", 1), 0, 7);

        TaskDataRecordVO replay = service.put(1, "links", "a:1", 10, Map.of("pr", 1), 0, 8);
        assertEquals(created.getVersion(), replay.getVersion());
        assertEquals(Map.of("pr", 1), replay.getValue());
        assertThrows(TaskDataConflictException.class,
                () -> service.put(1, "links", "a:1", 10, Map.of("pr", 2), 99, 8));
        assertThrows(IllegalArgumentException.class,
                () -> service.put(1, "bad namespace", "a:1", 10, Map.of("pr", 1), 0, 7));
        assertThrows(IllegalArgumentException.class,
                () -> service.put(1, "links", "bad key", 10, Map.of("pr", 1), 0, 7));
        assertThrows(IllegalArgumentException.class,
                () -> service.put(1, "links", "a:2", 10, "x".repeat(TaskDataService.MAX_VALUE_BYTES + 1), 0, 7));
    }

    private static final class InMemoryDao implements TaskDataRecordDao {
        private final Map<String, TaskDataRecordDO> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public void insert(TaskDataRecordDO record) {
            String key = record.getWorkspaceId() + "\n" + record.getNamespace() + "\n" + record.getRecordKey();
            if (rows.containsKey(key)) {
                throw new DuplicateKeyException("duplicate");
            }
            record.setId(nextId++);
            record.setVersion(0);
            record.setValueJson(normalize(record.getValueJson()));
            rows.put(key, record);
        }

        @Override
        public TaskDataRecordDO findByKey(Long workspaceId, String namespace, String recordKey) {
            return rows.get(workspaceId + "\n" + namespace + "\n" + recordKey);
        }

        @Override
        public List<TaskDataRecordDO> list(Long workspaceId, String namespace, Long afterId, int limit) {
            List<TaskDataRecordDO> result = new ArrayList<>();
            for (TaskDataRecordDO row : rows.values()) {
                if (!workspaceId.equals(row.getWorkspaceId()) || !namespace.equals(row.getNamespace())) {
                    continue;
                }
                if (afterId != null && row.getId() <= afterId) {
                    continue;
                }
                result.add(row);
            }
            result.sort(Comparator.comparing(TaskDataRecordDO::getId));
            return result.size() <= limit ? result : result.subList(0, limit);
        }

        @Override
        public int update(Long workspaceId, String namespace, String recordKey, Long ownerWorkitemId,
                          String valueJson, Integer expectedVersion, Long modifierId) {
            TaskDataRecordDO row = findByKey(workspaceId, namespace, recordKey);
            if (row == null || !expectedVersion.equals(row.getVersion())) {
                return 0;
            }
            row.setOwnerWorkitemId(ownerWorkitemId);
            row.setValueJson(normalize(valueJson));
            row.setModifierId(modifierId);
            row.setVersion(row.getVersion() + 1);
            return 1;
        }

        private static String normalize(String json) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) JSON.parse(json);
            List<String> keys = new ArrayList<>(parsed.keySet());
            Collections.reverse(keys);
            Map<String, Object> reordered = new LinkedHashMap<>();
            for (String key : keys) {
                reordered.put(key, parsed.get(key));
            }
            return JSON.toJSONString(reordered);
        }
    }
}
