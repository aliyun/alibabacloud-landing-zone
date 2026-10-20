#!/usr/bin/env python3
"""Read-only three-way Git tree gate. Exit 0 MATCH, 1 MISMATCH, 2 ERROR.

Compares committed objects, not checkout files. Requires Git and Python 3;
never fetches, builds, starts services or executes project hooks. A MATCH
proves tree identity, not test completion or cloud/environment compatibility.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


SUBTREE = 'ai-sdlc/auto-wonder'


def git(repo, *args):
    env = {k: v for k, v in os.environ.items() if k not in (
        'GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_OBJECT_DIRECTORY',
        'GIT_ALTERNATE_OBJECT_DIRECTORIES')}
    env['GIT_NO_REPLACE_OBJECTS'] = '1'
    env['GIT_NO_LAZY_FETCH'] = '1'
    result = subprocess.run(['git', '-C', str(repo), *args], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if result.returncode:
        # Do not copy arbitrary Git stderr (paths/configuration) into public evidence.
        raise ValueError('Git {} failed (exit {}); check repository, objects and base ancestry'
                         .format(args[0], result.returncode))
    return result.stdout


def commit(repo, value):
    if not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', value):
        raise ValueError('Use full immutable commit IDs, not branch names or abbreviations')
    if git(repo, 'cat-file', '-t', value).strip() != b'commit':
        raise ValueError('Input object must be a commit')
    return value


def tree(repo, sha, prefix=None):
    spec = sha + (':' + prefix if prefix else '^{tree}')
    tree_id = git(repo, 'rev-parse', '--verify', spec).decode().strip()
    if git(repo, 'cat-file', '-t', tree_id).strip() != b'tree':
        raise ValueError('Expected a tree at ' + spec)
    entries = {}
    for record in git(repo, 'ls-tree', '-r', '-z', '--full-tree', tree_id).split(b'\0'):
        if record:
            metadata, path = record.split(b'\t', 1)
            mode, kind, oid = metadata.split(b' ')
            entries[path] = (mode.decode(), kind.decode(), oid.decode())
    return {'commit': sha, 'tree': tree_id, 'entries': len(entries)}, entries


def display(path):
    return path.decode('utf-8', errors='surrogateescape')


def compare(left, right):
    differences = []
    for path in sorted(left.keys() | right.keys()):
        if left.get(path) != right.get(path):
            differences.append({'path': display(path),
                                'expected': left.get(path), 'actual': right.get(path)})
    return {'differenceCount': len(differences), 'differences': differences[:50],
            'truncated': len(differences) > 50}


def verify(args):
    # Older Git versions ignore GIT_NO_LAZY_FETCH. Inspect config before any
    # object lookup so a missing promisor object cannot start an implicit fetch.
    for repo in (args.internal_repo, args.github_repo):
        keys = git(repo, 'config', '--name-only', '--list').lower().splitlines()
        if any(k == b'extensions.partialclone' or
               (k.startswith(b'remote.') and k.endswith(b'.promisor')) for k in keys):
            raise ValueError('Partial/promisor repositories are not supported; use complete local clones')
    accepted = commit(args.internal_repo, args.accepted_commit)
    community = commit(args.internal_repo, args.community_commit)
    public = commit(args.github_repo, args.github_commit)
    base = commit(args.github_repo, args.github_base)
    if len({len(x) for x in (accepted, community, public, base)}) != 1:
        raise ValueError('Repositories must use the same Git object format')
    git(args.github_repo, 'merge-base', '--is-ancestor', base, public)
    a, ae = tree(args.internal_repo, accepted)
    c, ce = tree(args.internal_repo, community)
    g, ge = tree(args.github_repo, public, SUBTREE)
    comparisons = {'acceptedToCommunity': compare(ae, ce),
                   'communityToGithub': compare(ce, ge)}
    outside = [display(p) for p in git(args.github_repo, 'diff-tree',
               '--no-commit-id', '--name-only', '--no-renames', '-r', '-z',
               base, public).split(b'\0') if p and not p.startswith((SUBTREE + '/').encode())]
    match = a['tree'] == c['tree'] == g['tree'] and not outside
    return {'schemaVersion': 1, 'verdict': 'MATCH' if match else 'MISMATCH',
            'scope': 'committed Git objects only; no test execution',
            'githubBase': base, 'githubSubtree': SUBTREE,
            'trees': {'accepted': a, 'community': c, 'github': g},
            'comparisons': comparisons, 'outsideChanges': outside}, 0 if match else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--internal-repo', required=True, type=Path)
    parser.add_argument('--github-repo', required=True, type=Path)
    parser.add_argument('--accepted-commit', required=True)
    parser.add_argument('--community-commit', required=True)
    parser.add_argument('--github-commit', required=True)
    parser.add_argument('--github-base', required=True)
    parser.add_argument('--output', type=Path, help='Also atomically write the JSON report here')
    args = parser.parse_args()
    try:
        report, code = verify(args)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        report, code = {'schemaVersion': 1, 'verdict': 'ERROR', 'error': str(error)}, 2
    text = json.dumps(report, ensure_ascii=True, indent=2) + '\n'
    if args.output:
        temp = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8',
                                             dir=args.output.parent, delete=False) as handle:
                temp = handle.name
                handle.write(text)
            os.replace(temp, args.output)
        except OSError:
            if temp:
                Path(temp).unlink(missing_ok=True)
            text = json.dumps({'schemaVersion': 1, 'verdict': 'ERROR',
                               'error': 'Could not write report; do not reuse an older report'}) + '\n'
            code = 2
    sys.stdout.write(text)
    return code


if __name__ == '__main__':
    sys.exit(main())
