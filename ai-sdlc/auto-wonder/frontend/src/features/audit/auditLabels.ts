export const ACTOR_KEYS = { HUMAN: 'humanOperations', AGENT: 'agent', SYSTEM: 'system' } as const;
export const MODULE_KEYS = {
  WORKITEM: 'workitem',
  AGENT: 'agent',
  SKILL: 'skill',
  STATUS_TEMPLATE: 'statusTemplate',
  SCHEDULED_TASK: 'scheduledTask',
  DISPATCH: 'dispatch',
} as const;
export const ACTION_KEYS = {
  CREATE: 'create',
  UPDATE: 'update',
  DELETE: 'delete',
  APPROVE: 'approve',
  REJECT: 'reject',
  RETRY: 'retry',
  FORCE_PUBLISH: 'forcePublish',
  ROLE_CHANGE: 'roleChange',
} as const;
export const RISK_KEYS = { high: 'high', medium: 'medium', low: 'low' } as const;
export const TARGET_KEYS = {
  workitem: 'workitem',
  agent: 'agent',
  skill: 'skill',
  scheduled_task_run: 'scheduledTaskRun',
} as const;
