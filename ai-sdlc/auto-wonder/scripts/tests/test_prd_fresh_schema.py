"""Static guard supplementing real MySQL fresh/upgrade acceptance."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class PrdFreshSchemaTest(unittest.TestCase):
    def test_prd_and_profile_migration_tables_exist_in_full_schema(self):
        schema = (ROOT / 'docs/autowonder-schema.sql').read_text()
        for migration in ('V102__frontend_project_probe.sql', 'V104__prd_design_version.sql'):
            sql = (ROOT / 'docs/migration' / migration).read_text()
            tables = re.findall(r'^CREATE TABLE IF NOT EXISTS `([^`]+)`', sql, re.M)
            self.assertTrue(tables, migration)
            for table in tables:
                with self.subTest(table=table):
                    self.assertRegex(schema, r'CREATE TABLE IF NOT EXISTS `' + table + r'`\s*\(')

    def test_fresh_prd_design_preserves_manual_target_storage(self):
        schema = (ROOT / 'docs/autowonder-schema.sql').read_text()
        table = re.search(r'CREATE TABLE IF NOT EXISTS `prd_design`\s*\((.*?)\) ENGINE=', schema, re.S)
        self.assertIsNotNone(table)
        self.assertRegex(table.group(1), r'`target_config_json`\s+MEDIUMTEXT\s+DEFAULT NULL')


if __name__ == '__main__':
    unittest.main()
