# Phase 0: 环境自检

## 检查 MCP

```bash
# 尝试调用 autowonder MCP 列出 workspace
curl -s "<MCP_URL>" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | head -1
```

如果失败,提示用户:

> autowonder MCP 未连接。请按你所用客户端(Claude Code / Qoder / Codex / Cursor 等)的方式配置好 autowonder MCP,具体配置方法参考对应客户端文档。
>
> MCP 的作用:①获取 SDLC/Agent 的当前配置 ②获取工单评论 ③优化完成后一键应用修改。
> 没有 MCP 也能完成分析(日志里有足够信息),但优化方案只能手动应用。

## 检查日志

询问用户日志路径。如果用户说"还没跑",给出启动指引:

> 请按以下步骤启动执行器并收集日志:
>
> 1. 到平台**执行器页面**,对链路上每个数字人的执行器点「启动命令」,复制 **debug 模式命令**(日志文件名平台已自动生成好)
> 2. 用复制到的命令重启对应的客户端执行器,每个执行器一个终端
> 3. 在 AutoWonder 管理界面派发一个工单给你的小队
> 4. 等待全链路完成(最后一个数字人交接给真人)
> 5. 告诉我各个日志文件的路径

## 验证日志可读

```bash
# 确认日志包含 dispatch 事件
grep -c "event dispatch.assigned" ~/aw-dev.log
grep -c "event dispatch.completed" ~/aw-dev.log
```

如果 `dispatch.assigned` 有但 `dispatch.completed` 没有 → 任务还没跑完或跑失败了。

## 就绪条件

- [ ] 至少一个日志文件可读且包含完整的 dispatch 生命周期
- [ ] (可选)MCP 连接成功

满足后进入 Phase 1。
