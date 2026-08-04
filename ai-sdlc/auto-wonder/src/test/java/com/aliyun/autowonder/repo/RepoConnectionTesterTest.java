package com.aliyun.autowonder.repo;

import com.aliyun.autowonder.repo.dto.TestRepoConnectionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepoConnectionTesterTest {

    @TempDir
    Path tempDir;

    @Test
    void buildLsRemoteCommandUsesGitAndBranch() {
        RepoConnectionTester tester = new RepoConnectionTester("git", 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("https://github.com/org/repo.git");
        req.setDefaultBranch("main");

        assertEquals(List.of("git", "ls-remote", "--heads", "https://github.com/org/repo.git", "main"),
                tester.buildLsRemoteCommand(req));
    }

    @Test
    void buildLsRemoteCommandOmitsBranchWhenBlank() {
        RepoConnectionTester tester = new RepoConnectionTester("git", 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("git@github.com:org/repo.git");
        req.setDefaultBranch("   ");

        assertEquals(List.of("git", "ls-remote", "--heads", "git@github.com:org/repo.git"),
                tester.buildLsRemoteCommand(req));
    }

    @Test
    void sshUrlCarriesNoCredentialArgumentsAndReliesOnLocalGit() {
        RepoConnectionTester tester = new RepoConnectionTester("git", 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("git@github.com:org/repo.git");

        List<String> command = tester.buildLsRemoteCommand(req);

        String joined = String.join(" ", command);
        assertFalse(joined.contains("ssh -i"));
        assertFalse(joined.contains("IdentitiesOnly"));
        assertFalse(joined.contains("GIT_ASKPASS"));
    }

    @Test
    void testFailsFastWhenUrlMissing() {
        RepoConnectionTester tester = new RepoConnectionTester("git", 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();

        RepoConnectionTestResult result = tester.test(req);

        assertFalse(result.isSuccess());
        assertEquals("仓库地址不能为空", result.getMessage());
    }

    @Test
    void testReadsProcessStreamsWithProjectThreadPool() throws Exception {
        Path fakeGit = tempDir.resolve("fake-git.sh");
        Files.writeString(fakeGit, """
                #!/bin/sh
                echo refs/heads/main
                echo diagnostic >&2
                exit 0
                """, StandardCharsets.UTF_8);
        assertTrue(fakeGit.toFile().setExecutable(true, true));
        RepoConnectionTester tester = new RepoConnectionTester(fakeGit.toString(), 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("https://github.com/org/repo.git");

        RepoConnectionTestResult result = tester.test(req);

        assertTrue(result.isSuccess());
        assertEquals("连接成功，已验证读取权限", result.getMessage());
    }

    @Test
    void testDisablesGitInteractivePromptSoLocalPermissionFailureDoesNotHang() throws Exception {
        Path fakeGit = tempDir.resolve("echo-prompt-git.sh");
        Files.writeString(fakeGit, """
                #!/bin/sh
                echo "prompt=$GIT_TERMINAL_PROMPT" >&2
                exit 128
                """, StandardCharsets.UTF_8);
        assertTrue(fakeGit.toFile().setExecutable(true, true));
        RepoConnectionTester tester = new RepoConnectionTester(fakeGit.toString(), 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("https://github.com/org/private.git");

        RepoConnectionTestResult result = tester.test(req);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("prompt=0"), result.getMessage());
    }

    @Test
    void failureMessagePointsAtLocalGitPermissionInsteadOfCredentialInput() throws Exception {
        Path fakeGit = tempDir.resolve("silent-fail-git.sh");
        Files.writeString(fakeGit, """
                #!/bin/sh
                exit 128
                """, StandardCharsets.UTF_8);
        assertTrue(fakeGit.toFile().setExecutable(true, true));
        RepoConnectionTester tester = new RepoConnectionTester(fakeGit.toString(), 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("https://github.com/org/private.git");

        RepoConnectionTestResult result = tester.test(req);

        assertFalse(result.isSuccess());
        assertEquals("连接测试失败，请检查仓库地址、网络和本机 git 权限", result.getMessage());
    }
}
