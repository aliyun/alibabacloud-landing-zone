"""Exercise the release mirror gate using real, isolated Git object databases."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'verify-community-mirror.py'


class MirrorTests(unittest.TestCase):
    def git(self, repo, *args):
        return subprocess.check_output(
            ['git', '-C', str(repo), *args], env=self.env, stderr=subprocess.PIPE
        ).decode().strip()

    def commit(self, repo):
        self.git(repo, 'add', '-A')
        self.git(repo, 'commit', '-qm', 'fixture', '--allow-empty')
        return self.git(repo, 'rev-parse', 'HEAD')

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.env = {**os.environ, 'GIT_CONFIG_NOSYSTEM': '1',
                    'GIT_CONFIG_GLOBAL': os.devnull,
                    'GIT_AUTHOR_NAME': 'Fixture', 'GIT_AUTHOR_EMAIL': 'fixture@example.test',
                    'GIT_COMMITTER_NAME': 'Fixture', 'GIT_COMMITTER_EMAIL': 'fixture@example.test'}
        self.internal, self.public = self.root / 'internal', self.root / 'public'
        for repo in (self.internal, self.public):
            repo.mkdir()
            self.git(repo, 'init', '-q')
            self.git(repo, 'config', 'core.hooksPath', os.devnull)
            self.git(repo, 'config', 'core.fileMode', 'true')
        (self.internal / 'a.txt').write_text('accepted\n')
        (self.internal / '空 格\t\n.txt').write_text('unicode filename\n')
        (self.internal / 'run.sh').write_text('#!/bin/sh\nexit 0\n')
        (self.internal / 'run.sh').chmod(0o755)
        (self.internal / 'link').symlink_to('a.txt')
        self.accepted = self.commit(self.internal)
        self.community = self.commit(self.internal)  # new commit, same tree
        (self.public / 'README.md').write_text('outside mirror\n')
        self.base = self.commit(self.public)
        self.target = self.public / 'ai-sdlc' / 'auto-wonder'
        shutil.copytree(self.internal, self.target, symlinks=True,
                        ignore=shutil.ignore_patterns('.git'))
        self.output = self.commit(self.public)

    def run_gate(self, **overrides):
        args = {'internal-repo': self.internal, 'github-repo': self.public,
                'accepted-commit': self.accepted, 'community-commit': self.community,
                'github-commit': self.output, 'github-base': self.base}
        args.update(overrides)
        result = subprocess.run([sys.executable, str(SCRIPT),
                                 *[v for k, value in args.items() for v in ('--' + k, str(value))]],
                                env=self.env, capture_output=True, text=True)
        self.assertTrue(result.stdout.strip(), result.stderr)
        return result.returncode, json.loads(result.stdout)

    def test_identical_committed_trees_ignore_dirty_and_untracked_files(self):
        (self.target / 'a.txt').write_text('uncommitted\n')
        (self.target / 'secret.local').write_text('untracked\n')
        code, report = self.run_gate()
        self.assertEqual(code, 0)
        self.assertEqual(report['verdict'], 'MATCH')
        self.assertEqual(report['trees']['accepted']['entries'], 4)
        self.assertEqual(report['trees']['accepted']['tree'], report['trees']['github']['tree'])

    def test_detects_content_added_deleted_mode_and_symlink_changes(self):
        for change in ('content', 'added', 'deleted', 'mode', 'symlink'):
            with self.subTest(change=change):
                self.git(self.public, 'reset', '--hard', self.output)
                if change == 'content': (self.target / 'a.txt').write_text('different\n')
                if change == 'added': (self.target / 'extra').write_text('extra\n')
                if change == 'deleted': (self.target / 'a.txt').unlink()
                if change == 'mode': (self.target / 'run.sh').chmod(0o644)
                if change == 'symlink':
                    (self.target / 'link').unlink()
                    (self.target / 'link').symlink_to('run.sh')
                tip = self.commit(self.public)
                code, report = self.run_gate(**{'github-commit': tip})
                self.assertEqual(code, 1)
                self.assertEqual(report['verdict'], 'MISMATCH')
                self.assertGreater(report['comparisons']['communityToGithub']['differenceCount'], 0)

    def test_rejects_changed_community_even_if_github_matches_it(self):
        (self.internal / 'a.txt').write_text('unaccepted\n')
        community = self.commit(self.internal)
        (self.target / 'a.txt').write_text('unaccepted\n')
        public = self.commit(self.public)
        code, report = self.run_gate(**{'community-commit': community, 'github-commit': public})
        self.assertEqual(code, 1)
        self.assertEqual(report['comparisons']['acceptedToCommunity']['differenceCount'], 1)
        self.assertEqual(report['comparisons']['communityToGithub']['differenceCount'], 0)

    def test_rejects_changes_outside_mirror(self):
        (self.public / 'README.md').write_text('unrelated change\n')
        code, report = self.run_gate(**{'github-commit': self.commit(self.public)})
        self.assertEqual(code, 1)
        self.assertEqual(report['outsideChanges'], ['README.md'])

    def test_rejects_moving_refs_missing_objects_and_missing_subtree(self):
        for overrides in ({'accepted-commit': 'HEAD'},
                          {'community-commit': 'f' * 40},
                          {'github-commit': self.base}):
            with self.subTest(overrides=overrides):
                code, report = self.run_gate(**overrides)
                self.assertEqual(code, 2)
                self.assertEqual(report['verdict'], 'ERROR')

    def test_report_file_matches_stdout(self):
        path = self.root / 'result.json'
        code, report = self.run_gate(output=path)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(path.read_text()), report)

    def test_rejects_promisor_repositories_before_object_reads(self):
        # Old Git ignores GIT_NO_LAZY_FETCH: reject these repos even if all
        # currently requested objects happen to be present.
        self.git(self.internal, 'config', 'remote.origin.promisor', 'true')
        code, report = self.run_gate()
        self.assertEqual(code, 2)
        self.assertEqual(report['verdict'], 'ERROR')

    def test_report_io_failure_cannot_return_match(self):
        code, report = self.run_gate(output=self.root / 'missing-parent' / 'report.json')
        self.assertEqual(code, 2)
        self.assertEqual(report['verdict'], 'ERROR')

    def test_new_nested_directories_are_discovered_recursively(self):
        for root in (self.internal, self.target):
            folder = root / '新目录 with spaces' / 'nested' / 'deeper'
            folder.mkdir(parents=True)
            (folder / 'file\t\n.txt').write_text('nested payload\n')
            (root / 'empty-directory').mkdir()
        accepted = self.commit(self.internal)
        public = self.commit(self.public)
        code, report = self.run_gate(**{'accepted-commit': accepted,
                                       'community-commit': accepted, 'github-commit': public})
        self.assertEqual(code, 0)
        self.assertEqual(report['trees']['github']['entries'], 5)
        (self.target / 'only-on-github' / 'nested').mkdir(parents=True)
        (self.target / 'only-on-github' / 'nested' / 'file').write_text('extra\n')
        code, report = self.run_gate(**{'accepted-commit': accepted,
                                       'community-commit': accepted,
                                       'github-commit': self.commit(self.public)})
        self.assertEqual(code, 1)
        self.assertEqual(report['comparisons']['communityToGithub']['differenceCount'], 1)


if __name__ == '__main__':
    unittest.main()
