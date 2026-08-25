# Phase 6: 交付质量验证(优化前后对比)

## 何时进入

Phase 5(应用)完成后,用户提供了重跑的新日志,且希望验证"效率提升是否压缩了交付质量"。

**前置条件:**
- 有两次相同或高度相似的工单执行:一次是优化前(基线),一次是优化后(验证)
- 两次执行的 capsule 目录可访问(从日志定位,或用户直接给路径)
- 优化后的执行确认已 dispatch.completed(不分析失败的 dispatch)

## 核心原则

**省下来的时间,必须全部来自非交付性活动。** 如果某个交付维度回退了,该优化点有问题。

非交付性活动(可安全消除):
- 重复编译/验证(下游不消费逐 commit 可编译)
- 环境搏斗(esbuild/npm 重试循环)
- 后台轮询/sleep(等已就绪的数据)
- 补救回合重定向(重建"我做到哪了")
- STREAM 叙述减少(因为不再做那些动作)

交付性活动(不可消除):
- 代码编辑本身
- 测试运行(自测步)
- 评论发布
- Evidence 写入
- Push / MR 创建
- Handoff

---

## 三维度对比方法

### 维度 1: 评论质量

**数据提取:**
```python
import json, re
def extract_comments(capsule_path):
    ev = [json.loads(l) for l in open(f"{capsule_path}/observability/events.jsonl")]
    comments = []
    for e in ev:
        if e['type'] != 'bash.call': continue
        inp = e['payload'].get('input')
        if isinstance(inp, str):
            try: inp = json.loads(inp)
            except: inp = {}
        if not isinstance(inp, dict): continue
        cmd = str(inp.get('command', ''))
        if '/comment' not in cmd or 'contentMd' not in cmd: continue
        m = re.search(r'"contentMd":"(.*?)"}', cmd, re.DOTALL)
        if m:
            comments.append(m.group(1).replace('\\n', '\n'))
    return comments
```

**评估标准:**

| 维度 | 如何判断 | 回退判定 |
|---|---|---|
| 信息完整度 | 需求理解、技术方案、影响范围、分支基线是否都覆盖 | 缺少任一核心字段 |
| 结构化程度 | 有无 Markdown 标题/列表/代码块 | 从结构化退化为散文 |
| 自测汇报字段数 | 按固定模板计数(如 10 字段) | 字段数减少 |
| 准确性 | commit hash/分支名/测试结论是否与实际一致 | 出现错误信息 |

### 维度 2: Evidence 产物

**数据提取:**
```bash
# 基线
find $OLD/artifacts/accepted -type f | sed "s|$OLD/artifacts/accepted/||" | sort > /tmp/old_evidence.txt
# 验证
find $NEW/artifacts/accepted -type f | sed "s|$NEW/artifacts/accepted/||" | sort > /tmp/new_evidence.txt
diff /tmp/old_evidence.txt /tmp/new_evidence.txt
```

**评估标准:**

| 维度 | 如何判断 | 回退判定 |
|---|---|---|
| 文件数 | accepted/evidence/ 下文件数量 | 新版 < 旧版 |
| 覆盖面 | 后端日志、前端日志、tsc、lint、总结 MD 是否齐全 | 缺少关键类别 |
| 内容实质 | test-summary.md 是否包含命令/结果/基线对照/结论 | 变成空壳或占位符 |
| handoff 完整性 | metadata.json + summary.md 是否存在 | 缺失 |

### 维度 3: 代码变更等价性(最关键)

**数据提取:**
```bash
# 对比 diff stat
cd $OLD/repos/<repo> && git diff --stat <base>..HEAD > /tmp/old_stat.txt
cd $NEW/workspace/repos/<repo> && git diff --stat <base>..HEAD > /tmp/new_stat.txt

# 文件集是否一致
diff <(cd $OLD/repos/<repo> && git diff --name-only <base>..HEAD | sort) \
     <(cd $NEW/workspace/repos/<repo> && git diff --name-only <base>..HEAD | sort)

# 逐文件行数对比
for f in <文件列表>; do
  a=$(cd $OLD/repos/<repo> && git show HEAD:"$f" | wc -l)
  b=$(cd $NEW/workspace/repos/<repo> && git show HEAD:"$f" | wc -l)
  printf "%-60s %6d %6d\n" "$f" "$a" "$b"
done

# 测试用例数
grep -c '@Test' / grep -c 'it(' / grep -c 'func Test'
```

**评估标准:**

