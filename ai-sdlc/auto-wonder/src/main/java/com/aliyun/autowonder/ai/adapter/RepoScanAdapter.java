package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.repo.RepoConclusionDO;
import com.aliyun.autowonder.repo.RepoConclusionDao;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.repo.RepoDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RepoScanAdapter implements SceneAdapter {

    private static final Logger log = LoggerFactory.getLogger(RepoScanAdapter.class);

    private final RepoDao repoDao;
    private final RepoConclusionDao conclusionDao;

    public RepoScanAdapter(RepoDao repoDao, RepoConclusionDao conclusionDao) {
        this.repoDao = repoDao;
        this.conclusionDao = conclusionDao;
    }

    @Override
    public String scene() {
        return AiConstants.Scene.REPO_SCAN;
    }

    @Override
    public String buildSystemPrompt(AiSessionDO session) {
        return "你是代码仓库分析专家。你的目标是扫描工作区代码并给出准确的仓库分析结论。\n\n" +
                "工作方式：\n" +
                "1. 先扫描仓库代码，分析其作用、关键业务、核心模块、上下游依赖。\n" +
                "2. 用简洁的总结向用户展示你的分析结论，询问是否有需要补充或修正的地方。\n" +
                "3. 用户确认后，输出最终JSON。\n\n" +
                "最终输出的JSON格式：\n" +
                "{\"purpose\":\"...\", \"keyBusiness\":\"...\", " +
                "\"upstreams\":\"...\", \"downstreams\":\"...\", \"summaryMd\":\"...\"}\n\n" +
                "在用户确认之前不要输出JSON。";
    }

    @Override
    public String buildUserPrompt(AiSessionDO session, String userInput) {
        log.info("repo scan buildUserPrompt sessionId={} bizRefId={} userInput={}",
                session.getId(), session.getBizRefId(), userInput);
        if (userInput != null && !userInput.isBlank()) {
            return "请扫描本地仓库: " + userInput + "。\n" +
                    "请重点分析仓库的作用、关键业务、核心模块、上下游依赖和可能对其他系统产生影响的接口或任务。" +
                    "分析完成后先展示结论摘要，等待用户确认。";
        }
        return "请扫描并分析当前仓库。";
    }

    @Override
    public String validateResult(String resultJson) {
        log.info("repo scan validateResult jsonLen={}", resultJson != null ? resultJson.length() : 0);
        try {
            JSONObject obj = JSON.parseObject(resultJson);
            if (obj == null || obj.getString("purpose") == null || obj.getString("purpose").isBlank()) {
                log.warn("repo scan validateResult failed: missing or blank purpose field");
                return "missing or blank purpose field";
            }
            if (obj.getString("summaryMd") == null) {
                log.warn("repo scan validateResult failed: missing summaryMd field");
                return "missing summaryMd field";
            }
            log.info("repo scan validateResult passed");
            return null;
        } catch (Exception e) {
            log.warn("repo scan validateResult failed: invalid JSON", e);
            return "invalid JSON: " + e.getMessage();
        }
    }

    @Override
    public void persistConfirmedResult(AiSessionDO session, String resultJson) {
        log.info("repo scan persistConfirmedResult sessionId={} bizRefId={} jsonLen={}",
                session.getId(), session.getBizRefId(), resultJson != null ? resultJson.length() : 0);
        Long repoId = session.getBizRefId();
        RepoDO repo = repoDao.findById(repoId);
        if (repo == null) {
            log.warn("repo not found for scan confirm repoId={}", repoId);
            return;
        }
        JSONObject obj = JSON.parseObject(resultJson);
        RepoConclusionDO existing = conclusionDao.findByRepoId(repoId);
        if (existing == null) {
            RepoConclusionDO c = new RepoConclusionDO();
            c.setTenantId(session.getTenantId());
            c.setRepoId(repoId);
            c.setPurpose(obj.getString("purpose"));
            c.setKeyBusiness(obj.getString("keyBusiness"));
            c.setUpstreams(obj.getString("upstreams"));
            c.setDownstreams(obj.getString("downstreams"));
            c.setSummaryMd(obj.getString("summaryMd"));
            c.setAiSessionId(session.getId());
            c.setVersion(0);
            conclusionDao.insert(c);
        } else {
            conclusionDao.update(existing.getId(), session.getTenantId(),
                    obj.getString("purpose"), obj.getString("keyBusiness"),
                    obj.getString("upstreams"), obj.getString("downstreams"),
                    obj.getString("summaryMd"), existing.getVersion(), null);
        }
        repoDao.updateScanStatus(repoId, session.getTenantId(), "CONCLUDED", repo.getVersion(), null);
        log.info("repo scan concluded repoId={} sessionId={}", repoId, session.getId());
    }
}
