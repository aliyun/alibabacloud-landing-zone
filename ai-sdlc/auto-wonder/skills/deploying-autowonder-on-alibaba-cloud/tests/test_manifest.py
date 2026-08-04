import json
import unittest
from pathlib import Path


MANIFEST_PATH = (
    Path(__file__).resolve().parents[1]
    / "assets"
    / "templates"
    / "deployment-manifest.json"
)
FORBIDDEN_KEYS = {
    "password",
    "secret",
    "accessKeySecret",
    "jwtSecret",
    "masterKey",
    "presignedUrl",
    "executorToken",
}
REQUIRED_TAGS = {
    "Project",
    "Environment",
    "DeploymentId",
    "ManagedBy",
    "Topology",
}


def walk(value):
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


class ManifestContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))

    def test_secure_defaults(self):
        manifest = self.manifest
        self.assertEqual(1, manifest["schemaVersion"])
        self.assertEqual("new", manifest["mode"])
        self.assertEqual("questionnaire", manifest["phase"])
        self.assertEqual("planned", manifest["status"])
        self.assertEqual(
            {
                "cn-zhangjiakou",
                "cn-hangzhou",
                "cn-shanghai",
                "cn-beijing",
            },
            set(manifest["regionPreset"]),
        )
        self.assertEqual("multi-az-ha", manifest["topology"])
        self.assertEqual("remote", manifest["stateMode"])
        self.assertEqual("persistent", manifest["lifecycle"])
        self.assertIs(True, manifest["slsEnabled"])
        self.assertIs(False, manifest["aoneEnabled"])
        self.assertIs(False, manifest["publicEgress"])
        self.assertEqual("admin", manifest["adminUsername"])
        self.assertEqual("x86_64", manifest["architecture"])
        self.assertEqual([], manifest["availabilityZones"])
        self.assertEqual("", manifest["resolvedInfrastructure"]["ecsImageId"])

    def test_manifest_contains_no_secret_fields_or_values(self):
        forbidden_lower = {key.lower() for key in FORBIDDEN_KEYS}
        for key, value in walk(self.manifest):
            self.assertNotIn(key.lower(), forbidden_lower)
            if isinstance(value, str):
                self.assertFalse(
                    any(token.lower() in value.lower() for token in FORBIDDEN_KEYS),
                    f"Secret-bearing value found at {key}",
                )

    def test_required_system_tags_are_present(self):
        self.assertTrue(REQUIRED_TAGS.issubset(self.manifest["tags"]))


if __name__ == "__main__":
    unittest.main()
