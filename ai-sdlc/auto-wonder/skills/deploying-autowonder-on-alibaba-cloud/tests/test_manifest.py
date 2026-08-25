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
        self.assertEqual("small", manifest["sizePreset"])
        self.assertEqual(2, manifest["resolvedInfrastructure"]["ecsVcpus"])
        self.assertEqual(4, manifest["resolvedInfrastructure"]["ecsMemoryGiB"])
        self.assertEqual(
            "ecs.c8a.large",
            manifest["resolvedInfrastructure"]["preferredEcsInstanceType"],
        )
        self.assertEqual("remote", manifest["stateMode"])
        self.assertEqual("auto-wonder-prod", manifest["environment"])
        self.assertEqual("pending", manifest["terraform"]["backendStatus"])
        self.assertEqual("", manifest["terraform"]["stateReference"])
        self.assertEqual("", manifest["terraform"]["stateBucket"])
        self.assertEqual("", manifest["terraform"]["stateKey"])
        self.assertEqual("persistent", manifest["lifecycle"])
        self.assertEqual(
            {
                "strategy": "subscription-first",
                "purchasePeriodMonths": 1,
                "autoRenew": True,
                "autoRenewPeriodMonths": 1,
                "payAsYouGoExceptions": ["ALB", "OSS", "SLS"],
            },
            manifest["billing"],
        )
        self.assertEqual("unattended", manifest["executionMode"])
        self.assertIs(True, manifest["slsEnabled"])
        self.assertIs(False, manifest["aoneEnabled"])
        self.assertIs(False, manifest["publicEgress"])
        self.assertEqual("admin", manifest["adminUsername"])
        self.assertEqual("x86_64", manifest["architecture"])
        self.assertEqual([], manifest["availabilityZones"])
        self.assertEqual("", manifest["resolvedInfrastructure"]["ecsImageId"])
        self.assertEqual({}, manifest["upgrade"])

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
        self.assertEqual("auto-wonder-prod", self.manifest["tags"]["Environment"])

    def test_recommended_runtime_matches_current_server_contract(self):
        self.assertEqual("0.2.130", self.manifest["recommendedRuntimeVersion"])


if __name__ == "__main__":
    unittest.main()
