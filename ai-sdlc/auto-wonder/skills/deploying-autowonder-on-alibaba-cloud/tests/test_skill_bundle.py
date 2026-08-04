import re
import unittest
from pathlib import Path


SKILL_ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    "SKILL.md",
    "assets/terraform/main.tf",
    "assets/terraform/terraform.tfvars.example",
    "assets/systemd/autowonder.service",
    "assets/templates/deployment-manifest.json",
    "scripts/lib.sh",
    "scripts/preflight.sh",
    "scripts/build-release.sh",
    "scripts/terraform-stage.sh",
    "scripts/deploy-via-cloud-assistant.sh",
    "scripts/initialize-and-verify.sh",
    "scripts/sanitize-evidence.sh",
    "references/input-catalog.md",
    "references/architecture-and-resources.md",
    "references/operations-runbook.md",
    "references/troubleshooting.md",
    "references/qa-reference.md",
    "references/acceptance-and-rollback.md",
]

REFERENCE_PATHS = [path for path in REQUIRED if path.startswith("references/")]
REQUIRED_REFERENCE_TERMS = [
    "cn-zhangjiakou",
    "cn-hangzhou",
    "cn-shanghai",
    "cn-beijing",
    "multi-zone",
    "DeploymentId",
    "CostCenter",
    "Cloud Assistant",
    "/opt/autowonder",
    "/etc/autowonder/autowonder.env",
    "/var/lib/autowonder/logs",
    "checkpreload.htm",
    "enc:v1:",
    "allowPublicKeyRetrieval=true",
    "IndexConfigNotExist",
    "jq -Rrs @sh",
    "ws://",
    "wss://",
    "port 443",
    "query token",
    "rollback",
    "teardown",
]

REQUIRED_SKILL_TERMS = [
    "New deployment",
    "Resume deployment",
    "QA and diagnosis",
    "Teardown",
    "one consolidated questionnaire",
    "staged",
    "unattended",
    "sanitized manifest",
    "OSS is mandatory",
    "SLS is enabled",
    "multi-zone HA",
    "no NAT",
    "no public EIP",
    "no SSH",
    "admin",
    "Infrastructure ready",
    "Application ready",
    "Business initialized",
    "Release accepted",
    "TLS accepted",
]


class SkillBundleTest(unittest.TestCase):
    def test_required_files_exist(self):
        missing = [path for path in REQUIRED if not (SKILL_ROOT / path).is_file()]
        self.assertEqual([], missing, f"Missing Skill bundle files: {missing}")

    def test_frontmatter_has_valid_identity(self):
        content = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        match = re.match(r"\A---\n(.*?)\n---(?:\n|\Z)", content, re.DOTALL)
        self.assertIsNotNone(match, "SKILL.md must start with YAML frontmatter")

        fields = {}
        for line in match.group(1).splitlines():
            key, separator, value = line.partition(":")
            self.assertTrue(separator, f"Invalid frontmatter line: {line!r}")
            self.assertNotIn(key, fields, f"Duplicate frontmatter field: {key}")
            fields[key] = value.strip()

        self.assertEqual({"name", "description"}, set(fields))
        self.assertEqual(
            "deploying-autowonder-on-alibaba-cloud", fields["name"]
        )
        self.assertTrue(fields["description"].startswith("Use when"))

    def test_references_are_progressive_and_sanitized(self):
        combined = []
        for relative_path in REFERENCE_PATHS:
            content = (SKILL_ROOT / relative_path).read_text(encoding="utf-8")
            self.assertRegex(content, r"\A# .+", f"{relative_path} needs a title")
            self.assertIn("## Purpose", content, f"{relative_path} needs a purpose")
            combined.append(content)

        text = "\n".join(combined)
        for term in REQUIRED_REFERENCE_TERMS:
            self.assertIn(term, text, f"References must document {term!r}")

        forbidden_patterns = {
            "Alibaba Cloud resource ID": r"\b(?:i|vpc|vsw|sg|nlb|t)-[a-z0-9]{8,}\b",
            "account UID": r"\b\d{15,20}\b",
            "credential assignment": (
                r"(?i)(?:password|access[_-]?key[_-]?secret|executor[_-]?token)\s*[:=]"
                r"\s*[^<{\s][^\s]*"
            ),
        }
        for label, pattern in forbidden_patterns.items():
            self.assertIsNone(re.search(pattern, text), f"References contain {label}")

    def test_skill_dispatcher_is_concise_and_complete(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        self.assertLess(len(skill.splitlines()), 500)
        for term in REQUIRED_SKILL_TERMS:
            self.assertIn(term, skill, f"SKILL.md must route {term!r}")

        for reference_path in REFERENCE_PATHS:
            self.assertIn(reference_path, skill)
        for script in [path for path in REQUIRED if path.startswith("scripts/")]:
            self.assertIn(script, skill)

    def test_skill_documents_portable_build_environment(self):
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        for term in [
            "## Build And Runtime Environment",
            "JDK 21",
            "Maven 3.9.9",
            "Node.js 22.22.2",
            "npm 10.9.7",
            "downloads the pinned Node.js and npm versions",
            "Linux x86_64",
        ]:
            self.assertIn(term, skill, f"SKILL.md must document {term!r}")


if __name__ == "__main__":
    unittest.main()
