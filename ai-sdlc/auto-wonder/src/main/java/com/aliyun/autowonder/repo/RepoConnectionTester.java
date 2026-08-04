package com.aliyun.autowonder.repo;

import com.aliyun.autowonder.configuration.ThreadPoolManager;
import com.aliyun.autowonder.repo.dto.TestRepoConnectionRequest;
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
public class RepoConnectionTester {

    private final String gitBinary;
    private final int timeoutSeconds;

    public RepoConnectionTester(
            @Value("${autowonder.repo.test.git-binary:git}") String gitBinary,
            @Value("${autowonder.repo.test.timeout-sec:30}") int timeoutSeconds) {
        this.gitBinary = gitBinary;
        this.timeoutSeconds = timeoutSeconds;
    }

    public RepoConnectionTestResult test(TestRepoConnectionRequest req) {
        if (req == null || req.getUrl() == null || req.getUrl().isBlank()) {
            return RepoConnectionTestResult.fail("仓库地址不能为空");
        }
        Path tempDir = null;
        try {
            // 空目录作为 cwd，避免继承调用方所在仓库的 git 配置
            tempDir = Files.createTempDirectory("autowonder-repo-test-");
            ProcessBuilder pb = new ProcessBuilder(buildLsRemoteCommand(req));
            pb.directory(tempDir.toFile());
            // 复用本机 git 权限，本机无权限时禁止 git 交互式索要账号密码而挂起
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = pb.start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> read(process.getInputStream()), ThreadPoolManager.networkCallPool);
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> read(process.getErrorStream()), ThreadPoolManager.networkCallPool);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return RepoConnectionTestResult.fail("连接测试超时，请检查仓库地址、网络和本机 git 权限");
            }
            stdout.join();
            String err = stderr.join();
            if (process.exitValue() == 0) {
                return RepoConnectionTestResult.ok("连接成功，已验证读取权限");
            }
            return RepoConnectionTestResult.fail(sanitizeGitError(err));
        } catch (Exception e) {
            return RepoConnectionTestResult.fail("连接测试失败：" + e.getMessage());
        } finally {
            deleteQuietly(tempDir);
        }
    }

    List<String> buildLsRemoteCommand(TestRepoConnectionRequest req) {
        List<String> command = new ArrayList<>();
        command.add(gitBinary);
        command.add("ls-remote");
        command.add("--heads");
        command.add(req.getUrl());
        if (req.getDefaultBranch() != null && !req.getDefaultBranch().isBlank()) {
            command.add(req.getDefaultBranch().trim());
        }
        return command;
    }

    private String sanitizeGitError(String err) {
        if (err == null || err.isBlank()) {
            return "连接测试失败，请检查仓库地址、网络和本机 git 权限";
        }
        String trimmed = err.replaceAll("(?m)^Warning: Permanently added .*$\\n?", "").trim();
        if (trimmed.length() > 500) {
            trimmed = trimmed.substring(0, 500);
        }
        return "连接测试失败：" + trimmed;
    }

    private String read(InputStream stream) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
            return "";
        }
        return sb.toString();
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walk(path)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort cleanup
                        }
                    });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
