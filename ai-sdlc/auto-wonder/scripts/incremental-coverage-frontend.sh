#!/usr/bin/env bash
# 增量代码行/分支覆盖度检查（前端，vitest + coverage-v8）。
# 用法：
#   ./scripts/incremental-coverage-frontend.sh [BASE_SHA] [HEAD_SHA]
# 默认对比 origin/master..HEAD，只统计本分支新增行（语句/分支）的覆盖情况。
# 门槛：行 >= 80%，分支 >= 80%。
# 依赖：@vitest/coverage-v8@0.34.6（--no-save 安装，不污染 lockfile）。
set -euo pipefail

BASE_SHA="${1:-origin/master}"
HEAD_SHA="${2:-HEAD}"
MIN_LINE="${AUTOWONDER_COV_MIN_LINE:-80}"
MIN_BRANCH="${AUTOWONDER_COV_MIN_BRANCH:-80}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
frontend="${repo_root}/frontend"
cd "${frontend}"

if ! git rev-parse --verify -q "${BASE_SHA}" >/dev/null; then
  echo "BASE_SHA '${BASE_SHA}' 不存在（若要用 origin/master 请先 git fetch）" >&2
  exit 2
fi

# vitest coverage provider 未随 lockfile 分发，按需临时安装（不写 package.json/lock）。
if [[ ! -d node_modules/@vitest/coverage-v8 ]]; then
  echo "== 安装 @vitest/coverage-v8（--no-save，不改 lockfile）=="
  npm install --no-save --no-package-lock @vitest/coverage-v8@0.34.6 >/dev/null
fi
# 本机 node_modules 只有 darwin-x64 的 esbuild 二进制时补 arm64（同样不落 lockfile）。
if [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]] \
   && [[ ! -d node_modules/@esbuild/darwin-arm64 ]]; then
  npm install --no-save --no-package-lock @esbuild/darwin-arm64@0.18.20 >/dev/null
fi

echo "== 前端增量覆盖度：${BASE_SHA}..${HEAD_SHA} =="
rm -rf .cov-inc
npx vitest run --coverage.enabled --coverage.reporter=json \
  --coverage.reportsDirectory=.cov-inc \
  src/features/auth/ src/features/settings/ src/shared/hooks/ src/shared/

python3 - "${BASE_SHA}" "${HEAD_SHA}" "${MIN_LINE}" "${MIN_BRANCH}" <<'PY'
import json, subprocess, re, sys

base, head, min_line, min_branch = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])

final = json.load(open('.cov-inc/coverage-final.json'))

def added_lines(path):
    out = subprocess.run(['git', 'diff', '-U0', base, head, '--', path],
                         capture_output=True, text=True).stdout
    lines, cur = set(), 0
    for l in out.splitlines():
        m = re.match(r'@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@', l)
        if m:
            cur = int(m.group(1)); continue
        if l.startswith('+') and not l.startswith('+++'):
            lines.add(cur); cur += 1
        elif l.startswith('-'):
            pass
        else:
            cur += 1
    return lines

def key_for(src):
    for k in final:
        if k.endswith(src):
            return k
    return None

out = subprocess.run(['git', 'diff', '--name-only', base, head],
                     capture_output=True, text=True).stdout
# coverage-final.json 的 key 是绝对路径，以 frontend/src/... 结尾，可直接 endswith 匹配；
# 但 git diff 的 pathspec 需相对当前目录（脚本已 cd 到 frontend/）。
targets = [p for p in out.splitlines()
           if p.startswith('frontend/src/') and not p.endswith(('.test.ts', '.test.tsx'))]

tc = ta = bc = ba = 0
for path in targets:
    k = key_for(path)
    if not k:
        continue
    added = added_lines(path.removeprefix('frontend/'))
    if not added:
        continue
    fc = final[k]
    s = fc.get('s', {}); m = fc.get('statementMap', {})
    for sid, c in s.items():
        if m[sid]['start']['line'] in added:
            ta += 1
            if c > 0:
                tc += 1
    b = fc.get('b', {}); bm = fc.get('branchMap', {})
    for bid, counts in b.items():
        if bm[bid]['loc']['start']['line'] in added:
            for c in counts:
                ba += 1
                if c > 0:
                    bc += 1

line_pct = tc / ta * 100 if ta else 100.0
branch_pct = bc / ba * 100 if ba else 100.0
print(f"增量文件数: {len(targets)}")
print(f"增量行覆盖:   {line_pct:.1f}% ({tc}/{ta})")
print(f"增量分支覆盖: {branch_pct:.1f}% ({bc}/{ba})")
ok = line_pct >= min_line and branch_pct >= min_branch
print(f"门槛: 行 >= {min_line:.0f}% / 分支 >= {min_branch:.0f}% → {'PASS' if ok else 'FAIL'}")
sys.exit(0 if ok else 1)
PY
