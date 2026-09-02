import type { QoderLaunchOptions } from './qoderOptions';

export type DebugShell = 'bash' | 'powershell';

const PROVIDER_MAP: Record<string, string> = {
  QODER_CN_CLI: 'qodercn',
  QODER_CLI: 'qoder',
  CLAUDE_CODE: 'claude',
  CODEX_CLI: 'codex',
  CURSOR_CLI: 'cursor',
};

export function resolveProvider(clientKind: string): string {
  return PROVIDER_MAP[clientKind] ?? 'claude';
}

export function buildWsUrl(mcpBaseUrl: string): string {
  let url: URL;
  try {
    url = new URL(mcpBaseUrl);
  } catch {
    throw new Error('MCP 地址格式不合法');
  }
  const proto = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${url.host}/ws/executor`;
}

export type StartupOs = 'windows' | 'posix';

export function detectStartupOs(): StartupOs {
  try {
    const platform = (navigator.platform ?? '').toLowerCase();
    const hint = platform || (navigator.userAgent ?? '').toLowerCase();
    if (hint.includes('win')) {
      return 'windows';
    }
  } catch {
    // navigator unavailable — fall through to the default command
  }
  return 'posix';
}

export function buildStartupCommand(
  token: string,
  executorId: number,
  clientKind: string,
  memoryMode: string,
  mcpBaseUrl: string,
  runtimeVersion: string,
  qoder?: QoderLaunchOptions,
  os: StartupOs = 'posix',
): string {
  const provider = resolveProvider(clientKind);
  const isQoderFamily = provider === 'qoder' || provider === 'qodercn';
  const qoderFlags = isQoderFamily && qoder
    ? ` --model ${qoder.model} --reasoning-effort ${qoder.reasoningEffort} --context-window ${qoder.contextWindow}`
    : '';
  const tokenAwareFlag = isQoderFamily ? ' --token-aware-enable' : '';
  const base = `npx -y autowonder@${runtimeVersion} connect --ws-url ${buildWsUrl(mcpBaseUrl)} --token ${token} --executor-id ${executorId} --provider ${provider} --memory-mode ${memoryMode}${qoderFlags}${tokenAwareFlag}`;
  if (os === 'windows') {
    // Session-level UTF-8 console so Chinese progress output is not mangled on CP936 systems;
    // affects only the launched process session, never the user's system configuration.
    return `powershell -NoProfile -Command "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $OutputEncoding = [System.Text.Encoding]::UTF8; ${base}"`;
  }
  return base;
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

export function debugLogFileName(clientKind: string, executorId: number, now: Date): string {
  const date = `${pad(now.getFullYear() % 100)}${pad(now.getMonth() + 1)}${pad(now.getDate())}`;
  const time = `${pad(now.getHours())}-${pad(now.getMinutes())}-${pad(now.getSeconds())}`;
  return `aw-${resolveProvider(clientKind)}-${executorId}-${date}-${time}.log`;
}

export function buildDebugCommand(
  baseCommand: string,
  clientKind: string,
  executorId: number,
  shell: DebugShell,
  now: Date,
): string {
  const logFile = debugLogFileName(clientKind, executorId, now);
  const redirect = shell === 'powershell'
    ? `| Tee-Object -FilePath "$HOME/${logFile}"`
    : `| tee ~/${logFile}`;
  return `${baseCommand} --debug 2>&1 ${redirect}`;
}
