#!/usr/bin/env node
// Scripted executor protocol fixture: no provider CLI or model execution.
import { createHash, randomBytes } from 'node:crypto';
import { writeFile, lstat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

export class CheckFailure extends Error {}
const requireCheck = (condition, code) => { if (!condition) throw new CheckFailure(code); };
const delay = ms => new Promise(done => setTimeout(done, ms));
export const INJECTED_VALUE = '中文\n"quoted"\\path\n{"@type":"synthetic.Type","$ref":"$.fixture","123":2}';
export function validateFrame(wire, expectedEnvironment, resumed) {
  let frame;
  try { frame = JSON.parse(wire); } catch { throw new CheckFailure('WIRE_JSON_INVALID'); }
  requireCheck(frame?.type === 'CONVERSATION_TURN', 'WIRE_TYPE_INVALID');
  requireCheck(isDeepStrictEqual(frame.mcpSecrets, {}), 'WIRE_SECRET_OBJECT_MISMATCH');
  requireCheck(isDeepStrictEqual(frame.environmentVariables, expectedEnvironment), 'WIRE_ENVIRONMENT_MISMATCH');
  // Inspect actual objects, not strings: the injected value deliberately contains literal $ref text.
  const hasReference = value => value && typeof value === 'object'
    && (Object.hasOwn(value, '$ref') || Object.values(value).some(hasReference));
  requireCheck(!hasReference(frame), 'WIRE_REFERENCE_ALIAS');
  requireCheck(resumed ? frame.cliSessionRef === resumed : !frame.cliSessionRef, 'WIRE_RESUME_MISMATCH');
  requireCheck(Number.isSafeInteger(frame.turnId), 'WIRE_TURN_ID_INVALID');
  return frame;
}
export async function waitFor(probe, code, timeoutMs = 30000) {
  const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) {
    const result = await probe();
    if (result) return result;
    const remaining = deadline - performance.now();
    if (remaining > 0) await delay(Math.min(300, remaining));
  }
  throw new CheckFailure(code);
}
export async function cleanupOwned(closeSocket, deleteExecutor, evidence) {
  let failed = false;
  try { await closeSocket(); } catch { failed = true; }
  // The server-side executor must be removed even if WebSocket shutdown fails.
  try { await deleteExecutor(); } catch { failed = true; }
  evidence.cleanup = !failed;
  if (failed) {
    evidence.verdict = 'FAIL';
    evidence.failureKind ??= 'EXECUTOR_CLEANUP_FAILED';
  }
}
export async function run(base, state) {
  const evidence = { verdict: 'FAIL', fixture: 'scripted-executor', checks: [], cleanup: false };
  let ws, heartbeat, executorId, token;
  const call = async (method, path, body) => {
    try {
      const response = await fetch(base + path, { method, redirect: 'error',
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) });
      const result = await response.json();
      requireCheck(response.ok && result.success === true, 'API_BUSINESS_FAILURE');
      return result.data;
    } catch (error) { throw error instanceof CheckFailure ? error : new CheckFailure('API_TRANSPORT_FAILURE'); }
  };
  try {
    const url = new URL(base);
    requireCheck(url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port
      && !url.username && !url.password && url.pathname === '/' && !url.search && !url.hash,
      'LOCAL_BASE_URL_REQUIRED');
    base = url.origin;
    const stat = await lstat(state);
    requireCheck(stat.isDirectory() && !stat.isSymbolicLink(), 'LIFECYCLE_STATE_REQUIRED');
    const suffix = randomBytes(8).toString('hex');
    const username = `json-e2e-${suffix}`, password = randomBytes(24).toString('hex');
    await call('POST', '/api/auth/register', { username, password, email: `${username}@example.invalid`, nickname: 'JSON E2E' });
    token = (await call('POST', '/api/auth/login', { username, password })).accessToken;
    const workspace = await call('POST', '/api/workspaces', { name: `JSON E2E ${suffix}` });
    evidence.workspaceId = workspace.id;
    token = (await call('POST', `/api/workspaces/${workspace.id}/switch`)).accessToken;
    const config = { roleName: 'JSON Fixture', roleCode: 'JSON_E2E',
      businessBackground: 'Bounded protocol serialization check.', responsibilities: 'Return scripted proof.', evolutionMode: 'MANUAL' };
    const agent = await call('POST', '/api/agents', { ...config, name: `JSON E2E ${suffix}` });
    evidence.agentId = agent.id;
    const agentPath = `/api/agents/${agent.id}`;
    const publish = async () => {
      await call('PUT', `${agentPath}/config`, config);
      await call('POST', `${agentPath}/submit`, {});
      await call('POST', `${agentPath}/approve`, { comment: 'Disposable JSON regression' });
      const current = await call('GET', agentPath);
      requireCheck(current.status === 'ONLINE' && current.onlineVersionId, 'AGENT_VERSION_MISSING');
      return current.onlineVersionId;
    };
    evidence.initialAgentVersionId = await publish();
    const executor = await call('POST', `${agentPath}/executors`, { name: 'scripted-json-fixture', clientKind: 'QODER_CLI' });
    executorId = evidence.executorId = executor.id;
    const frames = [];
    let socketFailure = false;
    ws = new WebSocket(`${base.replace('http:', 'ws:')}/ws/executor?executorId=${executor.id}&token=${encodeURIComponent(executor.token)}`);
    ws.addEventListener('error', () => { socketFailure = true; });
    ws.addEventListener('message', event => {
      try {
        const frame = JSON.parse(String(event.data));
        if (frame.type === 'CONVERSATION_TURN') frames.push(String(event.data));
      } catch { socketFailure = true; }
    });
    await waitFor(() => { requireCheck(!socketFailure, 'SOCKET_FAILED'); return ws.readyState === WebSocket.OPEN; }, 'SOCKET_OPEN_TIMEOUT');
    const beat = () => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({
      type: 'HEARTBEAT', version: '0.3.3', protocolFeatures: ['AGENT_ENVIRONMENT_VARIABLES_V1'] })); };
    beat();
    heartbeat = setInterval(beat, 5000);
    await waitFor(async () => (await call('GET', `${agentPath}/executors`)).some(row => row.id === executorId && row.status === 'ONLINE'), 'EXECUTOR_ONLINE_TIMEOUT');
    evidence.checks.push('scripted_executor_online');
    const workitem = await call('POST', '/api/workitems', { workType: 'TASK', title: 'JSON regression', contentMd: 'Protocol fixture only.', priority: 2 });
    evidence.workitemId = workitem.id;
    const conversationBase = `/api/workitems/${workitem.id}/clarification-conversations`;
    const exercise = async (environment, label, versionId) => {
      const conversation = await call('POST', conversationBase, { agentId: agent.id });
      evidence[`${label}ConversationId`] = conversation.id;
      const sessionId = `json-fixture-${conversation.id}`;
      for (let attempt = 0; attempt < 2; attempt++) {
        await call('POST', `${conversationBase}/${conversation.id}/turns`, { content: 'Synthetic JSON regression', clientMessageId: randomBytes(16).toString('hex') });
        const wire = await waitFor(() => { requireCheck(!socketFailure && ws.readyState === WebSocket.OPEN, 'SOCKET_FAILED'); return frames.shift(); }, 'CONVERSATION_FRAME_TIMEOUT');
        const frame = validateFrame(wire, environment, attempt ? sessionId : null);
        requireCheck(frame.conversationId === conversation.id && frame.executorId === executorId
          && frame.agentVersionId === versionId, 'WIRE_ROUTING_MISMATCH');
        ws.send(JSON.stringify({ type: 'CONVERSATION_TURN_ACK', conversationId: conversation.id,
          turnId: frame.turnId, status: 'SUCCESS', replyMarkdown: INJECTED_VALUE, sessionId }));
        const completed = await waitFor(async () => {
          const detail = await call('GET', `${conversationBase}/${conversation.id}`);
          return detail.cliSessionRef === sessionId
            && detail.turns?.some(turn => turn.id === frame.turnId && turn.status === 'SUCCESS') && detail;
        }, 'CONVERSATION_ACK_TIMEOUT');
        const replies = completed.turns.filter(turn => turn.direction === 'OUT');
        requireCheck(replies.length === attempt + 1
          && replies.every(turn => turn.status === 'SUCCESS' && turn.content === INJECTED_VALUE),
          'HTTP_REPLY_ROUNDTRIP_MISMATCH');
        evidence.checks.push(`${label}_${attempt ? 'resumed' : 'initial'}_plain_json`,
          `${label}_${attempt ? 'resumed' : 'initial'}_reply_roundtrip`);
      }
    };
    await exercise({}, 'empty', evidence.initialAgentVersionId);
    const environment = await call('POST', '/api/environment-variables', { name: 'JSON_REGRESSION_VALUE', value: INJECTED_VALUE, description: 'Synthetic regression value' });
    evidence.environmentVariableId = environment.id;
    requireCheck((await call('GET', `/api/environment-variables/${environment.id}/value`)).value === INJECTED_VALUE, 'HTTP_ENVIRONMENT_ROUNDTRIP_MISMATCH');
    evidence.checks.push('http_environment_roundtrip');
    await call('POST', `${agentPath}/environment-variables/${environment.id}`, {});
    evidence.populatedAgentVersionId = await publish();
    requireCheck(evidence.populatedAgentVersionId !== evidence.initialAgentVersionId, 'AGENT_VERSION_NOT_ADVANCED');
    await exercise({ JSON_REGRESSION_VALUE: INJECTED_VALUE }, 'populated', evidence.populatedAgentVersionId);
    evidence.valueSha256 = createHash('sha256').update(INJECTED_VALUE).digest('hex');
    evidence.verdict = 'PASS';
  } catch (error) {
    evidence.failureKind = error instanceof CheckFailure ? error.message : 'JSON_REGRESSION_INTERNAL';
  } finally {
    clearInterval(heartbeat);
    await cleanupOwned(async () => {
      if (ws && ws.readyState !== WebSocket.CLOSED) {
        ws.close(1000, 'fixture complete');
        await waitFor(() => ws.readyState === WebSocket.CLOSED, 'SOCKET_CLOSE_TIMEOUT');
      }
    }, async () => {
      if (executorId) await call('DELETE', `/api/executors/${executorId}`);
    }, evidence);
    await writeFile(resolve(state, 'json-regression.json'), JSON.stringify(evidence, null, 2) + '\n', { mode: 0o600 });
  }
  return evidence;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = process.argv.slice(2);
  const base = args[args.indexOf('--base-url') + 1], state = args[args.indexOf('--state-dir') + 1];
  try {
    requireCheck(args.length === 4 && args.includes('--base-url') && args.includes('--state-dir'), 'ARGUMENTS_REQUIRED');
    const evidence = await run(base, state);
    console.log(`JSON_REGRESSION=${evidence.verdict}`);
    if (evidence.failureKind) console.log(`JSON_FAILURE_KIND=${evidence.failureKind}`);
    process.exitCode = evidence.verdict === 'PASS' ? 0 : 1;
  } catch { console.log('JSON_REGRESSION=FAIL\nJSON_FAILURE_KIND=JSON_REGRESSION_SETUP_FAILED'); process.exitCode = 1; }
}
