#!/usr/bin/env bash
# 增量代码行/分支覆盖度检查（后端，JaCoCo）。
# 用法：
#   ./scripts/incremental-coverage-backend.sh [BASE_SHA] [HEAD_SHA]
# 默认对比 origin/master..HEAD，只统计本分支新增行的覆盖情况。
# 门槛：行 >= 80%，分支 >= 80%。
set -euo pipefail

BASE_SHA="${1:-origin/master}"
HEAD_SHA="${2:-HEAD}"
MIN_LINE="${AUTOWONDER_COV_MIN_LINE:-80}"
MIN_BRANCH="${AUTOWONDER_COV_MIN_BRANCH:-80}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
cd "${repo_root}"

# 覆盖率相关测试类：本分支新增/修改的模块对应的全部测试。
# 新增模块时请同步维护此列表。
TEST_CLASSES='AccessRequestServiceTest,WorkspaceControllerTest,WorkspaceAccessRequestSchemaContractTest,AccessRequestDaoSqlTest,WorkspaceMemberDaoSqlTest,ImNotificationFormatterTest,ImNotificationWorkerTest,WorkspaceAccessRequestedListenerTest,WorkspaceAccessReviewedListenerTest,InAppWorkspaceAccessRequestedListenerTest,InAppWorkspaceAccessReviewedListenerTest,WorkspaceAccessNotifyTextTest,MapperStatementContractTest,WorkspaceAccessAnnotationCoverageTest'

if ! git rev-parse --verify -q "${BASE_SHA}" >/dev/null; then
  echo "BASE_SHA '${BASE_SHA}' 不存在（若要用 origin/master 请先 git fetch）" >&2
  exit 2
fi

echo "== 后端增量覆盖度：${BASE_SHA}..${HEAD_SHA} =="
mvn -q org.jacoco:jacoco-maven-plugin:0.8.11:prepare-agent test \
  org.jacoco:jacoco-maven-plugin:0.8.11:report \
  -Dtest="${TEST_CLASSES}" \
  -DskipGitCommitId=true -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

python3 - "${BASE_SHA}" "${HEAD_SHA}" "${MIN_LINE}" "${MIN_BRANCH}" <<'PY'
import subprocess, re, sys, xml.etree.ElementTree as ET

base, head, min_line, min_branch = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])

tree = ET.parse('target/site/jacoco/jacoco.xml')
root = tree.getroot()
files = {}
for pkg in root.iter('package'):
    for sf in pkg.iter('sourcefile'):
        for ln in sf.iter('line'):
            files.setdefault((pkg.get('name'), sf.get('name')), []).append(
                (int(ln.get('nr')), int(ln.get('mi')), int(ln.get('ci')),
                 int(ln.get('mb')), int(ln.get('cb'))))

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

targets = {}
out = subprocess.run(['git', 'diff', '--name-only', base, head],
                     capture_output=True, text=True).stdout
for path in out.splitlines():
    if path.startswith('src/main/java/') and path.endswith('.java'):
        targets[path.split('/')[-1]] = path

tl = tc = ml = mc = 0
for name, path in targets.items():
    added = added_lines(path)
    cov = {}
    for (pkg, sf), rows in files.items():
        if sf == name:
            cov = {nr: (mi, ci, mb, cb) for nr, mi, ci, mb, cb in rows}
    if not cov:
        continue
    for nr in added:
        if nr not in cov:
            continue
        mi, ci, mb, cb = cov[nr]
        tl += mi + ci; ml += mi; tc += mb + cb; mc += mb

line_pct = (tl - ml) / tl * 100 if tl else 100.0
branch_pct = (tc - mc) / tc * 100 if tc else 100.0
print(f"增量文件数: {len(targets)}")
print(f"增量行覆盖:   {line_pct:.1f}% ({tl - ml}/{tl})")
print(f"增量分支覆盖: {branch_pct:.1f}% ({tc - mc}/{tc})")
ok = line_pct >= min_line and branch_pct >= min_branch
print(f"门槛: 行 >= {min_line:.0f}% / 分支 >= {min_branch:.0f}% → {'PASS' if ok else 'FAIL'}")
sys.exit(0 if ok else 1)
PY
