import { describe, it, expect } from 'vitest';
import { buildTimeline } from './timeline';
import type { StreamedEvent } from './hooks';

const ev = (eventSeq: number, eventType: string, payload: unknown): StreamedEvent =>
  ({ turnId: 1, eventSeq, eventType, payload } as StreamedEvent);

describe('buildTimeline', () => {
  // F1：事件可能乱序到达（分片重组、重连补拉），时间线必须按 eventSeq 排。
  it('orders nodes by eventSeq regardless of arrival order', () => {
    const nodes = buildTimeline([
      ev(3, 'text', { content: 'c' }),
      ev(1, 'text', { content: 'a' }),
      ev(2, 'thinking', { content: 'b' }),
    ]);
    expect(nodes.map((n) => n.kind)).toEqual(['text', 'thinking', 'text']);
    expect(nodes[0].text).toBe('a');
    expect(nodes[2].text).toBe('c');
  });

  it('is a pure function: does not mutate the input array order', () => {
    const events = [ev(2, 'text', { content: 'b' }), ev(1, 'text', { content: 'a' })];
    buildTimeline(events);
    expect(events.map((e) => e.eventSeq)).toEqual([2, 1]);
  });

  // F2：chunk 是 token 级的，不合并会产生成百上千个节点。
  it('merges adjacent same-kind text chunks into one node', () => {
    const nodes = buildTimeline([
      ev(1, 'text', { content: 'Hello' }),
      ev(2, 'text', { content: ' world' }),
      ev(3, 'text', { content: '!' }),
    ]);
    expect(nodes).toHaveLength(1);
    expect(nodes[0].text).toBe('Hello world!');
    expect(nodes[0].eventSeq).toBe(1);
  });

  it('drops empty text chunks instead of emitting blank nodes', () => {
    const nodes = buildTimeline([ev(1, 'text', { content: '' }), ev(2, 'text', null)]);
    expect(nodes).toHaveLength(0);
  });

  // F3：这是本次重构的核心 —— 思考与动作必须按真实时序穿插，
  // 而不是「一坨思考 + 一堆工具」。
  it('interleaves thinking and tool nodes in real order', () => {
    const nodes = buildTimeline([
      ev(1, 'thinking', { content: 'let me look' }),
      ev(2, 'tool_use', { tool: 'Read', callId: 'c1' }),
      ev(3, 'thinking', { content: 'now I know' }),
      ev(4, 'tool_use', { tool: 'Bash', callId: 'c2' }),
    ]);
    expect(nodes.map((n) => n.kind)).toEqual(['thinking', 'tool', 'thinking', 'tool']);
  });

  // F4：tool_use 与 tool_result 是同一次调用的两个阶段，必须归并
  // 成一个节点并原地更新状态，否则界面上会出现两条记录。
  it('merges tool_use and tool_result by callId', () => {
    const nodes = buildTimeline([
      ev(1, 'tool_use', { tool: 'Bash', callId: 'c1', input: { command: 'ls' } }),
      ev(2, 'tool_result', { tool: 'Bash', callId: 'c1', status: 'completed', output: 'ok' }),
    ]);
    expect(nodes).toHaveLength(1);
    expect(nodes[0].kind).toBe('tool');
    expect(nodes[0].tool?.status).toBe('completed');
    expect(nodes[0].tool?.output).toBe('ok');
    expect(nodes[0].tool?.input).toContain('"command"');
  });

  it('marks a tool node as running until its result arrives', () => {
    const nodes = buildTimeline([ev(1, 'tool_use', { tool: 'Bash', callId: 'c1' })]);
    expect(nodes[0].tool?.status).toBe('running');
    expect(nodes[0].tool?.input).toBeUndefined();
  });

  // 重连时起始帧可能已经滚出窗口，只有结果帧到达也必须能建节点。
  it('creates a tool node from a result that has no preceding tool_use', () => {
    const nodes = buildTimeline([
      ev(9, 'tool_result', { tool: 'Edit', callId: 'lost', output: 'done' }),
    ]);
    expect(nodes).toHaveLength(1);
    expect(nodes[0].kind).toBe('tool');
    expect(nodes[0].tool?.tool).toBe('Edit');
    expect(nodes[0].tool?.status).toBe('completed');
  });

  it('falls back to a per-event key when callId is missing', () => {
    const nodes = buildTimeline([
      ev(1, 'tool_use', { tool: 'A' }),
      ev(2, 'tool_use', { tool: 'B' }),
    ]);
    expect(nodes).toHaveLength(2);
    expect(nodes[0].tool?.tool).toBe('A');
  });

  it('applies acp tool metadata on both use and result frames', () => {
    const nodes = buildTimeline([
      ev(1, 'tool_use', {
        tool: 'Edit',
        callId: 'c1',
        data: { kind: 'edit', title: '改写 README', locations: [{ path: 'README.md', line: 3 }] },
      }),
      ev(2, 'tool_use', { tool: 'Edit', callId: 'c1', data: { title: '改写 README.md' } }),
      ev(3, 'tool_result', {
        callId: 'c1',
        status: 'completed',
        data: {
          diffs: [{ path: 'README.md', oldText: 'a', newText: 'b' }],
          terminalOutput: '$ npm test',
        },
      }),
    ]);
    expect(nodes).toHaveLength(1);
    expect(nodes[0].tool?.toolKind).toBe('edit');
    expect(nodes[0].tool?.title).toBe('改写 README.md');
    expect(nodes[0].tool?.locations?.[0].path).toBe('README.md');
    expect(nodes[0].tool?.diffs?.[0].newText).toBe('b');
    expect(nodes[0].tool?.terminalOutput).toBe('$ npm test');
  });

  // F7：plan 是全量替换语义，只保留末次快照。
  it('keeps only the latest plan snapshot', () => {
    const nodes = buildTimeline([
      ev(1, 'acp_plan', { data: { entries: [{ content: 'a', priority: 'medium', status: 'pending' }] } }),
      ev(2, 'acp_plan', { data: { entries: [{ content: 'a', priority: 'medium', status: 'completed' }] } }),
    ]);
    const plans = nodes.filter((n) => n.kind === 'plan');
    expect(plans).toHaveLength(1);
    expect(plans[0].plan?.entries[0].status).toBe('completed');
  });

  // 快照节点在时间线上原地更新，不因刷新而跳到末尾。
  it('updates the snapshot node in place', () => {
    const nodes = buildTimeline([
      ev(1, 'acp_plan', { data: { entries: [] } }),
      ev(2, 'text', { content: 'hi' }),
      ev(3, 'acp_plan', { data: { entries: [{ content: 'b', priority: 'high', status: 'pending' }] } }),
    ]);
    expect(nodes.map((n) => n.kind)).toEqual(['plan', 'text']);
    expect(nodes[0].plan?.entries).toHaveLength(1);
  });

  it('keeps only the latest available-commands snapshot', () => {
    const nodes = buildTimeline([
      ev(1, 'acp_commands', { data: { availableCommands: [{ name: 'quest' }] } }),
      ev(2, 'acp_commands', {
        data: { availableCommands: [{ name: 'quest', description: 'd', input: { hint: 'h' } }, { name: 'spec' }] },
      }),
    ]);
    const commands = nodes.filter((n) => n.kind === 'commands');
    expect(commands).toHaveLength(1);
    expect(commands[0].commands?.availableCommands.map((c) => c.name)).toEqual(['quest', 'spec']);
  });

  it('tolerates snapshot frames without data', () => {
    const nodes = buildTimeline([ev(1, 'acp_plan', {}), ev(2, 'acp_commands', {})]);
    expect(nodes[0].plan?.entries).toEqual([]);
    expect(nodes[1].commands?.availableCommands).toEqual([]);
  });

  // F15：卡片的打开与解决按 requestId 归并成一个节点。
  it('merges elicitation open and resolved by requestId', () => {
    const nodes = buildTimeline([
      ev(1, 'acp_elicitation', { data: { requestId: 'r1', mode: 'form', message: 'pick', requestedSchema: {} } }),
      ev(2, 'acp_elicitation_resolved', { data: { requestId: 'r1', action: 'accept', content: { q0: 'A' } } }),
    ]);
    const cards = nodes.filter((n) => n.kind === 'elicitation');
    expect(cards).toHaveLength(1);
    expect(cards[0].elicitation?.action).toBe('accept');
    expect(cards[0].elicitation?.resolved).toBe(true);
    expect(cards[0].elicitation?.content).toEqual({ q0: 'A' });
    expect(cards[0].elicitation?.message).toBe('pick');
  });

  it('leaves an unanswered card unresolved', () => {
    const nodes = buildTimeline([
      ev(1, 'acp_elicitation', { data: { requestId: 'r1', mode: 'form', message: 'pick', toolCallId: 't1' } }),
    ]);
    expect(nodes[0].elicitation?.resolved).toBe(false);
    expect(nodes[0].elicitation?.toolCallId).toBe('t1');
  });

  // 重连补拉可能只拿到 resolved 帧；卡片仍要以已解决态出现在时间线上。
  it('creates a resolved card even when the open frame is missing', () => {
    const nodes = buildTimeline([
      ev(5, 'acp_elicitation_resolved', { data: { requestId: 'r9', action: 'decline' } }),
    ]);
    expect(nodes).toHaveLength(1);
    expect(nodes[0].elicitation?.requestId).toBe('r9');
    expect(nodes[0].elicitation?.resolved).toBe(true);
    expect(nodes[0].elicitation?.action).toBe('decline');
  });

  it('re-applies a duplicated open frame without clearing resolution', () => {
    const nodes = buildTimeline([
      ev(1, 'acp_elicitation', { data: { requestId: 'r1', message: 'first' } }),
      ev(2, 'acp_elicitation_resolved', { data: { requestId: 'r1', action: 'accept', content: {} } }),
      ev(3, 'acp_elicitation', { data: { requestId: 'r1', message: 'second' } }),
    ]);
    expect(nodes).toHaveLength(1);
    expect(nodes[0].elicitation?.message).toBe('second');
    expect(nodes[0].elicitation?.resolved).toBe(true);
  });

  it('keeps status, error and log events as their own nodes', () => {
    const nodes = buildTimeline([
      ev(1, 'log', { content: 'starting' }),
      ev(2, 'error', { content: 'boom' }),
      ev(3, 'status', { status: 'completed' }),
      ev(4, 'error', { output: 'from output' }),
      ev(5, 'log', {}),
    ]);
    expect(nodes.map((n) => n.kind)).toEqual(['log', 'error', 'status', 'error', 'log']);
    expect(nodes[0].text).toBe('starting');
    expect(nodes[2].status).toBe('completed');
    expect(nodes[3].text).toBe('from output');
    expect(nodes[4].text).toBe('');
  });

  // F5：未知事件类型必须保留为兜底节点。静默丢弃会让新增的 Agent
  // 能力在前端「消失」，且没人知道发生了什么。
  it('keeps unknown event types as fallback nodes', () => {
    const nodes = buildTimeline([ev(1, 'acp_future_thing', { data: { x: 1 } })]);
    expect(nodes).toHaveLength(1);
    expect(nodes[0].kind).toBe('unknown');
    expect(nodes[0].raw).toBeDefined();
    expect(nodes[0].raw?.eventType).toBe('acp_future_thing');
    expect(nodes[0].raw?.payload).toEqual({ data: { x: 1 } });
  });

  it('gives every node a stable unique id', () => {
    const nodes = buildTimeline([
      ev(1, 'text', { content: 'a' }),
      ev(2, 'tool_use', { tool: 'Bash', callId: 'c1' }),
      ev(3, 'acp_plan', { data: { entries: [] } }),
      ev(4, 'acp_elicitation', { data: { requestId: 'r1' } }),
      ev(5, 'weird', null),
    ]);
    const ids = nodes.map((n) => n.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('returns an empty timeline for no events', () => {
    expect(buildTimeline([])).toEqual([]);
  });
});

describe('buildTimeline 跨轮次归并', () => {
  const evt = (turnId: number, eventSeq: number, eventType: string, payload: unknown) =>
    ({ turnId, eventSeq, eventType, payload } as StreamedEvent);

  // eventSeq 每轮从 1 重新递增，纯按 eventSeq 排会把不同轮次的事件交错。
  it('orders by (turnId, eventSeq) so turns never interleave', () => {
    const nodes = buildTimeline([
      evt(2, 1, 'text', { content: '第二轮先说' }),
      evt(1, 2, 'text', { content: '第一轮后说' }),
      evt(1, 1, 'thinking', { content: '第一轮先想' }),
    ]);
    expect(nodes.map((n) => [n.turnId, n.text])).toEqual([
      [1, '第一轮先想'],
      [1, '第一轮后说'],
      [2, '第二轮先说'],
    ]);
  });

  it('never merges text chunks that belong to different turns', () => {
    const nodes = buildTimeline([
      evt(1, 1, 'text', { content: '第一轮回答' }),
      evt(2, 1, 'text', { content: '第二轮回答' }),
    ]);
    expect(nodes).toHaveLength(2);
    expect(nodes[0].text).toBe('第一轮回答');
    expect(nodes[1].text).toBe('第二轮回答');
  });

  // callId 由执行器每轮独立生成，跨轮次撞号会把两次工具调用叠成一个节点。
  it('keeps same-callId tool calls of different turns as separate nodes', () => {
    const nodes = buildTimeline([
      evt(1, 1, 'tool_use', { tool: 'Bash', callId: 'call_1' }),
      evt(1, 2, 'tool_result', { tool: 'Bash', callId: 'call_1', status: 'completed', output: '第一轮产出' }),
      evt(2, 1, 'tool_use', { tool: 'Read', callId: 'call_1' }),
    ]);
    expect(nodes).toHaveLength(2);
    expect(nodes[0].tool?.output).toBe('第一轮产出');
    expect(nodes[1].tool?.tool).toBe('Read');
    expect(nodes[1].tool?.output).toBeUndefined();
  });

  it('keeps same-requestId elicitations of different turns as separate cards', () => {
    const nodes = buildTimeline([
      evt(1, 1, 'acp_elicitation', { data: { requestId: 'req-1', message: '第一轮提问' } }),
      evt(1, 2, 'acp_elicitation_resolved', { data: { requestId: 'req-1', action: 'accept' } }),
      evt(2, 1, 'acp_elicitation', { data: { requestId: 'req-1', message: '第二轮提问' } }),
    ]);
    expect(nodes).toHaveLength(2);
    expect(nodes[0].elicitation?.resolved).toBe(true);
    expect(nodes[1].elicitation?.message).toBe('第二轮提问');
    expect(nodes[1].elicitation?.resolved).toBe(false);
  });

  it('keeps one plan and commands snapshot per turn', () => {
    const nodes = buildTimeline([
      evt(1, 1, 'acp_plan', { data: { entries: [{ content: 'A', priority: 'low', status: 'pending' }] } }),
      evt(1, 2, 'acp_plan', { data: { entries: [{ content: 'A', priority: 'low', status: 'completed' }] } }),
      evt(2, 1, 'acp_plan', { data: { entries: [{ content: 'B', priority: 'low', status: 'pending' }] } }),
      evt(1, 3, 'acp_commands', { data: { availableCommands: [{ name: 'quest' }] } }),
      evt(2, 2, 'acp_commands', { data: { availableCommands: [{ name: 'commit' }] } }),
    ]);
    const plans = nodes.filter((n) => n.kind === 'plan');
    const commands = nodes.filter((n) => n.kind === 'commands');
    expect(plans).toHaveLength(2);
    expect(plans[0].plan?.entries[0].status).toBe('completed');
    expect(plans[1].plan?.entries[0].content).toBe('B');
    expect(commands).toHaveLength(2);
    expect(commands[1].commands?.availableCommands[0].name).toBe('commit');
  });
});

describe('dispatch attempt isolation', () => {
  it('does not merge different attempts sharing tool ids or sequence numbers', () => {
    const events = [
      { ...ev(1, 'tool_use', { callId: 'same', tool: 'first' }), dispatchAttempt: 1 },
      { ...ev(1, 'tool_use', { callId: 'same', tool: 'second' }), dispatchAttempt: 2 },
    ];
    const nodes = buildTimeline(events);
    expect(nodes.map(n => n.tool?.tool)).toEqual(['first', 'second']);
    expect(new Set(nodes.map(n => n.id)).size).toBe(2);
  });
});