| 维度 | 如何判断 | 回退判定 |
|---|---|---|
| 改动文件集 | git diff --name-only 是否完全一致 | 新版缺少文件 |
| 代码规模 | 各文件行数差异 ≤10% | 核心实现文件行数显著减少 |
| 测试用例数 | @Test / it() / func Test 计数一致 | 新版测试更少 |
| 核心方法存在性 | grep 关键方法签名 | 核心方法缺失 |
| 验收标准覆盖 | 逐条检查工单验收标准 | 任一条不满足 |
| 功能点完整性 | 对照工单描述的所有功能点 | 功能缺失或被简化 |

**注意:** 代码风格差异(如 JSX inline vs 提取变量、方法排列顺序不同)不算回退。判断依据是**功能等价性**,不是文本一致性。

---

## 输出模板

创建 `~/aw-diagnosis-{date}/quality-comparison.md`:

```markdown
# 交付质量前后对比 · {workitem_old} vs {workitem_new}

## 执行概况

| | 基线({workitem_old}) | 验证({workitem_new}) | 变化 |
|---|---|---|---|
| DEV 墙钟 | {old_time} | {new_time} | {change}% |
| 全链路墙钟 | {old_chain} | {new_chain} | {change}% |
| dispatch 结果 | completed | completed | — |

## 维度 1: 评论质量

| 评论类型 | 基线 | 验证 | 判定 |
|---|---|---|---|
| 需求分析评论 | {描述} | {描述} | 持平 / 更好 / 回退 |
| 自测/交付评论 | {描述} | {描述} | 持平 / 更好 / 回退 |
| 完成汇报(硬模板) | {N}/10 字段 | {N}/10 字段 | 持平 / 更好 / 回退 |

## 维度 2: Evidence 产物

| | 基线 | 验证 | 判定 |
|---|---|---|---|
| evidence/ 文件数 | {N} | {N} | 持平 / 更好 / 回退 |
| 关键类别覆盖 | {列举} | {列举} | 持平 / 更好 / 回退 |
| test-summary.md | {有/无,长度} | {有/无,长度} | 持平 / 更好 / 回退 |
| handoff/ | {有/无} | {有/无} | 持平 / 更好 / 回退 |

## 维度 3: 代码变更

| 指标 | 基线 | 验证 | 判定 |
|---|---|---|---|
| 改动文件数 | {N} | {N} | |
| insertions / deletions | +{N} / -{N} | +{N} / -{N} | |
| 核心实现文件行数 | {列表} | {列表} | |
| 测试用例数 | {N} | {N} | |
| 验收标准满足 | {N}/{total} | {N}/{total} | |
| 功能点覆盖 | {列举} | {列举} | |

### 验收标准逐条核对

| # | 验收标准 | 基线 | 验证 |
|---|---|---|---|
| 1 | {标准描述} | ✅/❌ | ✅/❌ |
| ... | ... | ... | ... |

## 总判定

**效率变化:** {old_time} → {new_time} ({change}%)

**质量变化:** {总判定:未压缩 / 部分回退(列出) / 显著回退}

**省下来的时间来源分解:**

| 来源 | 节省 | 是否交付性活动 |
|---|---|---|
| {编译消除} | {Xs} | 否 |
| {环境搏斗消除} | {Xs} | 否 |
| {后台轮询消除} | {Xs} | 否 |
| {STREAM 间接压缩} | {Xs} | 否 |
| {其他} | {Xs} | {是/否} |

**结论:** {一句话}
```

---

## 判定规则

### 优化成功(可宣布)
三个维度全部"持平"或"更好",且省下的时间全部来自非交付性活动。

### 优化有瑕疵(需记录但不回退)
某个维度有微小差异(如代码风格不同、方法排列顺序变了),但功能完全等价、测试数一致、验收标准全满足。在报告中标注差异但判定为"等价"。

### 优化需回退(质量回退)
出现以下任一情况:
- 核心方法缺失或功能被简化
- 测试用例数减少
- 验收标准有不满足项
- 评论中出现错误信息(如 commit hash 错误)
- Evidence 从有到无

→ 定位是哪条优化导致的(通常是约束写过紧),精确回退那一条,不全盘回退。

---

## 与 Phase 2/3 的关系

Phase 6 的发现可以反哺检测器:
- 如果发现某个优化导致 commit 粒度过大(agent 一次性提交所有文件)→ 调整 B9 约束的措辞
- 如果发现禁编译导致最终代码有编译错误 → 说明禁得太死,应改为"编码步不**主动**编译;测试步会统一验证"
- 如果 evidence 文件数下降 → 检查是否 gate 配置被意外改了

**Phase 6 不是每次都做** — 只在用户提供了前后两份日志(或两个工单的 capsule)且主动要求质量对比时才进入。
