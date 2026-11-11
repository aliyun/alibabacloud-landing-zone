/**
 * `/executors?agentId=<id>&view=agent` entry contract. A positive integer selects that agent's
 * executors; `view=agent` additionally forces the agent grouping for this visit only. Anything else
 * is reported as invalid so the page can fall back instead of requesting a bad or negative id.
 */
export interface ExecutorAgentParam {
  agentId?: number;
  invalid: boolean;
  entryView: boolean;
}

export function parseExecutorAgentParam(params: URLSearchParams): ExecutorAgentParam {
  const entryView = params.get('view') === 'agent';
  const raw = params.get('agentId');
  if (raw == null || raw.trim() === '') {
    return { invalid: false, entryView };
  }
  const trimmed = raw.trim();
  if (!/^\d+$/.test(trimmed)) {
    return { invalid: true, entryView };
  }
  const agentId = Number(trimmed);
  if (!Number.isSafeInteger(agentId) || agentId <= 0) {
    return { invalid: true, entryView };
  }
  return { agentId, invalid: false, entryView };
}
