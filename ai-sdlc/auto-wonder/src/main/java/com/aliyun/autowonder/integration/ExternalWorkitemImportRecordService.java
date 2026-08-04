package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.common.ExternalWorkitemImportRecordDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemImportRecordDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalWorkitemImportRecordService {

    private final ExternalWorkitemImportRecordDao recordDao;

    public ExternalWorkitemImportRecordService(ExternalWorkitemImportRecordDao recordDao) {
        this.recordDao = recordDao;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(ExternalWorkitemImportRecordDO record) {
        recordDao.insert(record);
    }
}
