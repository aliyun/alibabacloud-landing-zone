import { apiClient } from '@/shared/api/client';

export type WorkflowNodeKind = 'MEMBER' | 'HUMAN' | 'EXTERNAL' | 'UNRESOLVED';
export type WorkflowEdgeKind = 'HANDOFF' | 'REWORK' | 'ESCALATION';
export type WorkflowFindingKind = 'MISSING_CONFIGURATION' | 'AMBIGUOUS_TARGET' | 'CONFLICT' | 'INFORMATION';

export interface WorkflowEvidenceRef {
  sourceId: string;
  fieldPath?: string | null;
  quote: string;
}

export interface WorkflowStepSummary {
  stepId: string;
  label: string;
  description: string;
  evidenceRefs?: WorkflowEvidenceRef[];
}

export interface WorkflowNode {
  id: string;
  kind: WorkflowNodeKind;
  label: string;
  agentId: string | null;
  responsibility: string;
  stepSummaries?: WorkflowStepSummary[];
  evidenceRefs?: WorkflowEvidenceRef[];
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  kind: WorkflowEdgeKind;
  condition: string;
  isMainPath: boolean;
  evidenceRefs?: WorkflowEvidenceRef[];
}

export interface WorkflowFinding {
  id: string;
  kind: WorkflowFindingKind;
  summary: string;
  nodeIds?: string[];
  edgeIds?: string[];
  evidenceRefs?: WorkflowEvidenceRef[];
}

export interface WorkflowGraph {
  schemaVersion: string;
  inputHash: string;
  workflow: {
    nodes: WorkflowNode[];
    edges: WorkflowEdge[];
    findings: WorkflowFinding[];
  };
  review?: {
    status: string;
    checks: Record<string, string>;
    summary: string;
  };
}

export type SquadWorkflowStatus = 'EMPTY' | 'MISSING' | 'GENERATING' | 'REPAIRING' | 'READY' | 'FAILED';

export interface SquadWorkflowError {
  code: string;
  message: string;
  retryable: boolean;
}

export interface SquadWorkflowView {
  status: SquadWorkflowStatus;
  inputHash: string | null;
  analysisVersion: string | null;
  generationId: number | null;
  attemptId: string | null;
  generatedAt: string | null;
  error: SquadWorkflowError | null;
  graph: WorkflowGraph | null;
}

export interface GenerateWorkflowRequest {
  expectedInputHash?: string | null;
  retry?: boolean;
}

/** Server code for "online configuration changed since the page loaded the hash". */
export const WORKFLOW_STALE_INPUT_CODE = '38003';

export async function getSquadWorkflow(squadId: number): Promise<SquadWorkflowView> {
  const resp = await apiClient.get<SquadWorkflowView>(`/api/squads/${squadId}/workflow`);
  return resp.data;
}

export async function generateSquadWorkflow(
  squadId: number,
  request: GenerateWorkflowRequest = {},
): Promise<SquadWorkflowView> {
  const resp = await apiClient.post<SquadWorkflowView>(`/api/squads/${squadId}/workflow/generations`, request);
  return resp.data;
}
