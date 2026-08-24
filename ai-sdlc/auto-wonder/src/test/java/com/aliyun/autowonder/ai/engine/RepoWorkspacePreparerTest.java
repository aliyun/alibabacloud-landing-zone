package com.aliyun.autowonder.ai.engine;

import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoWorkspacePreparerTest {

    @Test
    void buildCloneCommandUsesDepthOneAndDefaultBranch() {
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(mock(RepoDao.class), "git", 120);
        RepoDO repo = new RepoDO();
        repo.setUrl("git@gitlab.alibaba-inc.com:sdlc-autopilot/auto-wonder.git");
        repo.setDefaultBranch("master");

        List<String> command = preparer.buildCloneCommand(repo, Path.of("/tmp/aiw/10003/repo"));

        assertEquals(List.of("git", "clone", "--depth", "1", "--branch", "master",
                "--single-branch", "git@gitlab.alibaba-inc.com:sdlc-autopilot/auto-wonder.git",
                "/tmp/aiw/10003/repo"), command);
    }

    @Test
    void prepareMultiRepoReturnsWorkDirWhenNoRepos(@TempDir Path tempDir) throws IOException {
        RepoDao repoDao = mock(RepoDao.class);
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of());
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(repoDao, "git", 120);

        Path result = preparer.prepareMultiRepo(100L, tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), result);
    }

    @Test
    void prepareMultiRepoSkipsRepoWithEmptyUrl(@TempDir Path tempDir) throws IOException {
        RepoDao repoDao = mock(RepoDao.class);
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setName("no-url-repo");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(repoDao, "git", 120);

        Path result = preparer.prepareMultiRepo(100L, tempDir);

        assertTrue(Files.exists(result));
        assertEquals("repos", result.getFileName().toString());
    }

    @Test
    void prepareMultiRepoReadsCloneProcessStreamsWithProjectThreadPool(@TempDir Path tempDir) throws Exception {
        Path fakeGit = tempDir.resolve("fake-git.sh");
        Files.writeString(fakeGit, """
                #!/bin/sh
                target=""
                for arg in "$@"; do
                  target="$arg"
                done
                mkdir -p "$target"
                echo clone-output
                echo clone-diagnostic >&2
                exit 0
                """, StandardCharsets.UTF_8);
        assertTrue(fakeGit.toFile().setExecutable(true, true));
        RepoDao repoDao = mock(RepoDao.class);
        RepoDO repo = new RepoDO();
        repo.setId(7L);
        repo.setName("main-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(repoDao, fakeGit.toString(), 120);

        Path result = preparer.prepareMultiRepo(100L, tempDir.resolve("work"));

        assertEquals("repos", result.getFileName().toString());
        assertTrue(Files.isDirectory(result.resolve("main-repo")));
    }

    @Test
    void cloneUsesLocalGitPermissionWithoutWritingCredentialFiles(@TempDir Path tempDir) throws Exception {
        Path fakeGit = tempDir.resolve("env-probe-git.sh");
        Path envDump = tempDir.resolve("env-dump.txt");
        Files.writeString(fakeGit, """
                #!/bin/sh
                target=""
                for arg in "$@"; do
                  target="$arg"
                done
                mkdir -p "$target"
                {
                  echo "GIT_SSH_COMMAND=$GIT_SSH_COMMAND"
                  echo "GIT_ASKPASS=$GIT_ASKPASS"
                  echo "AUTOWONDER_REPO_TOKEN=$AUTOWONDER_REPO_TOKEN"
                  echo "GIT_TERMINAL_PROMPT=$GIT_TERMINAL_PROMPT"
                } > "%s"
                exit 0
                """.formatted(envDump), StandardCharsets.UTF_8);
        assertTrue(fakeGit.toFile().setExecutable(true, true));
        RepoDao repoDao = mock(RepoDao.class);
        RepoDO repo = new RepoDO();
        repo.setId(7L);
        repo.setName("main-repo");
        repo.setUrl("git@github.com:workspace/private.git");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(repoDao, fakeGit.toString(), 120);

        Path result = preparer.prepareMultiRepo(100L, tempDir.resolve("work"));

        String env = Files.readString(envDump);
        assertTrue(env.contains("GIT_SSH_COMMAND=\n"), env);
        assertTrue(env.contains("GIT_ASKPASS=\n"), env);
        assertTrue(env.contains("AUTOWONDER_REPO_TOKEN=\n"), env);
        assertTrue(env.contains("GIT_TERMINAL_PROMPT=0"), env);
        assertFalse(Files.exists(result.resolve("repo_key")));
        assertFalse(Files.exists(result.resolve("git-askpass.sh")));
    }
}
