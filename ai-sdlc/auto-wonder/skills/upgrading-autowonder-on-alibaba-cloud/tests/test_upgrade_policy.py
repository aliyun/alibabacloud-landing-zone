import unittest
from pathlib import Path


SKILL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SKILL_ROOT.parents[1]


class UpgradePolicyTest(unittest.TestCase):
    def test_upgrade_info_is_persistent_secret_safe_and_scale_aware(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for term in [
            "upgrade-info/index.json",
            "--deployment-dir",
            "refresh-upgrade-info",
            "every upgrade run",
            "local state",
            "oss remote state",
            "never persist backend credentials",
            "complete tagged cloud ecs set",
            "resource set fingerprint",
            "invalidates any prior plan approval",
            "newly scaled ecs",
            "register-manifest",
        ]:
            self.assertIn(term, policy)
        for obsolete in ["legacy-folder-required", "--legacy-dir", "-legacydirectory"]:
            self.assertNotIn(obsolete, f"{skill}\n{runbook}".lower())

    def test_upgrade_backup_and_confirmed_one_click_rollback_contract(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        operations = (SKILL_ROOT / "scripts" / "internal" / "operations.sh").read_text(encoding="utf-8")
        wrapper = (SKILL_ROOT / "scripts" / "upgrade-operations.sh").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for term in [
            "exactly one backup archive per ecs",
            "never roll back automatically",
            "only after the user confirms rollback",
        ]:
            self.assertIn(term, policy)
        for term in [
            "upgrade-backup)",
            "rollback-upgrade)",
            "/opt/autowonder/upgrade-rollback-backup.tar.gz",
            "mv -f \"$backup_tmp\" \"$backup_archive\"",
            "--confirm-rollback",
            "ROLLBACK_STATUS=passed",
            'status:"partial"',
            "failedInstanceId",
            "autowonder.env.previous",
            "autowonder.service.previous",
            "-delete",
        ]:
            self.assertIn(term, operations)
        backup_operation = operations.split("  upgrade-backup)", 1)[1].split(
            "  rollback-upgrade)", 1
        )[0]
        self.assertLess(
            backup_operation.index("sha256sum -c CHECKSUMS"),
            backup_operation.index('mv -f "$backup_tmp" "$backup_archive"'),
        )
        self.assertIn("upgrade-backup|rollback-upgrade", wrapper)

    def test_startup_contract_separates_credentials_from_missing_targets(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for term in [
            "detect the control host",
            "missing supported third-party dependencies without conversational confirmation",
            "alibaba cloud cli",
            "sts getcalleridentity",
            "--mode oauth",
            "missing targets does not establish an expired login",
            "only a confirmed missing profile/session or recognized credential failure triggers oauth",
            "do not trigger login for transient api/network failures or unknown errors",
            "do not replay mutations",
        ]:
            self.assertIn(term, policy)

    def test_upgrade_target_uses_discovered_default_branch_without_ancestry_gate(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for term in [
            "discover the recorded repository default branch",
            "isolated clean detached worktree",
            "worktree at the exact fetched commit",
            "preserve a dirty, ahead, or divergent local branch unchanged",
            "never merge, rebase, reset, or build it",
            "an explicitly authorized source repository change remains bound to the same deployment",
            "commit equality is the only version-availability check",
            "do not block because of git ancestry",
        ]:
            self.assertIn(term, policy)

    def test_same_target_commit_is_reported_as_already_latest_and_skipped(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for term in [
            "already the latest version",
            "skip planning, approval, build, staging, migration, and activation",
        ]:
            self.assertIn(term, policy)

    def test_shared_bootstrap_uses_private_runtime_instead_of_global_installers(self):
        runtime_root = SKILL_ROOT
        posix = (runtime_root / 'scripts/bootstrap-control-host.sh').read_text()
        windows = (runtime_root / 'scripts/windows/bootstrap-control-host.ps1').read_text()
        self.assertIn('autowonder_runtime_environment', posix)
        self.assertIn('runtime-bootstrap.ps1', windows)
        self.assertIn('tool_runtime.py', windows)
        for source in (posix, windows):
            self.assertNotIn('brew install', source)
            self.assertNotIn('winget install', source)
            self.assertNotIn('ossutil-v1.', source)

    def test_upgrade_plan_is_the_only_mutation_authority(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for term in [
            "only mutation authority",
            "do not clean up, delete, recreate, resize, reconfigure, replace",
            "bounded deterministic repair",
            "without confirmation",
            "ask the user only when",
        ]:
            self.assertIn(term, policy)

    def test_default_upgrade_flow_is_unattended_except_impact_gates(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for term in [
            "run unattended by default",
            "deployment-folder-required",
            "do not ask the user to reconfirm",
            "never ask for individual infrastructure values",
            "approve automatically",
            "deliver one consolidated report after acceptance",
            "new database migrations",
            "target/resource change",
            "rollback",
        ]:
            self.assertIn(term, policy)

    def test_rolling_upgrade_failure_never_rolls_back_automatically(self):
        initialize = (SKILL_ROOT / "scripts" / "internal" / "operations.sh").read_text(encoding="utf-8")
        rolling = initialize.split("  rolling-upgrade)", 1)[1].split(
            "  rolling-start)", 1
        )[0]
        normalized = rolling.replace('\\\"', '"')

        self.assertIn("RESOLUTION_REQUIRED=human-confirmation", normalized)
        self.assertNotIn("automatic application rollback", normalized)
        self.assertNotIn("rollback_status=passed", normalized)
        self.assertNotIn(
            'ln -sfn "$previous" /opt/autowonder/current.new', normalized
        )

    def test_upgrade_acceptance_scope_is_ecs_local_only(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for required in (
            "ecs-local-only acceptance",
            "all ecs-local checks pass",
            "does not inspect alb, certificates, or domain names",
            "does not require rds, redis, oss, sls, restart, executor websocket, tags, or secret-log acceptance checks",
        ):
            self.assertIn(required, policy)

    def test_migration_directory_has_immutable_version_contract(self):
        policy_path = REPO_ROOT / "docs" / "migration" / "README.md"
        self.assertTrue(policy_path.is_file())
        policy = policy_path.read_text(encoding="utf-8")
        normalized = " ".join(policy.split())
        for term in [
            "V<n>__<description>.sql",
            "strictly increasing",
            "never modify, rename, or delete",
            "autowonder-schema.sql",
        ]:
            self.assertIn(term, normalized)

    def test_community_docs_retain_incremental_migrations(self):
        policy = (REPO_ROOT / "docs" / "community" / "docs-policy.md").read_text(
            encoding="utf-8"
        )
        runtime = (REPO_ROOT / "docs" / "community" / "README.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("docs/migration/", policy)
        self.assertIn("docs/migration/", runtime)
        self.assertNotIn("Community currently supports fresh database initialization only", policy)

    def test_upstream_sync_requires_migration_for_ddl_changes(self):
        guide = (
            REPO_ROOT / "docs" / "community" / "upstream-sync-guide.md"
        ).read_text(encoding="utf-8")
        normalized = " ".join(guide.split())
        for term in [
            "DDL",
            "docs/migration/",
            "full schema alone is not sufficient",
            "modified, renamed, or deleted",
        ]:
            self.assertIn(term, normalized)


if __name__ == "__main__":
    unittest.main()
