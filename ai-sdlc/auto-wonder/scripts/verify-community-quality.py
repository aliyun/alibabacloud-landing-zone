#!/usr/bin/env python3
"""Validate a Community product-acceptance manifest; never run product tests.

Only Python's standard library and local Git objects are used. Paths are explicit
inputs. Evidence paths are relative to --evidence-root, not the author's machine.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

CORE = {'static', 'configuration', 'json-protocol', 'runtime-delivery',
        'sls-disabled', 'sls-enabled', 'cleanup'}
CONDITIONAL = {'encryption', 'migration', 'feature-switches'}
REVIEWS = ('masterSemantics', 'communityBoundary', 'releaseReadiness')
LEVELS = {'static', 'unit', 'integration', 'api-integration', 'runtime-e2e'}
RUNTIME_CHECKS = {'executor_online', 'routing_matches', 'server_acked',
                  'server_running', 'dispatch_succeeded', 'downloaded_artifact_matches'}
SHA = re.compile(r'[0-9a-f]{40}')
DIGEST = re.compile(r'[0-9a-f]{64}')
VERSION = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?')


class InvalidEvidence(Exception):
    pass


def require(condition, message):
    if not condition:
        raise InvalidEvidence(message)


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def relative_path(value):
    require(nonempty(value) and '\\' not in value and ':' not in value,
            'evidence/input path must be portable and relative')
    path = PurePosixPath(value)
    require(not path.is_absolute() and '..' not in path.parts and value != '.',
            'evidence/input path must stay within its root')
    return path


def git(repo, *args):
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')
           or k in ('GIT_CONFIG_GLOBAL', 'GIT_CONFIG_NOSYSTEM')}
    env.update(GIT_NO_REPLACE_OBJECTS='1', GIT_NO_LAZY_FETCH='1',
               GIT_OPTIONAL_LOCKS='0', GIT_LITERAL_PATHSPECS='1')
    proc = subprocess.run(['git', '-C', str(repo), *args], env=env,
                          capture_output=True, text=True, timeout=25)
    require(proc.returncode == 0, 'Git object/ref unavailable for quality evidence')
    return proc.stdout.strip()


def tree(repo, commit):
    require(isinstance(commit, str) and SHA.fullmatch(commit), 'invalid evidence commit')
    return git(repo, 'rev-parse', commit + '^{tree}')


def proof(root, entry):
    require(isinstance(entry, dict), 'invalid evidence reference')
    relative = relative_path(entry.get('path'))
    path = (root / str(relative)).resolve()
    require(root in path.parents and path.is_file(), 'evidence file missing or outside root')
    expected = entry.get('sha256')
    require(isinstance(expected, str) and DIGEST.fullmatch(expected), 'evidence digest missing')
    data = path.read_bytes()
    require(data and hashlib.sha256(data).hexdigest() == expected, 'evidence hash mismatch or empty file')
    return data


def proofs(root, entries):
    require(isinstance(entries, list) and bool(entries), 'evidence references required')
    for entry in entries:
        proof(root, entry)


def check_input_binding(repo, item, final, final_tree):
    tested = item.get('testedCommit')
    if tree(repo, tested) == final_tree:
        return
    require(item['id'] != 'runtime-delivery', 'runtime must test the final candidate tree')
    inputs = item.get('inputs')
    require(nonempty(item.get('reuseReason')) and isinstance(inputs, list) and bool(inputs),
            'component reuse needs explicit unchanged inputs and reason')
    for path in inputs:
        relative_path(path)
        before = git(repo, 'ls-tree', tested, '--', path)
        after = git(repo, 'ls-tree', final, '--', path)
        require(before and before == after, 'component reuse inputs differ or are absent')


def source_recommendation(config):
    # Same restricted YAML contract as the native lifecycle parser; no PyYAML,
    # environment expansion, or imports/execution from the candidate checkout.
    parents, values, mappings = {}, [], set()
    target = ('autowonder', 'runtime', 'recommended-version')
    for raw in config.splitlines():
        if not raw.strip() or raw.lstrip().startswith('#'):
            continue
        match = re.match(r'^( *)([A-Za-z0-9-]+)\s*:\s*(.*?)\s*$', raw)
        if not match:
            continue
        indent, key, scalar = len(match.group(1)), match.group(2), match.group(3)
        parents = {level: name for level, name in parents.items() if level < indent}
        current = tuple(parents[level] for level in sorted(parents)) + (key,)
        scalar = scalar.split(' #', 1)[0].strip()
        if current == target:
            values.append(scalar.strip(chr(34) + chr(39)))
        if not scalar:
            require(not (current in (target[:1], target[:2]) and current in mappings),
                    'runtime source namespace duplicated')
            mappings.add(current)
            parents[indent] = key
    require(len(values) == 1, 'runtime source recommendation missing or ambiguous')
    value = values[0]
    placeholder = re.fullmatch(r'\$\{AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:([^}]+)\}', value)
    if placeholder:
        value = placeholder.group(1)
    require(VERSION.fullmatch(value), 'runtime source recommendation invalid')
    return value


def check_runtime(repo, root, manifest, final):
    report = json.loads(proof(root, manifest.get('runtimeEvidence')))
    require(isinstance(report, dict) and report.get('verdict') == 'PASS'
            and report.get('cleanup') is True, 'runtime native report did not pass with cleanup')
    config = git(repo, 'show', final + ':src/main/resources/application.yml')
    version = source_recommendation(config)
    require(report.get('sourceRuntimeVersion') == version, 'runtime source version mismatch')
    context = json.loads(proof(root, manifest.get('runtimeContext')))
    require(isinstance(context, dict) and isinstance(context.get('git'), dict),
            'runtime lifecycle context missing')
    require(context['git'].get('dirty') is False
            and tree(repo, context['git'].get('commit')) == tree(repo, final),
            'runtime lifecycle context belongs to a different or dirty tree')
    legacy_summary = manifest.get('e2e', {}).get('withRuntime')
    if legacy_summary is not None:
        require(isinstance(legacy_summary, dict) and legacy_summary.get('verdict') == 'PASS'
                and type(legacy_summary.get('exitCode')) is int and legacy_summary['exitCode'] == 0,
                'runtime current summary conflicts or uses an unsupported legacy shape')
    effective = report.get('runtimeVersion')
    require(isinstance(effective, str) and VERSION.fullmatch(effective), 'runtime version invalid')
    source = report.get('runtimeRecommendationSource')
    require(source in ('application.yml', 'lifecycle-app.env'), 'runtime recommendation provenance missing')
    require(source != 'application.yml' or effective == version, 'runtime effective version mismatch')
    checks = report.get('checks')
    require(isinstance(checks, list) and all(isinstance(c, str) for c in checks)
            and RUNTIME_CHECKS <= set(checks), 'runtime delivery assertions incomplete')
    require(isinstance(report.get('artifactSha256'), str)
            and DIGEST.fullmatch(report['artifactSha256']), 'runtime artifact download proof missing')
    return effective


def validate(repo, root, manifest, phase):
    require(isinstance(manifest, dict) and type(manifest.get('schemaVersion')) is int
            and manifest['schemaVersion'] == 1,
            'unsupported manifest schemaVersion')
    require(type(manifest.get('qualityContractVersion')) is int and manifest['qualityContractVersion'] == 1,
            'qualityContractVersion=1 required; old summary-only evidence is not upgraded automatically')
    require(type(manifest.get('cycle')) is int and manifest['cycle'] > 0,
            'cycle must be a positive integer')
    for field in ('executionFingerprintCounts', 'repairFingerprintCounts'):
        counts = manifest.get(field)
        require(isinstance(counts, dict) and all(nonempty(key) and type(value) is int and value >= 0
                                                for key, value in counts.items()),
                field + ' must contain nonnegative integer counts')
    keys = git(repo, 'config', '--name-only', '--list').lower().splitlines()
    require(not any(key == 'extensions.partialclone' or
                    (key.startswith('remote.') and key.endswith('.promisor')) for key in keys),
            'partial/promisor repositories are unsupported; use complete local Git objects')
    final = manifest.get('finalSourceSha')
    final_tree = tree(repo, final)
    require(manifest.get('acceptedTree') == final_tree
            and tree(repo, manifest.get('testedCommit')) == final_tree, 'accepted/tested tree mismatch')
    require(git(repo, 'rev-parse', 'HEAD') == final, 'HEAD differs from candidate')
    branch = manifest.get('branch')
    require(nonempty(branch) and branch not in ('master', 'community')
            and git(repo, 'branch', '--show-current') == branch, 'candidate branch mismatch or protected branch')
    require(not git(repo, 'status', '--porcelain', '--untracked-files=no'), 'tracked candidate files are dirty')
    require(type(manifest.get('openFindings')) is int and manifest['openFindings'] == 0,
            'open findings remain or count is missing')
    require(type(manifest.get('openRequiredCheckpoints')) is int
            and manifest['openRequiredCheckpoints'] == 0, 'required checkpoint remains or count is missing')
    require(manifest.get('staticGates', {}).get('verdict') == 'PASS'
            and manifest.get('e2e', {}).get('verdict') == 'PASS'
            and manifest.get('e2e', {}).get('cleanup') == 'PASS', 'summary quality/cleanup is not PASS')
    items = manifest.get('qualityChecks')
    require(isinstance(items, list) and all(isinstance(c, dict) and nonempty(c.get('id')) for c in items),
            'qualityChecks required')
    ids = [item['id'] for item in items]
    require(len(ids) == len(set(ids)), 'duplicate quality check id')
    missing = sorted((CORE | CONDITIONAL) - set(ids))
    require(not missing, 'missing quality checks: ' + ', '.join(missing))
    for item in items:
        label = item['id']
        status = item.get('status')
        if status == 'NOT_APPLICABLE':
            require(label in CONDITIONAL and nonempty(item.get('reason')),
                    label + ': NOT_APPLICABLE needs a conditional scope and reason')
        else:
            require(status == 'PASS' and type(item.get('exitCode')) is int and item['exitCode'] == 0,
                    label + ': required check is not PASS/exit0')
            require(item.get('level') in LEVELS, label + ': verification level missing')
            require(label != 'runtime-delivery' or item.get('level') == 'runtime-e2e',
                    label + ': unit/fixture cannot replace real runtime E2E')
        check_input_binding(repo, item, final, final_tree)
        proofs(root, item.get('evidence'))
    implementer = manifest.get('implementer')
    require(nonempty(implementer), 'review implementer identity required')
    reviews = manifest.get('reviewEvidence', {})
    for name in REVIEWS:
        entry = reviews.get(name, {})
        require(manifest.get('reviews', {}).get(name) == 'PASS'
                and entry.get('independent') is True and nonempty(entry.get('reviewer'))
                and entry['reviewer'] != implementer and nonempty(entry.get('executionRef'))
                and entry.get('candidateTree') == final_tree, 'review independence/identity evidence incomplete: ' + name)
        proofs(root, entry.get('evidence'))
    version = check_runtime(repo, root, manifest, final)
    if phase == 'submitted':
        require(manifest.get('remoteSourceSha') == final
                and git(repo, 'rev-parse', 'refs/remotes/origin/' + branch) == final,
                'submitted source tracking/remote receipt does not match candidate')
    return dict(verdict='PASS', qualityContractVersion=1, phase=phase,
                finalSourceSha=final, acceptedTree=final_tree, runtimeVersion=version,
                checks=len(items), scope='evidence and local Git consistency; not a product test run')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--evidence-root', required=True, type=Path)
    parser.add_argument('--phase', choices=('local', 'submitted'), default='local')
    args = parser.parse_args()
    try:
        report = validate(args.repo.resolve(), args.evidence_root.resolve(),
                          json.loads(args.manifest.read_text(encoding='utf-8')), args.phase)
    except (InvalidEvidence, OSError, ValueError, TypeError, AttributeError, subprocess.TimeoutExpired) as exc:
        # Avoid printing arbitrary file contents, Git stderr or secrets in malformed input.
        message = str(exc) if isinstance(exc, InvalidEvidence) else 'invalid or unreadable quality evidence'
        print(json.dumps(dict(verdict='FAIL', reason=message), ensure_ascii=False))
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
