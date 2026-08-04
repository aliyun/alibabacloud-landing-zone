import { apiClient } from '@/shared/api/client';

export type EvolutionAssetType = 'MEMORY' | 'REPO_RELATION' | 'SKILL' | string;
export type EvolutionProposalStatus =
  | 'PROPOSED'
  | 'VALIDATED'
  | 'TRIAL'
  | 'REPLAY_PASSED'
  | 'REPLAY_FAIL'
  | 'REPLAY_INCONCLUSIVE'
  | 'APPROVED'
  | 'RELEASED'
  | 'REJECTED'
  | string;

export interface EvolutionProposal {
  id: string | number;
  assetType: EvolutionAssetType;
  assetId?: string | number | null;
  triggerType: string;
  rootEvidenceJson?: string | null;
  policyJson?: string | null;
  candidatePatchJson?: string | null;
  status: EvolutionProposalStatus;
  validationJson?: string | null;
  replayJson?: string | null;
  gateJson?: string | null;
  trialJson?: string | null;
  releaseJson?: string | null;
  rollbackJson?: string | null;
  gmtCreate?: string | null;
}

export interface EvolutionEvidence {
  id: string | number;
  assetType: EvolutionAssetType;
  assetId?: string | number | null;
  posteriorType?: string | null;
  contextKey?: string | null;
  sourceType?: string | null;
  sourceRef?: string | null;
  outcome?: string | null;
  posteriorMean?: number | null;
  effectiveSampleSize?: number | null;
  gmtCreate?: string | null;
}

export interface EvolutionAdminOverview {
  proposals: EvolutionProposal[];
  evidence: EvolutionEvidence[];
}

export interface EvolutionAssetManifestCard {
  assetType: EvolutionAssetType;
  assetId?: string | number | null;
  name?: string | null;
  category?: string | null;
  triggerHint?: string | null;
  lazyLoadRef?: string | null;
  version?: number | null;
  posteriorMean?: number | null;
  effectiveSampleSize?: number | null;
}

export interface EvolutionAssetManifest {
  contextKey?: string | null;
  limit?: number | null;
  cards: EvolutionAssetManifestCard[];
}

export interface EvolutionOrchestrateResult {
  action?: string;
  evidenceId?: string | number | null;
  proposalId?: string | number | null;
  proposalStatus?: string | null;
  replayVerdict?: string | null;
  trialDecision?: EvolutionTrialDecision | null;
}

export interface EvolutionTrialDecision {
  proposalId?: string | number | null;
  decision?: 'CONTINUE_TRIAL' | 'ADOPT' | 'REJECT' | string;
  proposalStatus?: string | null;
  reasonCode?: string | null;
  taskPatternKey?: string | null;
  baselinePosteriorMean?: number | null;
  baselineEffectiveSampleSize?: number | null;
  candidatePosteriorMean?: number | null;
  candidateEffectiveSampleSize?: number | null;
  posteriorWinProbability?: number | null;
  posteriorLoseProbability?: number | null;
  expectedLift?: number | null;
}

export async function getEvolutionOverview(limit = 20): Promise<EvolutionAdminOverview> {
  const resp = await apiClient.get<EvolutionAdminOverview>('/api/evolution/admin/overview', { params: { limit } });
  return resp.data;
}

export async function getEvolutionAssetManifest(limit = 20): Promise<EvolutionAssetManifest> {
  const resp = await apiClient.get<EvolutionAssetManifest>('/api/evolution/admin/asset-manifest', { params: { limit } });
  return resp.data;
}

export async function validateProposal(id: string | number): Promise<void> {
  await apiClient.post(`/api/evolution/proposals/${id}/validate`);
}

export async function executeReplay(id: string | number, replaySuiteJson: string): Promise<void> {
  await apiClient.post('/api/evolution/replay/execute', {
    proposalId: id,
    autoValidate: false,
    replaySuiteJson,
  });
}

export async function approveProposal(id: string | number): Promise<void> {
  await apiClient.post(`/api/evolution/proposals/${id}/approve`);
}

export async function releaseProposal(id: string | number): Promise<void> {
  await apiClient.post(`/api/evolution/proposals/${id}/release`);
}

export async function agentReleaseProposal(id: string | number): Promise<void> {
  await apiClient.post(`/api/evolution/proposals/${id}/agent-release`, {
    allowRelease: true,
  });
}

export async function recordTrialOutcome(id: string | number, rawOutcome: 'PASS' | 'FAIL'): Promise<EvolutionTrialDecision> {
  const sourceRef = `manual-ui:${Date.now()}`;
  const resp = await apiClient.post<EvolutionTrialDecision>(`/api/evolution/proposals/${id}/trial/evidence`, {
    rawOutcome,
    sourceType: 'HUMAN_REVIEW',
    sourceRef,
    evidenceJson: JSON.stringify({ summary: `Manual trial ${rawOutcome}` }),
    idempotencyKey: `proposal:${id}:${sourceRef}`,
  });
  return resp.data;
}

export async function decideTrial(id: string | number): Promise<EvolutionTrialDecision> {
  const resp = await apiClient.post<EvolutionTrialDecision>(`/api/evolution/proposals/${id}/trial/decide`);
  return resp.data;
}

export async function rejectProposal(id: string | number, reason: string): Promise<void> {
  await apiClient.post(`/api/evolution/proposals/${id}/reject`, { reason });
}

export async function rollbackProposal(id: string | number): Promise<void> {
  await apiClient.post(`/api/evolution/rollback/${id}`);
}

export async function orchestrateEvolution(payload: unknown): Promise<EvolutionOrchestrateResult> {
  const resp = await apiClient.post<EvolutionOrchestrateResult>('/api/evolution/orchestrate', payload);
  return resp.data;
}
