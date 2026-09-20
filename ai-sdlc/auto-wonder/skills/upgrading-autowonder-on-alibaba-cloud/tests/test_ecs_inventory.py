"""Fail closed on incomplete ECS responses; cloud transport alone is replaced."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import test_split_contract as fixtures

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


class InventoryEntrypointTests(unittest.TestCase):
    def test_malformed_tagged_row_cannot_be_discarded_before_exact_set_comparison(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = fixtures.UpgradeSkillSplitContractTests()
            manifest = fixture.write_target_manifest(root / 'manifest.json')
            binary = fixture.write_fake_aliyun(root)
            text = binary.read_text().replace(
                'print(json.dumps({"Instances": {"Instance": instances}}))',
                'print(json.dumps({"Instances": {"Instance": instances + ([None] if len(instances) > 1 else [])}}))')
            binary.write_text(text)
            result = subprocess.run(['bash', str(SCRIPTS / 'verify-deployment-targets.sh'),
                                     '--manifest', str(manifest)], text=True, capture_output=True,
                                    env={**os.environ, 'PATH': os.pathsep.join((str(root), str(Path(sys.executable).parent), os.environ['PATH']))}, timeout=30)
            self.assertNotEqual(0, result.returncode, 'Malformed ECS row was accepted as a verified inventory')
            self.assertNotIn('targetVerification', json.loads(manifest.read_text()).get('upgrade', {}))


class InventoryPolicyTests(unittest.TestCase):
    def page(self, response, state=None):
        result = subprocess.run([sys.executable, str(SCRIPTS / 'ecs_inventory.py'), 'page'],
                                input=json.dumps({'response': response, 'state': state}),
                                text=True, capture_output=True, timeout=5)
        return result

    def rows(self, start, count):
        return [{'InstanceId': f'i-{number}'} for number in range(start, start + count)]

    def accepted(self, response, state=None):
        result = self.page(response, state)
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def test_missing_total_requires_a_terminal_page_after_a_full_page(self):
        state = self.accepted({'Instances': {'Instance': self.rows(0, 100)}})
        self.assertFalse(state['done'])
        state = self.accepted({'Instances': {'Instance': [{'InstanceId': 'i-extra'}]}}, state)
        self.assertTrue(state['done'])
        self.assertEqual(101, len(state['ids']))
        self.assertIn('i-extra', state['ids'])

    def test_exact_full_page_without_total_requires_an_empty_page(self):
        state = self.accepted({'instances': self.rows(0, 100)})
        self.assertFalse(state['done'])
        state = self.accepted({'instances': []}, state)
        self.assertTrue(state['done'])
        self.assertEqual(100, len(state['ids']))

    def test_known_total_spans_pages_and_preserves_exact_ids(self):
        state = self.accepted({'Instances': {'Instance': self.rows(0, 100)}, 'TotalCount': 101})
        state = self.accepted({'instances': [{'instanceId': 'i-extra'}], 'totalCount': 101}, state)
        self.assertTrue(state['done'])
        self.assertEqual(101, len(state['ids']))

    def test_rejects_unknown_shapes_malformed_rows_and_ambiguous_ids(self):
        responses = [{}, {'Instances': {}}, {'Instances': {'Instance': None}},
                     {'Instances': {'Instance': {'InstanceId': 'i-a'}}},
                     {'instances': [None]}, {'instances': [{}]}, {'instances': ['i-a']},
                     {'instances': [{'InstanceId': ''}]}, {'instances': [{'InstanceId': 42}]},
                     {'instances': [{'InstanceId': ' i-a'}]},
                     {'instances': [{'InstanceId': 'i-a', 'instanceId': 'i-b'}]},
                     {'Instances': {'Instance': []}, 'instances': []}]
        for response in responses:
            with self.subTest(response=response):
                self.assertNotEqual(0, self.page(response).returncode)

    def test_rejects_duplicate_rows_and_repeated_pages(self):
        self.assertNotEqual(0, self.page({'instances': self.rows(0, 1) * 2}).returncode)
        state = self.accepted({'instances': self.rows(0, 100)})
        self.assertNotEqual(0, self.page({'instances': self.rows(0, 100)}, state).returncode)
        self.assertNotEqual(0, self.page({'instances': self.rows(99, 2)}, state).returncode)

    def test_rejects_invalid_total_and_short_page_total_mismatch(self):
        for total in (None, True, -1, '1', 1.5, 0, 2):
            with self.subTest(total=total):
                self.assertNotEqual(0, self.page({'instances': self.rows(0, 1), 'TotalCount': total}).returncode)
        self.assertNotEqual(0, self.page({'instances': [], 'TotalCount': 1}).returncode)

    def test_rejects_changed_or_disappearing_total_and_no_progress(self):
        state = self.accepted({'instances': self.rows(0, 100), 'TotalCount': 101})
        for response in ({'instances': self.rows(100, 1), 'TotalCount': 102},
                         {'instances': self.rows(100, 1)},
                         {'instances': [], 'TotalCount': 101}):
            with self.subTest(response=response):
                self.assertNotEqual(0, self.page(response, state).returncode)
        state = self.accepted({'instances': self.rows(0, 100)})
        self.assertNotEqual(0, self.page({'instances': self.rows(100, 1), 'TotalCount': 101}, state).returncode)

    def test_rejects_wrong_page_metadata_and_unsupported_continuation(self):
        for metadata in ({'PageNumber': 2}, {'PageSize': 10}, {'PageNumber': True},
                         {'NextToken': 'continuation'}, {'TotalCount': 1, 'totalCount': 2}):
            with self.subTest(metadata=metadata):
                self.assertNotEqual(0, self.page({'instances': self.rows(0, 1), **metadata}).returncode)
        self.assertNotEqual(0, self.page({'instances': self.rows(0, 101)}).returncode)

    def test_page_bound_fails_before_another_request_is_needed(self):
        state = {'ids': [], 'total': None, 'pages': 999, 'done': False}
        result = self.page({'instances': self.rows(0, 100)}, state)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('page limit', result.stderr)

    def test_native_utf8_pipeline_preserves_tag_values_under_non_utf8_locale(self):
        node = {'InstanceId': 'i-0', 'Tags': {'Tag': [{'TagKey': 'Environment', 'TagValue': '生产'}]}}
        result = subprocess.run([sys.executable, str(SCRIPTS / 'ecs_inventory.py'),
                                 'target', '--instance-id', 'i-0'],
                                input=json.dumps({'instances': [node]}, ensure_ascii=False).encode('utf-8'),
                                capture_output=True, env={**os.environ, 'PYTHONIOENCODING': 'ascii'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(node, json.loads(result.stdout))

    def test_target_requires_one_recognized_row_and_matching_identity(self):
        for response in ({'instances': self.rows(0, 2)}, {'instances': []},
                         {'instances': [{'InstanceId': 'i-other'}]}, {'unexpected': []}):
            with self.subTest(response=response):
                result = subprocess.run([sys.executable, str(SCRIPTS / 'ecs_inventory.py'),
                                         'target', '--instance-id', 'i-0'], input=json.dumps(response),
                                        text=True, capture_output=True)
                self.assertNotEqual(0, result.returncode)
        result = subprocess.run([sys.executable, str(SCRIPTS / 'ecs_inventory.py'),
                                 'target', '--instance-id', 'i-0'],
                                input=json.dumps({'instances': [{'instanceId': 'i-0'}]}),
                                text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({'InstanceId': 'i-0'}, json.loads(result.stdout))


if __name__ == '__main__':
    unittest.main()
