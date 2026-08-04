package com.aliyun.autowonder.ai.engine;

import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiMessageDO;
import com.aliyun.autowonder.ai.AiMessageDao;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.ai.AiSessionDao;
import com.aliyun.autowonder.ai.adapter.SceneAdapter;
import com.aliyun.autowonder.ai.adapter.SceneRegistry;
import com.aliyun.autowonder.aiusage.AiUsageService;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.websocket.NodeIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class AiWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(AiWorkerPool.class);
    private static final String API_MODE_SUFFIX =
            "\n\n重要：你在API模式下运行，不能使用AskUserQuestion等交互工具。直接用文字提问和回复。";

    private final AiSessionDao sessionDao;
    private final AiMessageDao messageDao;
    private final SceneRegistry sceneRegistry;
    private final CliExecutor cliExecutor;
    private final AiStreamPublisher streamPublisher;
    private final RedisManager redisManager;
    private final NodeIdentity nodeIdentity;
    private final AiUsageService aiUsageService;
    private final RepoDao repoDao;
    private final RepoWorkspacePreparer repoWorkspacePreparer;
    private final int poolSize;
    private final int queuePollSec;

    private ExecutorService threadPool;
    private volatile boolean running = true;

    public AiWorkerPool(AiSessionDao sessionDao, AiMessageDao messageDao,
            SceneRegistry sceneRegistry,
            CliExecutor cliExecutor, AiStreamPublisher streamPublisher,
            RedisManager redisManager, NodeIdentity nodeIdentity,
            AiUsageService aiUsageService,
            RepoDao repoDao,
            RepoWorkspacePreparer repoWorkspacePreparer,
            @Value("${autowonder.ai.worker-pool-size:3}") int poolSize,
            @Value("${autowonder.ai.queue-poll-sec:5}") int queuePollSec) {
        this.sessionDao = sessionDao;
        this.messageDao = messageDao;
        this.sceneRegistry = sceneRegistry;
        this.cliExecutor = cliExecutor;
        this.streamPublisher = streamPublisher;
        this.redisManager = redisManager;
        this.nodeIdentity = nodeIdentity;
        this.aiUsageService = aiUsageService;
        this.repoDao = repoDao;
        this.repoWorkspacePreparer = repoWorkspacePreparer;
        this.poolSize = poolSize;
        this.queuePollSec = queuePollSec;
    }

    @PostConstruct
    public void start() {
        threadPool = Executors.newFixedThreadPool(poolSize);
        for (int i = 0; i < poolSize; i++) {
            threadPool.submit(this::pollLoop);
        }
        log.info("AiWorkerPool started with {} workers", poolSize);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
    }

    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                String payload = redisManager.brpop("ai:queue:global", queuePollSec);
                if (payload == null) continue;

                long sessionId;
                try {
                    sessionId = Long.parseLong(payload);
                } catch (NumberFormatException e) {
                    log.warn("invalid queue payload: {}", payload);
                    continue;
                }

                log.info("ai worker picked sessionId={}", sessionId);
                AiSessionDO session = sessionDao.findById(sessionId);
                if (session == null || !AiConstants.Status.QUEUED.equals(session.getStatus())) {
                    log.info("ai worker skip non-queued sessionId={} status={}", sessionId,
                            session != null ? session.getStatus() : "NOT_FOUND");
                    continue;
                }

                executeSession(session, null);
            } catch (Exception e) {
                if (running) {
                    log.error("worker poll error", e);
                }
            }
        }
    }

    public void executeSession(AiSessionDO session, String userInput) {
        SceneAdapter adapter = sceneRegistry.get(session.getScene());
        if (adapter == null) {
            log.info("ai session exec failed sessionId={} reason=unsupported_scene scene={}", session.getId(), session.getScene());
            sessionDao.updateFailed(session.getId(), session.getTenantId(),
                    "unsupported scene: " + session.getScene(), session.getVersion());
            return;
        }

        String base = adapter.buildSystemPrompt(session);
        String systemPrompt = (base != null ? base : "") + API_MODE_SUFFIX;
        log.info("AI session step=system_prompt_built sessionId={} scene={} systemPromptLen={}",
                session.getId(), session.getScene(),
                systemPrompt != null ? systemPrompt.length() : 0);

        int updated = sessionDao.updateRunning(session.getId(), session.getTenantId(),
                nodeIdentity.getNodeId(), session.getCliSessionRef(), session.getVersion());
        if (updated == 0) {
            return;
        }
        int currentVersion = session.getVersion() + 1;
        log.info("AI session execution started sessionId={} scene={} bizRefType={} bizRefId={} nodeId={}",
                session.getId(), session.getScene(), session.getBizRefType(),
                session.getBizRefId(), nodeIdentity.getNodeId());
        streamPublisher.publishStatus(session.getId(), session.getTenantId(), AiConstants.Status.RUNNING);

        Path workDir = Path.of("/tmp/aiw/" + session.getId());
        boolean terminalState = false;
        try {
            Files.createDirectories(workDir);
            log.info("AI session step=workdir_ready sessionId={} scene={} workDir={}, hasResume={}",
                    session.getId(), session.getScene(), workDir.toAbsolutePath().normalize(), session.getCliSessionRef() != null);

            boolean isResume = session.getCliSessionRef() != null;

            Path cliWorkDir = workDir;
            String promptInput = userInput;
            if (isResume && promptInput == null) {
                List<AiMessageDO> msgs = messageDao.listBySession(session.getId());
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    if (AiConstants.Role.USER.equals(msgs.get(i).getRole())) {
                        promptInput = msgs.get(i).getContent();
                        break;
                    }
                }
            }
            if (AiConstants.Scene.REPO_SCAN.equals(session.getScene())) {
                if (!isResume) {
                    log.info("AI session step=repo_prepare_start sessionId={} repoId={}",
                            session.getId(), session.getBizRefId());
                    cliWorkDir = repoWorkspacePreparer.prepare(session, workDir);
                    promptInput = cliWorkDir.toString();
                    log.info("AI session step=repo_prepare_done sessionId={} repoId={} repoPath={}",
                            session.getId(), session.getBizRefId(), cliWorkDir);
                } else {
                    Path repoDir = workDir.resolve("repo");
                    if (Files.isDirectory(repoDir)) {
                        cliWorkDir = repoDir;
                    }
                }
            } else if (AiConstants.Scene.CLARIFICATION.equals(session.getScene()) && !isResume) {
                log.info("AI session step=multi_repo_prepare_start sessionId={} tenantId={}",
                        session.getId(), session.getTenantId());
                cliWorkDir = repoWorkspacePreparer.prepareMultiRepo(session.getTenantId(), workDir);
                log.info("AI session step=multi_repo_prepare_done sessionId={} reposPath={}",
                        session.getId(), cliWorkDir);
            }

            String prompt = isResume ? promptInput : adapter.buildUserPrompt(session, promptInput);
            if (prompt == null || prompt.isBlank()) {
                log.warn("AI session skip empty prompt sessionId={} isResume={}", session.getId(), isResume);
                sessionDao.updateFailed(session.getId(), session.getTenantId(),
                        "empty prompt on " + (isResume ? "resume" : "initial"), currentVersion);
                streamPublisher.publishStatus(session.getId(), session.getTenantId(), AiConstants.Status.FAILED);
                terminalState = true;
                return;
            }
            log.info("AI session step=prompt_ready sessionId={} scene={} promptLen={} cliWorkDir={} isResume={} promptPreview={}",
                    session.getId(), session.getScene(),
                    prompt != null ? prompt.length() : 0, cliWorkDir, isResume,
                    prompt != null ? prompt.substring(0, Math.min(prompt.length(), 200)) : "null");

            CliResult result = cliExecutor.execute(prompt, session.getCliSessionRef(),
                    cliWorkDir.toString(), allowedToolsFor(session), isResume ? null : systemPrompt,
                    text -> streamPublisher.publishDelta(session.getId(), session.getTenantId(), text));
            log.info("AI session step=cli_done sessionId={} exitCode={} success={} hasJson={} cliSessionId={} fullTextLen={} error={}",
                    session.getId(), result.getExitCode(), result.isSuccess(),
                    result.getExtractedJson() != null, result.getCliSessionId(),
                    result.getFullText() != null ? result.getFullText().length() : 0,
                    result.getError());

            if (result.getCliSessionId() != null) {
                int rows = sessionDao.updateCliSessionRef(session.getId(), session.getTenantId(),
                        result.getCliSessionId(), currentVersion);
                if (rows > 0) {
                    currentVersion++;
                }
            }

            if (!result.isSuccess()) {
                log.warn("AI session execution failed sessionId={} scene={} error={}",
                        session.getId(), session.getScene(), result.getError());
                sessionDao.updateFailed(session.getId(), session.getTenantId(),
                        truncate(result.getError(), 500), currentVersion);
                markRepoScanFailedIfNeeded(session);
                streamPublisher.publishStatus(session.getId(), session.getTenantId(), AiConstants.Status.FAILED);
                terminalState = true;
                return;
            }

            String responseText = result.getFullText();
            if (responseText != null && !responseText.isBlank()) {
                int nextSeq = messageDao.maxSeq(session.getId()) + 1;
                AiMessageDO aiMsg = new AiMessageDO();
                aiMsg.setTenantId(session.getTenantId());
                aiMsg.setSessionId(session.getId());
                aiMsg.setSeq(nextSeq);
                aiMsg.setRole(AiConstants.Role.AI);
                aiMsg.setContent(responseText);
                messageDao.insert(aiMsg);
            }

            String extracted = result.getExtractedJson();
            if (extracted != null) {
                sessionDao.updateResult(session.getId(), session.getTenantId(),
                        extracted, AiConstants.Status.WAIT_USER, currentVersion);
                streamPublisher.publishResult(session.getId(), session.getTenantId(), extracted);
            } else {
                sessionDao.updateStatus(session.getId(), session.getTenantId(),
                        AiConstants.Status.RUNNING, AiConstants.Status.WAIT_USER, currentVersion);
            }
            streamPublisher.publishStatus(session.getId(), session.getTenantId(), AiConstants.Status.WAIT_USER);
            log.info("AI session waiting for user sessionId={} scene={} hasJson={}",
                    session.getId(), session.getScene(), extracted != null);
            aiUsageService.recordUsage(session.getTenantId(), session.getScene(), 0, 0);
        } catch (Exception e) {
            log.error("session execution error id={}", session.getId(), e);
            sessionDao.updateFailed(session.getId(), session.getTenantId(),
                    truncate(e.getMessage(), 500), currentVersion);
            markRepoScanFailedIfNeeded(session);
            streamPublisher.publishStatus(session.getId(), session.getTenantId(), AiConstants.Status.FAILED);
            terminalState = true;
        } finally {
            if (terminalState) {
                cleanupWorkDir(workDir);
            }
        }
    }

    private String allowedToolsFor(AiSessionDO session) {
        if (AiConstants.Scene.SDLC_GEN.equals(session.getScene())
                || AiConstants.Scene.AGENT_CONFIG_GEN.equals(session.getScene())) {
            return "";
        }
        return null;
    }

    private void markRepoScanFailedIfNeeded(AiSessionDO session) {
        if (!AiConstants.Scene.REPO_SCAN.equals(session.getScene())
                || !"REPO".equals(session.getBizRefType())
                || session.getBizRefId() == null) {
            return;
        }
        RepoDO repo = repoDao.findById(session.getBizRefId());
        if (repo == null) {
            return;
        }
        repoDao.updateScanStatus(repo.getId(), session.getTenantId(), "FAILED",
                repo.getVersion(), null);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    private void cleanupWorkDir(Path workDir) {
        try {
            if (Files.exists(workDir)) {
                Files.walk(workDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception e) {
            log.warn("cleanup failed: {}", workDir, e);
        }
    }

}
