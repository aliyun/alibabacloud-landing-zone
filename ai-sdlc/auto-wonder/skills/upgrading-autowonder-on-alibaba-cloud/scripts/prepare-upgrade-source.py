#!/usr/bin/env python3
"""Prepare source independently of the computer that created a deployment."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit


def git(*args):
    result = subprocess.run(['git', *args], capture_output=True, text=True)
    if result.returncode:
        raise ValueError('Source Git operation failed; check repository access and requested ref')
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--deployment-id', required=True)
    parser.add_argument('--repository-url')
    parser.add_argument('--target-ref')
    parser.add_argument('--allow-repository-change', action='store_true')
    args = parser.parse_args()
    try:
        spec = importlib.util.spec_from_file_location('source_policy', Path(__file__).with_name('upgrade_plan.py'))
        policy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(policy)
        data = json.loads(Path(args.manifest).read_text(encoding='utf-8-sig'))
        if data.get('deploymentId') != args.deployment_id or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', args.deployment_id):
            raise ValueError('Deployment identity mismatch; never select another deployment to match source')
        recorded = data.get('repositoryUrl', '')
        repository = args.repository_url or recorded
        if not repository or repository.startswith('-'):
            raise ValueError('Repository unavailable; supply the verified release repository explicitly')
        if urlsplit(repository).password or any(c in repository for c in '\r\n\x00'):
            raise ValueError('Repository URL must not contain credentials or control characters')
        changed = policy.normalize_url(repository) != policy.normalize_url(recorded)
        if changed and not args.allow_repository_change:
            policy.check_repository(repository, recorded)
        ref = args.target_ref
        if not ref:
            output = git('ls-remote', '--symref', repository, 'HEAD')
            match = re.search(r'^ref: refs/heads/(\S+)\s+HEAD$', output, re.M)
            if not match:
                raise ValueError('Repository default branch unavailable; supply a verified target ref')
            ref = match[1]
        git('check-ref-format', '--branch', ref)
        parent = Path(args.workspace).resolve() / '.operations-cache' / args.deployment_id
        parent.mkdir(parents=True, exist_ok=True)
        checkout = Path(tempfile.mkdtemp(prefix='source-', dir=parent))
        git('clone', '--no-checkout', '--', repository, str(checkout))
        git('-C', str(checkout), 'fetch', 'origin', f'refs/heads/{ref}:refs/remotes/origin/{ref}')
        commit = git('-C', str(checkout), 'rev-parse', f'refs/remotes/origin/{ref}^{{commit}}')
        git('-C', str(checkout), '-c', 'core.hooksPath=/dev/null', 'checkout', '--detach', commit)
        project = policy.resolve_source(checkout)
        print(json.dumps(dict(deploymentId=args.deployment_id, repositoryUrl=repository,
                              previousRepositoryUrl=recorded, repositoryChanged=changed,
                              targetRef=ref, targetCommit=commit, sourceDirectory=str(project))))
        return 0
    except (ValueError, OSError, RuntimeError, policy.PlanError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
