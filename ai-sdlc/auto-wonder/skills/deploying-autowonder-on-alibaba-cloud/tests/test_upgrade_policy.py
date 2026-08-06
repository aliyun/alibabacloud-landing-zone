import unittest
from pathlib import Path


SKILL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SKILL_ROOT.parents[1]


class UpgradePolicyTest(unittest.TestCase):
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
