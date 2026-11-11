import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';

/** The design aggregate for one workitem: target project context, confirmation state, versions. */
export interface PrdDesign {
  id: number;
  workitemId: number;
  targetRepoId: number | null;
  targetProfileId: number | null;
  targetProjectNotes?: string | null;
  targetAppRoot: string | null;
  targetCommitSha: string | null;
  confirmedVersionId: number | null;
  confirmedVersionNo: number | null;
  confirmedSha256: string | null;
  deliveryMode: string;
  latestVersionNo: number | null;
  contentLanguage: string | null;
  version: number;
  gmtModified: string | null;
  versions: PrdDesignVersion[];
}

/** One design version. designJson is only populated on a single-version read. */
export interface PrdDesignVersion {
  version: number;
  id: number;
  designId: number;
  versionNo: number;
  status: string;
  sourceVersionId: number | null;
  designSha256: string;
  htmlOssRef: string | null;
  htmlSha256: string | null;
  htmlSize: number | null;
  rendererVersion: string | null;
  summary: string | null;
  changeSummary: string | null;
  requirementCount: number | null;
  openQuestionCount: number | null;
  blockingQuestionCount: number | null;
  aiSessionId: number | null;
  confirmedBy: number | null;
  confirmedAt: string | null;
  confirmedSha256: string | null;
  gmtCreate: string | null;
  designJson: string | null;
}

export interface PrdDeliveryBinding {
  dispatchId: number;
  workitemId: number;
  designId: number;
  designVersionId: number;
  versionNo: number;
  designSha256: string;
  targetRepoId: number | null;
  targetAppRoot: string | null;
  targetCommitSha: string | null;
  gmtCreate: string | null;
}

export interface RequirementAcceptanceEvidence {
  kind: string;
  ref: string;
  title: string | null;
  /** Presigned download URL for ARTIFACT evidence; null otherwise. */
  url: string | null;
}

export interface RequirementAcceptance {
  id: number;
  dispatchId: number;
  designVersionId: number;
  requirementKey: string;
  requirementTitle: string | null;
  priority: string | null;
  excluded: boolean;
  implementation: string;
  verdict: string;
  note: string | null;
  notApplicableReason: string | null;
  evidence: RequirementAcceptanceEvidence[];
  /** True once an explicit report moved the row off NOT_VERIFIED. */
  verified: boolean;
  reportedAt: number | null;
}

export interface PrdDeliveryAcceptance {
  dispatchId: number;
  designVersionId: number;
  versionNo: number;
  designSha256: string;
  targetRepoId: number | null;
  targetAppRoot: string | null;
  targetCommitSha: string | null;
  requirements: RequirementAcceptance[];
  total: number;
  passed: number;
  failed: number;
  notVerified: number;
  notApplicable: number;
  complete: boolean;
}

/** Server-owned version lifecycle; kept here so no component hardcodes a status string. */
export const PRD_VERSION_STATUS = {
  DRAFT: 'DRAFT',
  PENDING_REVIEW: 'PENDING_REVIEW',
  CONFIRMED: 'CONFIRMED',
} as const;

export const DELIVERY_MODE = {
  LEGACY: 'LEGACY',
  PRD_DRIVEN: 'PRD_DRIVEN',
} as const;

export const ACCEPTANCE_VERDICT = {
  PASSED: 'PASSED',
  FAILED: 'FAILED',
  NOT_VERIFIED: 'NOT_VERIFIED',
  NOT_APPLICABLE: 'NOT_APPLICABLE',
} as const;

export const IMPLEMENTATION_RESULT = {
  IMPLEMENTED: 'IMPLEMENTED',
  PARTIAL: 'PARTIAL',
  NOT_IMPLEMENTED: 'NOT_IMPLEMENTED',
} as const;

export interface SelectTargetRequest {
  profileId?: number;
  repoId?: number;
  appRoot?: string;
  projectNotes?: string;
}

export interface SaveDesignRequest {
  expectedVersion?: number;
  versionId?: number | null;
  designJson: string;
  changeSummary?: string | null;
  aiSessionId?: number | null;
}

export interface ConfirmVersionRequest {
  ignoreOpenQuestions?: boolean;
  versionId: number;
  /** The digest the reviewer actually saw; a stale digest is refused rather than confirmed. */
  expectedDigest?: string | null;
  deliveryMode?: string;
}

