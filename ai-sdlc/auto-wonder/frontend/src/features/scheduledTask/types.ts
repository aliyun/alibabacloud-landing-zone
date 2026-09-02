export type ScheduledTaskStatus = 'ACTIVE' | 'PAUSED' | 'EXHAUSTED' | 'ARCHIVED';
export type ScheduledTaskRunStatus = 'QUEUED' | 'STARTING' | 'WAITING_EXECUTOR' | 'WAITING_HUMAN' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'CANCELED' | 'SKIPPED';
export type ScheduleType = 'ONCE' | 'CRON';
export type SessionMode = 'ISOLATED' | 'CONTINUOUS';
export type OverlapPolicy = 'SKIP' | 'QUEUE' | 'ALLOW';
export type MisfirePolicy = 'SKIP_ALL' | 'FIRE_LATEST' | 'FIRE_ALL';
export type ScheduledTaskCapabilityMode = 'LEGACY' | 'V037_PARTIAL' | 'V037_READY' | 'INCONSISTENT';
export type ScheduledTaskUnavailableReason = 'DATABASE_UPGRADE_REQUIRED' | 'FEATURE_DISABLED' | 'CLUSTER_NOT_READY';

export interface ScheduledTaskCapability {
  available: boolean;
  mode: ScheduledTaskCapabilityMode;
  clusterReady: boolean;
  reason: ScheduledTaskUnavailableReason | null;
}

export interface ScheduledTask {
  id: number;
  name: string;
  instructionMd: string;
  squadId: number;
  initialAgentId: number;
  scheduleType: ScheduleType;
  runAt: string | null;
  cronExpression: string | null;
  timezone: string;
  sessionMode: SessionMode;
  overlapPolicy: OverlapPolicy;
  misfirePolicy: MisfirePolicy;
  startDeadlineSeconds: number | null;
  affinityTimeoutSeconds: number | null;
  status: ScheduledTaskStatus;
  nextFireAt: string | null;
  lastFireAt: string | null;
  gmtCreate: string;
  gmtModified: string;
  creatorId: number;
  modifierId: number;
  version: number;
}

export interface ScheduledTaskRun {
  id: number;
  scheduledTaskId: number;
  status: ScheduledTaskRunStatus;
  triggerType: 'SCHEDULED' | 'MANUAL' | 'MISFIRE';
  triggerKey: string;
  scheduledAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  currentAgentId: number | null;
  currentStepId: number | null;
  failureReason: string | null;
  version: number;
  sdlcId?: number | null;
  skipReason?: string | null;
  degradedResume?: boolean;
  degradedReason?: string | null;
  resultSummary?: string | null;
  error?: string | null;
  squadId?: number | null;
  initialAgentId?: number | null;
  sessionMode?: SessionMode | null;
  resumeFromRunId?: number | null;
  ownerId?: number | null;
  executorId?: number | null;
  snapshot?: { task?: { name?: string; instructionMd?: string }; requirementDocuments?: Array<{ id?: number; name?: string; sha256?: string }>; agents?: Array<{ agentId?: number; agentVersionId?: number; identity?: { name?: string }; sdlc?: { id?: number | string; steps?: Array<{ id?: number | string; name?: string }> } }> };
}

export interface CreateScheduledTaskBody {
  name: string;
  instructionMd: string;
  squadId: number;
  initialAgentId: number;
  scheduleType: ScheduleType;
  runAt?: string;
  cronExpression?: string;
  timezone: string;
  sessionMode: SessionMode;
  overlapPolicy: OverlapPolicy;
  misfirePolicy: MisfirePolicy;
  startDeadlineSeconds?: number;
  affinityTimeoutSeconds?: number;
  initialStatus: 'ACTIVE' | 'PAUSED';
}

export interface UpdateScheduledTaskBody extends Omit<CreateScheduledTaskBody, 'initialStatus'> {
  version: number;
}

export interface ScheduledTaskListQuery {
  status?: ScheduledTaskStatus;
  creatorId?: number;
  squadId?: number;
  keyword?: string;
  size?: number;
  offset?: number;
}

export interface OffsetPage<T> {
  list: T[];
  total: number;
  offset: number;
  size: number;
  pageNum?: number;
  pageSize?: number;
}

export interface SchedulePreview {
  cronExpression: string;
  timezone: string;
  instants: string[];
}
