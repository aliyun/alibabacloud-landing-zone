package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.clarification.ClarificationDO;
import com.aliyun.autowonder.clarification.ClarificationDao;
import com.aliyun.autowonder.repo.RepoConclusionDO;
import com.aliyun.autowonder.repo.RepoConclusionDao;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ClarificationAdapter implements SceneAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClarificationAdapter.class);

    private final ClarificationDao clarificationDao;
    private final WorkitemDao workitemDao;
    private final WorkitemCommentDao workitemCommentDao;
    private final RepoDao repoDao;
    private final RepoConclusionDao repoConclusionDao;

    public ClarificationAdapter(ClarificationDao clarificationDao,
            WorkitemDao workitemDao,
            WorkitemCommentDao workitemCommentDao,
            RepoDao repoDao,
            RepoConclusionDao repoConclusionDao) {
        this.clarificationDao = clarificationDao;
        this.workitemDao = workitemDao;
        this.workitemCommentDao = workitemCommentDao;
        this.repoDao = repoDao;
        this.repoConclusionDao = repoConclusionDao;
    }

    @Override
    public String scene() {
        return AiConstants.Scene.CLARIFICATION;
    }

    @Override
    public String buildSystemPrompt(AiSessionDO session) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是需求澄清专家。你的目标是通过对话帮助用户把模糊的需求变成清晰、完整、无歧义的描述。\n\n");
        sb.append("工作方式：\n");
        sb.append("1. 先阅读用户的需求描述，找出模糊点、歧义和缺失信息。\n");
        sb.append("2. 每轮针对最关键的2-3个问题追问，不要一次列出所有问题。\n");
        sb.append("3. 逐步建立完整理解，用简洁的总结回顾当前澄清结论，确认是否还有遗漏。\n");
        sb.append("4. 用户明确确认后，输出最终JSON。\n\n");
        sb.append("最终输出的JSON格式：{\"clarificationMd\":\"...(最终澄清材料Markdown)\"}\n\n");
        sb.append("在用户确认之前不要输出JSON。");

        appendWorkitemContext(sb, session);
        appendRepoContext(sb, session.getTenantId());

        String prompt = sb.toString();
        log.info("clarification systemPrompt built sessionId={} bizRefId={} tenantId={} promptLen={}\n{}",
                session.getId(), session.getBizRefId(), session.getTenantId(), prompt.length(), prompt);
        return prompt;
    }

    private void appendWorkitemContext(StringBuilder sb, AiSessionDO session) {
        if (!"WORKITEM".equals(session.getBizRefType()) || session.getBizRefId() == null) {
            return;
        }
        try {
            WorkitemDO workitem = workitemDao.findById(session.getBizRefId());
            if (workitem == null) {
                return;
            }
            sb.append("\n\n--- 需求信息 ---\n");
            sb.append("标题: ").append(workitem.getTitle() != null ? workitem.getTitle() : "").append("\n");
            if (workitem.getContentMd() != null && !workitem.getContentMd().isBlank()) {
                sb.append("内容:\n").append(workitem.getContentMd()).append("\n");
            }

            List<WorkitemCommentDO> comments = workitemCommentDao.listByWorkitem(workitem.getId());
            if (comments != null && !comments.isEmpty()) {
                sb.append("\n评论:\n");
                for (WorkitemCommentDO c : comments) {
                    String author = c.getAuthorRef() != null ? String.valueOf(c.getAuthorRef()) : "unknown";
                    sb.append("- ").append(author).append(": ").append(c.getContentMd()).append("\n");
                }
            }
            log.info("clarification context appended workitemId={} commentsCount={}",
                    workitem.getId(), comments != null ? comments.size() : 0);
        } catch (Exception e) {
            log.warn("failed to append workitem context bizRefId={}", session.getBizRefId(), e);
        }
    }

    private void appendRepoContext(StringBuilder sb, Long tenantId) {
        if (tenantId == null) {
            return;
        }
        try {
            List<RepoDO> repos = repoDao.list(tenantId, 0, 100);
            if (repos == null || repos.isEmpty()) {
                return;
            }
            sb.append("\n\n--- 项目仓库 ---\n");
            for (RepoDO repo : repos) {
                sb.append("仓库: ").append(repo.getName());
                if (repo.getUrl() != null) {
                    sb.append(" (").append(repo.getUrl()).append(")");
                }
                sb.append("\n");
                if (repo.getDescription() != null && !repo.getDescription().isBlank()) {
                    sb.append("  描述: ").append(repo.getDescription()).append("\n");
                }
                RepoConclusionDO conclusion = repoConclusionDao.findByRepoId(repo.getId());
                if (conclusion != null) {
                    if (conclusion.getPurpose() != null) {
                        sb.append("  简介: ").append(conclusion.getPurpose()).append("\n");
                    }
                    if (conclusion.getKeyBusiness() != null) {
                        sb.append("  关键业务: ").append(conclusion.getKeyBusiness()).append("\n");
                    }
                    if (conclusion.getUpstreams() != null) {
                        sb.append("  上游: ").append(conclusion.getUpstreams()).append("\n");
                    }
                    if (conclusion.getDownstreams() != null) {
                        sb.append("  下游: ").append(conclusion.getDownstreams()).append("\n");
                    }
                }
            }
            log.info("clarification context appended repoCount={}", repos.size());
        } catch (Exception e) {
            log.warn("failed to append repo context tenantId={}", tenantId, e);
        }
    }

    @Override
    public String buildUserPrompt(AiSessionDO session, String userInput) {
        if (userInput != null && !userInput.isBlank()) {
            return userInput;
        }
        return "请分析并澄清该需求。";
    }

    @Override
    public String validateResult(String resultJson) {
        try {
            JSONObject obj = JSON.parseObject(resultJson);
            if (obj == null || obj.getString("clarificationMd") == null
                    || obj.getString("clarificationMd").isBlank()) {
                return "missing clarificationMd field";
            }
            return null;
        } catch (Exception e) {
            return "invalid JSON: " + e.getMessage();
        }
    }

    @Override
    public void persistConfirmedResult(AiSessionDO session, String resultJson) {
        JSONObject obj = JSON.parseObject(resultJson);
        String md = obj.getString("clarificationMd");

        ClarificationDO existing = clarificationDao.findByWorkitem(session.getBizRefId());
        if (existing == null) {
            ClarificationDO c = new ClarificationDO();
            c.setTenantId(session.getTenantId());
            c.setWorkitemId(session.getBizRefId());
            c.setContentMd(md);
            clarificationDao.insert(c);
        } else {
            clarificationDao.update(existing.getId(), session.getTenantId(), md);
        }
    }
}
