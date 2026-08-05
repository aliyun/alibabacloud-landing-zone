import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TERRAFORM_FILE = ROOT / "assets" / "terraform" / "main.tf"
TFVARS_FILE = ROOT / "assets" / "terraform" / "terraform.tfvars.example"


class TerraformContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.terraform = TERRAFORM_FILE.read_text(encoding="utf-8")

    def variable_block(self, name):
        match = re.search(
            rf'variable "{re.escape(name)}"\s*\{{(?P<body>.*?)\n\}}',
            self.terraform,
            re.DOTALL,
        )
        self.assertIsNotNone(match, f"missing variable {name}")
        return match.group("body")

    def test_forbids_public_ssh_nat_eip_and_broad_iam(self):
        for forbidden in [
            'cidr_ip = "0.0.0.0/0"',
            'port_range = "22/22"',
            "alicloud_eip",
            "alicloud_nat_gateway",
            'internet_max_bandwidth_out = 1',
            '"ecs:*"',
            '"nlb:*"',
            '"rds:*"',
            '"kvstore:*"',
            'default     = "1052513758643380"',
        ]:
            self.assertNotIn(forbidden, self.terraform)

    def test_declares_validated_inputs_and_required_sensitive_secrets(self):
        for name in [
            "region",
            "environment",
            "deployment_id",
            "zone_a_id",
            "zone_b_id",
            "public_source_cidrs",
            "common_tags",
            "lifecycle_mode",
            "ecs_password",
            "rds_password",
            "redis_password",
        ]:
            self.variable_block(name)

        region = self.variable_block("region")
        for preset in [
            "cn-zhangjiakou",
            "cn-hangzhou",
            "cn-shanghai",
            "cn-beijing",
        ]:
            self.assertIn(preset, region)

        for name in ["ecs_password", "rds_password", "redis_password"]:
            body = self.variable_block(name)
            self.assertRegex(body, r"sensitive\s*=\s*true")
            self.assertNotRegex(body, r"(?m)^\s*default\s*=")
            self.assertIn("length(var.", body)
            self.assertRegex(body, r"<=\s*32")

    def test_builds_two_zone_ha_data_and_compute_topology(self):
        self.assertRegex(self.terraform, r"for_each\s*=\s*local\.ecs_nodes")
        self.assertIn("zone_a = {", self.terraform)
        self.assertIn("zone_b = {", self.terraform)
        self.assertRegex(self.terraform, r"zone\s*=\s*var\.zone_a_id")
        self.assertRegex(self.terraform, r"zone\s*=\s*var\.zone_b_id")
        self.assertRegex(self.terraform, r"internet_max_bandwidth_out\s*=\s*0")
        self.assertRegex(self.terraform, r'instance_type\s*=\s*var\.rds_instance_type')
        self.assertRegex(self.terraform, r'category\s*=\s*var\.rds_category')
        self.assertIn('zone_id_slave_a', self.terraform)
        self.assertIn('secondary_zone_id', self.terraform)
        self.assertRegex(self.variable_block("rds_category"), r"HighAvailability|cluster")
        redis_class = self.variable_block("redis_instance_class")
        self.assertIn("(shard|master)", redis_class)
        self.assertIn("redis.shard.small.ce", redis_class)
        self.assertRegex(self.terraform, r'host_name\s*=\s*"autowonder-\$\{replace\(each\.key, "_", "-"\)\}"')

    def test_exposes_nlb_port_80_to_backend_7001(self):
        self.assertIn('address_type       = "Internet"', self.terraform)
        self.assertIn("listener_port        = 80", self.terraform)
        self.assertNotIn("listener_port        = 443", self.terraform)
        self.assertNotIn("listener_port        = 7001", self.terraform)
        self.assertIn("server_group_port = 7001", self.terraform)
        self.assertIn("zone_mappings", self.terraform)

    def test_creates_mandatory_private_oss_and_three_sls_destinations(self):
        self.assertIn('resource "alicloud_oss_bucket" "package"', self.terraform)
        self.assertIn('resource "alicloud_oss_bucket" "artifact"', self.terraform)
        self.assertIn('resource "alicloud_oss_bucket_acl" "package"', self.terraform)
        self.assertIn('resource "alicloud_oss_bucket_acl" "artifact"', self.terraform)
        for name in ["system", "business", "metrics"]:
            self.assertIn(f'resource "alicloud_log_store" "{name}"', self.terraform)
        self.assertEqual(self.terraform.count('telemetry_type        = "Metrics"'), 1)
        self.assertIn('resource "alicloud_log_store_index" "system"', self.terraform)
        self.assertIn('resource "alicloud_log_store_index" "business"', self.terraform)

    def test_redis_backup_is_managed_on_instance_without_deprecated_resource(self):
        self.assertNotIn('resource "alicloud_kvstore_backup_policy"', self.terraform)
        block = re.search(r'resource "alicloud_kvstore_instance" "main"\s*\{(.*?)\n\}', self.terraform, re.DOTALL)
        self.assertIsNotNone(block)
        self.assertIn("backup_period", block.group(1))
        self.assertIn('backup_time', block.group(1))

    def test_runtime_outputs_are_deterministic_and_private(self):
        self.assertRegex(self.terraform, r'(?s)output "redis".*?port\s*=\s*6379')
        self.assertIn('control_endpoint = "oss-${var.region}.aliyuncs.com"', self.terraform)
        self.assertIn('runtime_endpoint = "oss-${var.region}-internal.aliyuncs.com"', self.terraform)
        self.assertIn('control_endpoint = "${var.region}.log.aliyuncs.com"', self.terraform)
        self.assertIn('runtime_endpoint = "${var.region}-intranet.log.aliyuncs.com"', self.terraform)

    def test_scopes_single_application_identity_to_created_oss_and_sls(self):
        self.assertEqual(self.terraform.count('resource "alicloud_ram_user"'), 1)
        self.assertEqual(self.terraform.count('resource "alicloud_ram_access_key"'), 1)
        self.assertNotRegex(self.terraform, r'(?i)"Resource"\s*:\s*"\*"')
        for reference in [
            "alicloud_oss_bucket.package.bucket",
            "alicloud_oss_bucket.artifact.bucket",
            "alicloud_log_project.main.project_name",
            "alicloud_log_store.system.logstore_name",
            "alicloud_log_store.business.logstore_name",
            "alicloud_log_store.metrics.logstore_name",
        ]:
            self.assertIn(reference, self.terraform)

    def test_system_tags_override_custom_and_lifecycle_is_explicit(self):
        self.assertIn("merge(var.common_tags, local.system_tags)", self.terraform)
        for key in ["Project", "Environment", "DeploymentId", "ManagedBy", "Topology"]:
            self.assertRegex(self.terraform, rf"{key}\s*=")
        lifecycle = self.variable_block("lifecycle_mode")
        self.assertIn("persistent", lifecycle)
        self.assertIn("temporary", lifecycle)
        self.assertIn("deletion_protection", self.terraform)
        self.assertIn("backup_retention_period", self.terraform)

    def test_example_contains_no_secrets(self):
        text = TFVARS_FILE.read_text(encoding="utf-8")
        self.assertIn('region              = "cn-beijing"', text)
        self.assertIn("TF_VAR_ecs_password", text)
        self.assertNotRegex(text, r'(?m)^\s*(ecs|rds|redis)_password\s*=')


if __name__ == "__main__":
    unittest.main()
