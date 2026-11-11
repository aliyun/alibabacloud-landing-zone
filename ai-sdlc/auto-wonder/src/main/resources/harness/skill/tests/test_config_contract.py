import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("harness_validator", ROOT / "scripts/validate_config.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class ConfigContractTest(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT / "harness-config.json").read_text())

    def test_export_and_standalone_skill_packages(self):
        self.assertEqual(validator.validate(self.data), 1)
        template = self.data[0]
        self.assertEqual(len(template["agents"]), 5)
        self.assertEqual(len(template["skills"]), 8)
        self.assertEqual(sum(len(a["skillRefs"]) for a in template["agents"]), 10)
        self.assertEqual(sum(len(a["sdlc"]["steps"]) for a in template["agents"] if a["sdlc"]), 23)

    def test_dangling_binding_rejected(self):
        self.data[0]["agents"][0]["skillRefs"].append("missing-skill")
        with self.assertRaisesRegex(ValueError, "unresolved skillRefs"):
            validator.validate(self.data)

    def test_path_traversal_and_missing_reference_rejected(self):
        for path in ("../escape.md", "/tmp/escape.md"):
            data = copy.deepcopy(self.data)
            data[0]["skills"][0]["files"][path] = "unsafe"
            with self.assertRaisesRegex(ValueError, "unsafe Skill path"):
                validator.validate(data)
        files = self.data[0]["skills"][0]["files"]
        files["SKILL.md"] += "\n[missing](missing.md)\n"
        with self.assertRaisesRegex(ValueError, "missing relative file"):
            validator.validate(self.data)

    def test_instance_requires_all_variables_resolved(self):
        with self.assertRaisesRegex(ValueError, "unresolved"):
            validator.validate(self.data, instantiated=True)
        def replace(value):
            if isinstance(value, str):
                return validator.TOKEN.sub("项目配置说明", value)
            if isinstance(value, list):
                return [replace(v) for v in value]
            if isinstance(value, dict):
                return {k: v if k == "variables" else replace(v) for k, v in value.items()}
            return value
        self.assertEqual(validator.validate(replace(self.data), instantiated=True), 1)

    def test_undeclared_variable_and_bad_order_rejected(self):
        data = copy.deepcopy(self.data)
        data[0]["variables"].pop()
        with self.assertRaisesRegex(ValueError, "variable declarations"):
            validator.validate(data)
        steps = self.data[0]["agents"][0]["sdlc"]["steps"]
        steps[1]["order"] = steps[0]["order"]
        with self.assertRaisesRegex(ValueError, "strictly increase"):
            validator.validate(self.data)

    def test_multiple_templates_with_identical_shared_skills(self):
        other = copy.deepcopy(self.data[0])
        other["template"]["name"] += "副本"
        other["squad"]["name"] += "副本"
        other = json.loads(json.dumps(other, ensure_ascii=False).replace("AW_AUTO_", "AW_AUTO_SECOND_"))
        self.assertEqual(validator.validate(self.data + [other]), 2)
        other["skills"][0]["files"]["SOURCE.md"] += "changed"
        with self.assertRaisesRegex(ValueError, "conflicting cross-template"):
            validator.validate(self.data + [other])


if __name__ == "__main__":
    unittest.main()
