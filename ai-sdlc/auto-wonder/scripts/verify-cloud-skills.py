#!/usr/bin/env python3
"""Run the two Skills' local fixture suites and report actual platform coverage.

No deployment is started. CLI deny stubs and isolated credentials protect the
test boundary; use an OS network sandbox as well when available. This runner
does not claim that a platform's native shells were tested merely by running
Python there. --strict treats every skipped test as incomplete.
"""
import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import time
import unittest


SKILLS = ('deploying-autowonder-on-alibaba-cloud', 'upgrading-autowonder-on-alibaba-cloud')


def verdict(results, strict=False):
    if not results or any(not item['tests'] or item['failures'] or item['errors'] for item in results):
        return 'failed'
    if any(item['skipped'] for item in results):
        return 'incomplete' if strict else 'passed-with-skips'
    return 'passed'


def case_id(test):
    # unittest subtest IDs embed parameter reprs, which may contain credentials.
    return getattr(test, 'test_case', test).id()


def worker(directory):
    started = time.monotonic()
    # Fixtures sometimes print subprocess output: only return test identifiers,
    # never arbitrary command output or assertion values in the summary.
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        suite = unittest.defaultTestLoader.discover(str(directory), pattern='test_*.py')
        result = unittest.TextTestRunner(stream=io.StringIO(), verbosity=0).run(suite)
    return {'tests': result.testsRun,
            'failures': [case_id(test) for test, _ in result.failures],
            'errors': [case_id(test) for test, _ in result.errors]
                      + [case_id(test) for test in result.unexpectedSuccesses],
            'skipped': [case_id(test) for test, _ in result.skipped],
            'seconds': round(time.monotonic() - started, 3)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--strict', action='store_true')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--worker', choices=SKILLS, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    if args.worker:
        print(json.dumps(worker(root / 'skills' / args.worker / 'tests')))
        return 0
    results = []
    with tempfile.TemporaryDirectory(prefix='aw-skill-tests-') as temp:
        private = Path(temp)
        bin_dir = private / 'bin'
        bin_dir.mkdir()
        for name in ('aliyun', 'ossutil'):
            stub = bin_dir / (name + '.cmd' if os.name == 'nt' else name)
            stub.write_text('@exit /b 97\n' if os.name == 'nt' else '#!/bin/sh\nexit 97\n')
            stub.chmod(0o700)
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(('ALICLOUD_', 'ALIBABA_CLOUD_', 'OSS_', 'TF_VAR_', 'TF_LOG', 'TF_CLI_ARGS'))}
        # Isolate the default credential location without pinning a config path:
        # individual tests must be able to supply their own fake HOME/profile.
        home = private / 'home'
        home.mkdir()
        env.update(PATH=str(bin_dir) + os.pathsep + env.get('PATH', ''),
                   HOME=str(home), USERPROFILE=str(home),
                   GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='core.hooksPath',
                   GIT_CONFIG_VALUE_0=os.devnull, PYTHONUTF8='1', PYTHONDONTWRITEBYTECODE='1')
        for skill in SKILLS:
            print('验证 ' + skill, file=sys.stderr, flush=True)
            command = [sys.executable, '-B', str(Path(__file__).resolve()), '--worker', skill]
            try:
                run = subprocess.run(command, env=env, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, timeout=1200, check=False)
                item = json.loads(run.stdout.decode('utf-8'))
                if run.returncode:
                    raise ValueError('worker failed')
            except (OSError, ValueError, subprocess.SubprocessError):
                item = {'tests': 0, 'failures': [], 'errors': ['suite-worker-failed'], 'skipped': []}
            item['skill'] = skill
            results.append(item)
    report = {'status': verdict(results, args.strict), 'platform': platform.system(),
              'architecture': platform.machine(), 'python': platform.python_version(),
              'scope': 'local-fixtures-only', 'cloudEndToEnd': 'not-run',
              'otherOperatingSystems': 'not-run', 'strict': args.strict, 'suites': results}
    output = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(output, encoding='utf-8')
    print(output, end='')
    return 0 if report['status'] in ('passed', 'passed-with-skips') else 1


if __name__ == '__main__':
    raise SystemExit(main())
