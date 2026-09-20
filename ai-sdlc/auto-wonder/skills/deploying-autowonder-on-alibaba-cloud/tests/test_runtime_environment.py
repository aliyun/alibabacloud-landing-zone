import json
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


class RuntimeEnvironmentTests(unittest.TestCase):
    def test_system_tool_directory_cannot_shadow_selected_private_tools(self):
        spec = importlib.util.spec_from_file_location('runtime_path_review', SCRIPTS / 'tool_runtime.py')
        runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runtime)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            tools = root / 'skills/.autowonder-tools'
            system = root / 'system bin'
            system.mkdir()
            selected = {}
            for name, command in runtime.TOOL_COMMANDS.items():
                path = (system if name == 'jq' else tools / name / 'bin') / command
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture')
                path.chmod(0o700)
                selected[name] = str(path)
                shadow = system / command
                shadow.write_text('old system tool')
                shadow.chmod(0o700)
            with patch.object(runtime, 'resolve_tool', side_effect=lambda name, *args, **kwargs: selected[name]), \
                    patch.dict(os.environ, {'PATH': str(system)}):
                result = runtime.session_environment(tools)
            for name in ('python', 'maven', 'terraform', 'aliyun', 'ossutil'):
                self.assertEqual(shutil.which(runtime.TOOL_COMMANDS[name], path=result['env']['PATH']), selected[name])

    @unittest.skipUnless(shutil.which('bash'), 'Bash unavailable')
    def test_git_bash_dispatches_before_runtime_or_cloud_commands(self):
        with tempfile.TemporaryDirectory(prefix='win entry ') as temp:
            root = Path(temp)
            for name, body in {
                'uname': '#!/bin/sh\necho MINGW64_NT\n',
                'cygpath': '#!/bin/sh\nprintf "%s\\n" "$2"\n',
                'powershell.exe': '#!' + sys.executable + '\nimport json,sys; print(json.dumps(sys.argv[1:]))\n',
            }.items():
                path = root / name
                path.write_text(body)
                path.chmod(0o700)
            manifest = str(root / '中文 manifest.json')
            result = subprocess.run(['bash', str(SCRIPTS / 'bootstrap-control-host.sh'),
                '--manifest', manifest, '--region', 'cn-beijing'],
                env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']),
                capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            arguments = json.loads(result.stdout)
            self.assertIn(str(SCRIPTS / 'windows/bootstrap-control-host.ps1'), arguments)
            self.assertEqual(arguments[-4:], ['-Manifest', manifest, '-Region', 'cn-beijing'])

    @unittest.skipUnless(shutil.which('bash'), 'Bash unavailable')
    def test_selected_environment_is_literal_and_process_local(self):
        with tempfile.TemporaryDirectory(prefix='运行 空格 ') as temp:
            root = Path(temp)
            scripts = root / 'skills/deploying-autowonder-on-alibaba-cloud/scripts'
            scripts.mkdir(parents=True)
            shutil.copyfile(SCRIPTS / 'runtime-env.sh', scripts / 'runtime-env.sh')
            poison = root / 'must-not-execute'
            selected = {'AUTOWONDER_PYTHON': sys.executable,
                        'JAVA_HOME': str(root / ('jdk $(touch ' + str(poison) + ')')),
                        'PATH': str(root / 'tools bin') + os.pathsep + os.environ['PATH']}
            (scripts / 'tool_runtime.py').write_text('import json\nprint(' + repr(json.dumps({'env': selected})) + ')\n')
            (scripts / 'runtime-bootstrap.sh').write_text('#!/bin/sh\nprintf "%s\\n" "$FIXTURE_PYTHON"\n')
            environment = dict(os.environ, FIXTURE_PYTHON=sys.executable)
            environment.pop('AUTOWONDER_PYTHON', None)
            original = os.environ.get('JAVA_HOME')
            result = subprocess.run(['bash', '-c',
                'source "$1"; autowonder_runtime_environment; "$AUTOWONDER_PYTHON" -c '\
                "'import json,os; print(json.dumps({k:os.environ[k] for k in [\"AUTOWONDER_PYTHON\",\"JAVA_HOME\",\"PATH\"]}))'",
                'fixture', str(scripts / 'runtime-env.sh')], env=environment,
                capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), selected)
            self.assertFalse(poison.exists())
            self.assertEqual(os.environ.get('JAVA_HOME'), original)
