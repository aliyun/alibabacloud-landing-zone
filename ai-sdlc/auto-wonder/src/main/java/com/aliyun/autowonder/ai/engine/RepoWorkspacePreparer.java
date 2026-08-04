package com.aliyun.autowonder.ai.engine;

import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.configuration.ThreadPoolManager;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class RepoWorkspacePreparer {

    private static final Logger log = LoggerFactory.getLogger(RepoWorkspacePreparer.class);

    private final RepoDao repoDao;
    private final String gitBinary;
    private final int cloneTimeoutSeconds;

    public RepoWorkspacePreparer(RepoDao repoDao,
            @Value("${autowonder.ai.repo-clone.git-binary:git}") String gitBinary,
            @Value("${autowonder.ai.repo-clone.timeout-sec:120}") int cloneTimeoutSeconds) {
        this.repoDao = repoDao;
        this.gitBinary = gitBinary;
        this.cloneTimeoutSeconds = cloneTimeoutSeconds;
    }

    public Path prepare(AiSessionDO session, Path sessionWorkDir) throws IOException, InterruptedException {
        if (!AiConstants.Scene.REPO_SCAN.equals(session.getScene())) {
            return sessionWorkDir.toAbsolutePath().normalize();
        }
        if (!"REPO".equals(session.getBizRefType()) || session.getBizRefId() == null) {
            throw new IllegalStateException("REPO_SCAN session missing repo reference");
        }

        RepoDO repo = repoDao.findById(session.getBizRefId());
        if (repo == null || repo.getTenantId() == null || !repo.getTenantId().equals(session.getTenantId())) {
            throw new IllegalStateException("repo not found for scan: " + session.getBizRefId());
        }
        if (repo.getUrl() == null || repo.getUrl().isBlank()) {
            throw new IllegalStateException("repo url is empty: " + session.getBizRefId());
        }

        Path repoDir = sessionWorkDir.resolve("repo").toAbsolutePath().normalize();
        Files.createDirectories(sessionWorkDir);

        cloneSingleRepo(repo, repoDir, session.getId());
        return repoDir;
    }

    public Path prepareMultiRepo(Long tenantId, Path sessionWorkDir) throws IOException {
        List<RepoDO> repos = repoDao.list(tenantId, 0, 100);
        if (repos == null || repos.isEmpty()) {
            log.info("multi-repo prep: no repos found for tenantId={}", tenantId);
            return sessionWorkDir.toAbsolutePath().normalize();
        }

        Path reposDir = sessionWorkDir.resolve("repos").toAbsolutePath().normalize();
        Files.createDirectories(reposDir);

        int cloned = 0;
        for (RepoDO repo : repos) {
            if (repo.getUrl() == null || repo.getUrl().isBlank()) {
                log.warn("multi-repo prep: skip repo with empty url repoId={} name={}", repo.getId(), repo.getName());
                continue;
            }
            String dirName = repo.getName() != null ? repo.getName().replaceAll("[^a-zA-Z0-9._-]", "_") : String.valueOf(repo.getId());
            Path repoDir = reposDir.resolve(dirName);
            try {
                cloneSingleRepo(repo, repoDir, null);
                cloned++;
            } catch (Exception e) {
                log.warn("multi-repo prep: clone failed repoId={} name={} error={}", repo.getId(), repo.getName(), e.getMessage());
            }
        }
        log.info("multi-repo prep done tenantId={} total={} cloned={}", tenantId, repos.size(), cloned);
        return reposDir;
    }

    private void cloneSingleRepo(RepoDO repo, Path repoDir, Long sessionId) throws IOException, InterruptedException {
        List<String> command = buildCloneCommand(repo, repoDir);
        log.info("repo clone step=clone_start repoId={} repoName={} repoUrl={} branch={} targetDir={} sessionId={}",
                repo.getId(), repo.getName(), repo.getUrl(), repo.getDefaultBranch(), repoDir, sessionId);

        Path tempDir = repoDir.getParent();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(false);
        // 复用本机 git 权限，本机无权限时禁止 git 交互式索要账号密码而挂起
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = pb.start();

        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() ->
                readAndLog(process.getInputStream(), "stdout", sessionId, repo.getId()), ThreadPoolManager.invokeTaskPool);
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() ->
                readAndLog(process.getErrorStream(), "stderr", sessionId, repo.getId()), ThreadPoolManager.invokeTaskPool);

        boolean finished = process.waitFor(cloneTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git clone timeout after " + cloneTimeoutSeconds + "s");
        }

        String out = stdout.join();
        String err = stderr.join();
        if (process.exitValue() != 0) {
            log.warn("repo clone step=clone_failed repoId={} exitCode={} stdout={} stderr={}",
                    repo.getId(), process.exitValue(), out, err);
            throw new IllegalStateException("git clone failed with exit code " + process.exitValue() + ": " + err);
        }

        log.info("repo clone step=clone_done repoId={} targetDir={}", repo.getId(), repoDir);
    }

    List<String> buildCloneCommand(RepoDO repo, Path repoDir) {
        List<String> command = new ArrayList<>();
        command.add(gitBinary);
        command.add("clone");
        command.add("--depth");
        command.add("1");
        if (repo.getDefaultBranch() != null && !repo.getDefaultBranch().isBlank()) {
            command.add("--branch");
            command.add(repo.getDefaultBranch());
            command.add("--single-branch");
        }
        command.add(repo.getUrl());
        command.add(repoDir.toString());
        return command;
    }

    private String readAndLog(InputStream inputStream, String stream, Long sessionId, Long repoId) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
                log.info("repo scan step=clone_output sessionId={} repoId={} stream={} line={}",
                        sessionId, repoId, stream, line);
            }
        } catch (IOException e) {
            log.warn("repo scan clone output read failed sessionId={} repoId={} stream={}",
                    sessionId, repoId, stream, e);
        }
        return content.toString();
    }
}
