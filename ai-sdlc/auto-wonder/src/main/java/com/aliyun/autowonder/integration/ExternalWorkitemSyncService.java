package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExternalWorkitemSyncService {

    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalProjectBindingDao bindingDao;
    private final AoneInboundSyncService aoneInboundSyncService;

    public ExternalWorkitemSyncService(ExternalWorkitemLinkDao linkDao, ExternalProjectBindingDao bindingDao,
                                       AoneInboundSyncService aoneInboundSyncService) {
        this.linkDao = linkDao;
        this.bindingDao = bindingDao;
        this.aoneInboundSyncService = aoneInboundSyncService;
    }

    public AoneSyncResult syncLocalWorkitem(long workitemId, long tenantId, long userId) {
        ExternalWorkitemLinkDO link = linkDao.findByWorkitem(tenantId, AoneIntegrationService.PROVIDER, workitemId);
        if (link == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "当前工单未关联 Aone 工单");
        }
        ExternalProjectBindingDO binding = bindingDao.findById(link.getBindingId());
        if (binding == null || !Long.valueOf(tenantId).equals(binding.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "Aone 托管配置不存在");
        }
        return aoneInboundSyncService.refreshIssueIds(binding, List.of(link.getExternalWorkitemId()), userId);
    }
}
