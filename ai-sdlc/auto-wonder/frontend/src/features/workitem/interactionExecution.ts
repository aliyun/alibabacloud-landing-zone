/** Dispatch statuses that mean the executor is no longer running the interaction. */
const TERMINAL_INTERACTION_EXECUTION_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELED']);

export function isTerminalInteractionExecution(status?: string | null): boolean {
  return status != null && TERMINAL_INTERACTION_EXECUTION_STATUSES.has(status);
}