export async function getPrdDesign(workitemId: string | number, versions = true): Promise<PrdDesign> {
  const resp = await apiClient.get<PrdDesign>(`/api/workitems/${workitemId}/prd`, {
    params: { versions },
  });
  return resp.data;
}

/**
 * Read-only summary for the workitem card: the design if one exists, else null. Browsing a workitem
 * never creates a design aggregate, so this differs from getPrdDesign (which opens the workbench).
 */
export async function getPrdSummary(workitemId: string | number): Promise<PrdDesign | null> {
  const resp = await apiClient.get<PrdDesign | null>(`/api/workitems/${workitemId}/prd/summary`);
  return resp.data;
}

export async function selectPrdTarget(
  workitemId: string | number,
  request: SelectTargetRequest,
): Promise<PrdDesign> {
  const resp = await apiClient.put<PrdDesign>(`/api/workitems/${workitemId}/prd/target`, request);
  return resp.data;
}

export async function listPrdVersions(workitemId: string | number): Promise<PrdDesignVersion[]> {
  const resp = await apiClient.get<PrdDesignVersion[]>(`/api/workitems/${workitemId}/prd/versions`);
  return resp.data;
}

export async function getPrdVersion(
  workitemId: string | number,
  versionId: string | number,
): Promise<PrdDesignVersion> {
  const resp = await apiClient.get<PrdDesignVersion>(
    `/api/workitems/${workitemId}/prd/versions/${versionId}`,
  );
  return resp.data;
}

export async function savePrdDraft(
  workitemId: string | number,
  request: SaveDesignRequest,
): Promise<PrdDesignVersion> {
  const resp = await apiClient.post<PrdDesignVersion>(
    `/api/workitems/${workitemId}/prd/versions`,
    request,
  );
  return resp.data;
}

export async function createPrdDraftFrom(
  workitemId: string | number,
  versionId: string | number,
  changeSummary?: string,
): Promise<PrdDesignVersion> {
  const resp = await apiClient.post<PrdDesignVersion>(
    `/api/workitems/${workitemId}/prd/versions/${versionId}/draft`,
    undefined,
    { params: changeSummary ? { changeSummary } : undefined },
  );
  return resp.data;
}

export async function submitPrdForReview(
  workitemId: string | number,
  versionId: string | number,
): Promise<PrdDesignVersion> {
  const resp = await apiClient.post<PrdDesignVersion>(
    `/api/workitems/${workitemId}/prd/versions/${versionId}/review`,
  );
  return resp.data;
}

export async function confirmPrdVersion(
  workitemId: string | number,
  request: ConfirmVersionRequest,
): Promise<PrdDesignVersion> {
  const resp = await apiClient.post<PrdDesignVersion>(
    `/api/workitems/${workitemId}/prd/confirm`,
    request,
  );
  return resp.data;
}

/**
 * Fetch the rendered HTML document for the sandboxed preview.
 *
 * <p>This endpoint returns raw HTML bytes, not an ApiResult envelope, so it bypasses apiClient (which
 * unwraps `data`) and is fetched directly with the bearer token, mirroring artifact preview. The
 * result is meant to be turned into a blob URL and shown only inside a sandboxed iframe.
 */
export async function getPrdHtmlBlob(
  workitemId: string | number,
  versionId: string | number,
  signal?: AbortSignal,
): Promise<Blob> {
  const headers = new Headers();
  const token = useAuthStore.getState().accessToken;
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const resp = await fetch(`/api/workitems/${workitemId}/prd/versions/${versionId}/html`, {
    headers,
    signal,
  });
  if (resp.status === 401) {
    useAuthStore.getState().clear();
    window.location.href = '/login';
    throw new Error('HTTP 401');
  }
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status}`);
  }
  return resp.blob();
}

export async function listPrdDeliveries(
  workitemId: string | number,
  limit = 20,
): Promise<PrdDeliveryBinding[]> {
  const resp = await apiClient.get<PrdDeliveryBinding[]>(
    `/api/workitems/${workitemId}/prd/deliveries`,
    { params: { limit } },
  );
  return resp.data;
}

export async function getPrdDeliveryAcceptance(
  workitemId: string | number,
  dispatchId: string | number,
): Promise<PrdDeliveryAcceptance> {
  const resp = await apiClient.get<PrdDeliveryAcceptance>(
    `/api/workitems/${workitemId}/prd/deliveries/${dispatchId}/acceptance`,
  );
  return resp.data;
}
