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
        req.setUrl("https://github.com/workspace/repo.git");
        req.setDefaultBranch("main");

        assertEquals(List.of("git", "ls-remote", "--heads", "https://github.com/workspace/repo.git", "main"),
                tester.buildLsRemoteCommand(req));
    }

    @Test
    void buildLsRemoteCommandOmitsBranchWhenBlank() {
        RepoConnectionTester tester = new RepoConnectionTester("git", 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("git@github.com:workspace/repo.git");
        req.setDefaultBranch("   ");

        assertEquals(List.of("git", "ls-remote", "--heads", "git@github.com:workspace/repo.git"),
                tester.buildLsRemoteCommand(req));
    }

    @Test
    void sshUrlCarriesNoCredentialArgumentsAndReliesOnLocalGit() {
        RepoConnectionTester tester = new RepoConnectionTester("git", 10);
        TestRepoConnectionRequest req = new TestRepoConnectionRequest();
        req.setUrl("git@github.com:workspace/repo.git");

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
        req.setUrl("https://github.com/workspace/repo.git");

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
        req.setUrl("https://github.com/workspace/private.git");

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
        req.setUrl("https://github.com/workspace/private.git");

        RepoConnectionTestResult result = tester.test(req);

        assertFalse(result.isSuccess());
        assertEquals("连接测试失败，请检查仓库地址、网络和本机 git 权限", result.getMessage());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "success", "silent", "stderr", "timeout", "exception"})
    void freshConnectionSummaryUsesLocaleAndKeepsSanitizedExternalDetail(String branch) throws Exception {
        Path fake = tempDir.resolve("fake-"+branch+".sh");
        String script = switch(branch) {case "success" -> "#!/bin/sh\nexit 0\n";
            case "stderr" -> "#!/bin/sh\necho 'Warning: Permanently added raw' >&2\necho '<b>raw</b>连接测试失败' >&2\nexit 128\n";
            case "timeout" -> "#!/bin/sh\nsleep 1\n";default -> "#!/bin/sh\nexit 128\n";};
        Files.writeString(fake,script);assertTrue(fake.toFile().setExecutable(true,true));
        var req = new TestRepoConnectionRequest(); if(!branch.equals("missing")) req.setUrl("https://raw");
        var tester = new RepoConnectionTester(branch.equals("exception") ? tempDir.resolve("missing").toString() : fake.toString(),branch.equals("timeout") ? 0 : 3);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        try {var result = tester.test(req); assertEquals(branch.equals("success"),result.isSuccess());
            switch(branch) {
                case "missing" -> assertEquals("The repository address cannot be empty",result.getMessage());
                case "success" -> assertEquals("Connection succeeded; read access verified",result.getMessage());
                case "silent" -> assertEquals("Connection test failed. Check the repository address, network and local git permissions",result.getMessage());
                case "stderr" -> assertEquals("Connection test failed: <b>raw</b>连接测试失败",result.getMessage());
                case "timeout" -> assertEquals("Connection test timed out. Check the repository address, network and local git permissions",result.getMessage());
                case "exception" -> assertTrue(result.getMessage().startsWith("Connection test failed: Cannot run program"),result.getMessage());
            }
        } finally {org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();}
    }

}
