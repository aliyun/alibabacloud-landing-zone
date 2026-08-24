# 跨步骤复用检测

## 方法

1. 从 `artifact.sealed` 事件获取每步的 sealed fileCount 和路径
2. 从下一步的 agent 事件中检查:是否有同名文件被 Write 或同样命令被 Bash
3. 如果是 → 标记为"重做了上一步的工作"

## 判据

- `accepted/` 目录里有文件 X → 下一步又 Write 了文件 X → 重做
- 上一步跑了 `vitest run src/features/sdlc` → 下一步又跑同一命令 → 重做

## 例外

- 下一步需要**验证**上一步的产出(如 QA 验证 DEV 的代码)→ 跑同类型测试是正当的
- 但 CR 跑测试不是正当的(CR 是评审,不是验证)
