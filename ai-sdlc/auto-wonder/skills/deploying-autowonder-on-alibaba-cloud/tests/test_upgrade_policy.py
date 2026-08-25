import unittest
from pathlib import Path


SKILL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SKILL_ROOT.parents[1]


class UpgradePolicyTest(unittest.TestCase):
    def test_upgrade_plan_is_the_only_mutation_authority(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (SKILL_ROOT / "references" / "upgrade-runbook.md").read_text(
            encoding="utf-8"
        )
        policy = " ".join(f"{skill}\n{runbook}".split()).lower()

        for term in [
            "only mutation authority",
            "do not clean up, delete, recreate, resize, reconfigure, replace",
            "read-only diagnostics",
            "revised plan",
            "explicit human confirmation",
        ]:
            self.assertIn(term, policy)

    def test_rolling_upgrade_failure_never_rolls_back_automatically(self):
        initialize = (
            SKILL_ROOT / "scripts" / "internal" / "operations.sh"
        ).read_text(encoding="utf-8")
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
