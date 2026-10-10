import test from 'node:test';
import assert from 'node:assert/strict';
import { validateFrame, INJECTED_VALUE, cleanupOwned, waitFor } from './json_regression.mjs';
const wire = overrides => JSON.stringify({ type: 'CONVERSATION_TURN', turnId: 1,
  mcpSecrets: {}, environmentVariables: {}, ...overrides });
test('accepts literal empty maps and checks resumed session', () => {
  validateFrame(wire({}), {}, null);
  validateFrame(wire({ cliSessionRef: 'resumed' }), {}, 'resumed');
  assert.throws(() => validateFrame(wire({}), {}, 'resumed'), /WIRE_RESUME_MISMATCH/);
});
test('historical shared-map alias is rejected', () => {
  assert.throws(() => validateFrame(wire({ environmentVariables: { $ref: '$.mcpSecrets' } }), {}, null), /WIRE_ENVIRONMENT_MISMATCH/);
  assert.throws(() => validateFrame(wire({ other: { $ref: '$.mcpSecrets' } }), {}, null), /WIRE_REFERENCE_ALIAS/);
});
test('quoted numeric keys and reference/type strings remain literal data', () => {
  const environmentVariables = { JSON_REGRESSION_VALUE: INJECTED_VALUE };
  const result = validateFrame(wire({ environmentVariables }), environmentVariables, null);
  assert.equal(result.environmentVariables.JSON_REGRESSION_VALUE, INJECTED_VALUE);
  assert.throws(() => validateFrame('{"123":2,broken}', {}, null), /WIRE_JSON_INVALID/);
});

test('socket shutdown failure still deletes executor and preserves failure', async () => {
  for (const failureKind of [undefined, 'WIRE_ENVIRONMENT_MISMATCH']) {
    const evidence = { verdict: failureKind ? 'FAIL' : 'PASS', failureKind };
    let deleted = false;
    await cleanupOwned(async () => { throw new Error('synthetic close failure'); },
      async () => { deleted = true; }, evidence);
    assert.equal(deleted, true);
    assert.equal(evidence.cleanup, false);
    assert.equal(evidence.verdict, 'FAIL');
    assert.equal(evidence.failureKind, failureKind ?? 'EXECUTOR_CLEANUP_FAILED');
  }
});
test('executor deletion failure fails cleanup after successful socket close', async () => {
  const evidence = { verdict: 'PASS' };
  await cleanupOwned(async () => {}, async () => { throw new Error('synthetic delete failure'); }, evidence);
  assert.equal(evidence.cleanup, false);
  assert.equal(evidence.verdict, 'FAIL');
  assert.equal(evidence.failureKind, 'EXECUTOR_CLEANUP_FAILED');
});
test('slow probe exhausts wall-clock deadline without another attempt', async () => {
  let probes = 0;
  await assert.rejects(waitFor(async () => {
    probes++;
    await new Promise(done => setTimeout(done, 25));
    return false;
  }, 'FIXTURE_TIMEOUT', 10), /FIXTURE_TIMEOUT/);
  assert.equal(probes, 1);
});
