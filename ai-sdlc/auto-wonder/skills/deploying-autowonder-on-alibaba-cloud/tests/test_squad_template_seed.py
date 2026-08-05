import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SEED = ROOT / "docs" / "autowonder-community-templates.sql"

EXPECTED_COUNTS = {
    "solo": ("独立开发者", 1),
    "pair": ("开发+评审双人组", 2),
    "delivery": ("标准研发交付小队", 3),
    "full_cycle": ("全链路研发协作小队", 7),
}
FULL_CYCLE_ROLES = {
    "REQ_CLARIFIER",
    "PROJECT_MANAGER",
    "FS_DEV",
    "CR",
    "QA",
    "CONFLICT_RESOLVER",
    "DBA",
}
PLACEHOLDERS = {
    "SOURCE_REPOSITORY",
    "CODE_PLATFORM",
    "CODE_PLATFORM_MR_CREATE_URL",
    "COLLABORATION_PLATFORM",
    "DEFAULT_BRANCH",
    "DEPLOYMENT_PLATFORM",
    "DATABASE_HOST",
    "DATABASE_NAME",
    "DATABASE_PASSWORD_ENV",
    "DATABASE_PORT",
    "DATABASE_USER",
    "PROJECT_NAME",
    "PROJECT_TECH_STACK",
}


class SquadTemplateSeedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = SEED.read_text(encoding="utf-8")
        pattern = re.compile(
            r"SET @template_([a-z_]+) = '((?:[^']|'')*)';", re.DOTALL
        )
        cls.raw_payloads = dict(pattern.findall(cls.sql))
        cls.payloads = {
            key: json.loads(raw.replace("''", "'").replace("\\\\", "\\"))
            for key, raw in cls.raw_payloads.items()
        }

    def test_json_escapes_survive_mysql_string_literal_parsing(self):
        for key, raw in self.raw_payloads.items():
            for run in re.findall(r"\\+", raw):
                self.assertEqual(0, len(run) % 2, f"single backslash in {key}")
        self.assertIn("NO_BACKSLASH_ESCAPES", self.sql)
        self.assertIn("SET SESSION sql_mode", self.sql)

    def test_contains_exactly_four_valid_payloads(self):
        self.assertEqual(set(EXPECTED_COUNTS), set(self.payloads))
        for key, (name, count) in EXPECTED_COUNTS.items():
            payload = self.payloads[key]
            self.assertEqual(name, payload["template"]["name"])
            self.assertEqual(count, len(payload["agents"]))
            self.assertEqual(count, payload["template"]["squadSize"])

    def test_every_agent_has_unique_role_and_nonempty_sdlc(self):
        for payload in self.payloads.values():
            roles = [agent["roleCode"] for agent in payload["agents"]]
            self.assertEqual(len(roles), len(set(roles)))
            for agent in payload["agents"]:
                self.assertTrue(agent["name"].strip())
                self.assertTrue(agent["businessBackground"].strip())
                self.assertTrue(agent["responsibilities"].strip())
                steps = agent["sdlc"]["steps"]
                self.assertTrue(steps)
                self.assertEqual(
                    list(range(1, len(steps) + 1)),
                    [step["order"] for step in steps],
                )
                for step in steps:
                    self.assertTrue(step["name"].strip())
                    self.assertTrue(step["instruction"].strip())

    def test_full_cycle_has_the_seven_approved_roles(self):
        roles = {agent["roleCode"] for agent in self.payloads["full_cycle"]["agents"]}
        self.assertEqual(FULL_CYCLE_ROLES, roles)

    def test_uses_only_approved_external_placeholders(self):
        serialized = json.dumps(self.payloads, ensure_ascii=False)
        actual = set(re.findall(r"\{\{([A-Z_]+)}}", serialized))
        self.assertEqual(PLACEHOLDERS, actual)

    def test_seed_is_idempotent_and_environment_neutral(self):
        self.assertEqual(4, self.sql.count("UPDATE squad_template"))
        self.assertEqual(4, self.sql.count("INSERT INTO squad_template"))
        self.assertEqual(4, self.sql.count("WHERE NOT EXISTS"))
        self.assertNotRegex(self.sql, r"INSERT INTO squad_template\s*\(\s*id\b")

        payload_text = json.dumps(self.payloads, ensure_ascii=False).lower()
        for forbidden in (
            "autowonder",
            "aone-mix",
            "code.alibaba",
            "alibaba-inc.com",
            "aliyun-inc.com",
            "rm-0jlg",
            "autowonderdev",
        ):
            self.assertNotIn(forbidden, payload_text)


if __name__ == "__main__":
    unittest.main()
