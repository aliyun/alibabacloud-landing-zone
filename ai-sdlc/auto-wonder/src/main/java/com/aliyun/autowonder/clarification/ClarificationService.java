package com.aliyun.autowonder.clarification;

import com.aliyun.autowonder.clarification.dto.ClarificationVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClarificationService {

    private final ClarificationDao dao;

    public ClarificationService(ClarificationDao dao) {
        this.dao = dao;
    }

    public ClarificationVO get(long workitemId) {
        ClarificationDO c = dao.findByWorkitem(workitemId);
        if (c == null) {
            ClarificationVO vo = new ClarificationVO();
            vo.setWorkitemId(workitemId);
            vo.setContentMd(null);
            vo.setVersion(0);
            return vo;
        }
        return toVO(c);
    }

    @Transactional
    public ClarificationVO put(long workitemId, String contentMd, long tenantId, long userId) {
        ClarificationDO existing = dao.findByWorkitem(workitemId);
        if (existing == null) {
            ClarificationDO c = new ClarificationDO();
            c.setTenantId(tenantId);
            c.setWorkitemId(workitemId);
            c.setContentMd(contentMd);
            dao.insert(c);
        } else {
            dao.update(existing.getId(), tenantId, contentMd);
        }
        return toVO(dao.findByWorkitem(workitemId));
    }

    private ClarificationVO toVO(ClarificationDO c) {
        ClarificationVO vo = new ClarificationVO();
        vo.setWorkitemId(c.getWorkitemId());
        vo.setContentMd(c.getContentMd());
        vo.setVersion(c.getVersion());
        vo.setGmtModified(c.getGmtModified());
        return vo;
    }
}
