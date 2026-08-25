from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class TerraformAccelerationTest(unittest.TestCase):
    def test_posix_config_is_isolated_and_contains_official_mirror_rules(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            existing = root / ".terraformrc"
            existing.write_text("credentials {}\n", encoding="utf-8")
            bash = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"
            result = subprocess.run(
                [bash, str(ROOT / "scripts/configure-terraform-acceleration.sh"),
                 "--config-dir", str(root / "autowonder")],
                text=True, capture_output=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            config = Path(result.stdout.strip())
            self.assertEqual(root / "autowonder" / "terraform-init-acceleration.tfrc", config)
            self.assertEqual("credentials {}\n", existing.read_text(encoding="utf-8"))
            text = config.read_text(encoding="utf-8")
            self.assertIn('url = "https://mirrors.aliyun.com/terraform/"', text)
            for provider in ("registry.terraform.io/aliyun/alicloud",
                             "registry.terraform.io/hashicorp/alicloud"):
                self.assertIn(provider, text)
            self.assertIn("network_mirror", text)
            self.assertIn("direct", text)
            self.assertIn("include", text)
            self.assertIn("exclude", text)

    def test_runtime_routes_terraform_through_generated_config(self):
        stage = (ROOT / "scripts/terraform-stage.sh").read_text(encoding="utf-8")
        self.assertIn("configure-terraform-acceleration.sh", stage)
        self.assertIn("TF_CLI_CONFIG_FILE", stage)

    def test_windows_config_uses_tfrc_without_overwriting_global_config(self):
        script = (ROOT / "scripts/windows/configure-terraform-acceleration.ps1").read_text(encoding="utf-8")
        bootstrap = (ROOT / "scripts/windows/bootstrap-control-host.ps1").read_text(encoding="utf-8")
        self.assertIn("terraform-init-acceleration.tfrc", script)
        self.assertIn("TF_CLI_CONFIG_FILE", script)
        self.assertIn("mirrors.aliyun.com/terraform/", script)
        self.assertNotIn("terraform.rc'", script)
        self.assertIn("configure-terraform-acceleration.ps1", bootstrap)

    def test_skill_makes_acceleration_automatic_without_questionnaire_input(self):
        skill = (ROOT / "SKILL.md").read_text(encoding="utf-8")
        runbook = (ROOT / "references/operations-runbook.md").read_text(encoding="utf-8")
        combined = skill + runbook
        self.assertIn("Terraform init acceleration", combined)
        self.assertIn("background default", combined)
        self.assertIn("Do not ask the user", combined)
        self.assertIn("Windows", combined)
        self.assertIn("macOS", combined)
        self.assertIn("Linux", combined)


if __name__ == "__main__":
    unittest.main()
