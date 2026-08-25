import { describe, it, expect } from 'vitest';
import { buildDebugCommand, buildStartupCommand, debugLogFileName } from './startupCommand';

describe('debugLogFileName', () => {
  it.each([
    ['QODER_CLI', 'qoder'],
    ['QODER_CN_CLI', 'qodercn'],
    ['CLAUDE_CODE', 'claude'],
    ['CODEX_CLI', 'codex'],
    ['CURSOR_CLI', 'cursor'],
  ])('maps %s to provider %s', (clientKind, provider) => {
    expect(debugLogFileName(clientKind, 10000, new Date(2026, 7, 24, 15, 30, 42)))
      .toBe(`aw-${provider}-10000-260824-15-30-42.log`);
  });

  it('falls back to claude for an unknown client kind', () => {
    expect(debugLogFileName('SOMETHING_NEW', 7, new Date(2026, 7, 24, 15, 30, 42)))
      .toBe('aw-claude-7-260824-15-30-42.log');
  });

  it('zero-pads every timestamp component including a single-digit year', () => {
    expect(debugLogFileName('CLAUDE_CODE', 1, new Date(2006, 0, 5, 9, 8, 7)))
      .toBe('aw-claude-1-060105-09-08-07.log');
  });
});

describe('buildDebugCommand', () => {
  const NOW = new Date(2026, 7, 24, 15, 30, 42);
  const base = buildStartupCommand(
    'exec_test_token', 10000, 'CLAUDE_CODE', 'platform',
    'https://daily.auto-wonder.example.com/api/mcp', '0.2.138',
  );

  it('appends --debug and a bash tee redirect', () => {
    expect(buildDebugCommand(base, 'CLAUDE_CODE', 10000, 'bash', NOW)).toBe(
      'npx -y autowonder@0.2.138 connect --ws-url wss://daily.auto-wonder.example.com/ws/executor'
      + ' --token exec_test_token --executor-id 10000 --provider claude --memory-mode platform'
      + ' --debug 2>&1 | tee ~/aw-claude-10000-260824-15-30-42.log',
    );
  });

  it('appends --debug and a PowerShell Tee-Object redirect', () => {
    expect(buildDebugCommand(base, 'CLAUDE_CODE', 10000, 'powershell', NOW)).toBe(
      'npx -y autowonder@0.2.138 connect --ws-url wss://daily.auto-wonder.example.com/ws/executor'
      + ' --token exec_test_token --executor-id 10000 --provider claude --memory-mode platform'
      + ' --debug 2>&1 | Tee-Object -FilePath "$HOME/aw-claude-10000-260824-15-30-42.log"',
    );
  });

  it('keeps every Qoder flag ahead of the debug suffix', () => {
    const qoderBase = buildStartupCommand(
      'exec_test_token', 10000, 'QODER_CLI', 'platform',
      'https://daily.auto-wonder.example.com/api/mcp', '0.2.138',
      { model: 'ultimate', reasoningEffort: 'high', contextWindow: '1000000' },
    );
    expect(buildDebugCommand(qoderBase, 'QODER_CLI', 10000, 'bash', NOW)).toBe(
      'npx -y autowonder@0.2.138 connect --ws-url wss://daily.auto-wonder.example.com/ws/executor'
      + ' --token exec_test_token --executor-id 10000 --provider qoder --memory-mode platform'
      + ' --model ultimate --reasoning-effort high --context-window 1000000'
      + ' --debug 2>&1 | tee ~/aw-qoder-10000-260824-15-30-42.log',
    );
  });

  it('places --debug after all flags and before the redirect operator', () => {
    const cmd = buildDebugCommand(base, 'CLAUDE_CODE', 10000, 'bash', NOW);
    expect(cmd.indexOf('--memory-mode')).toBeLessThan(cmd.indexOf('--debug'));
    expect(cmd.indexOf('--debug')).toBeLessThan(cmd.indexOf('2>&1'));
    expect(cmd.indexOf('2>&1')).toBeLessThan(cmd.indexOf('| tee'));
  });

  it('uses the same log file name that debugLogFileName produces', () => {
    expect(buildDebugCommand(base, 'CLAUDE_CODE', 10000, 'bash', NOW))
      .toContain(debugLogFileName('CLAUDE_CODE', 10000, NOW));
  });
});
