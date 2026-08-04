export type DispatchTimeRange = '7d' | '30d' | '90d';

export interface ArtifactVO {
  id: number;
  workitemId: number;
  dispatchId: number;
  name: string;
  type: string;
  size: number | null;
  gmtCreate: string;
}

export interface DispatchVO {
  id: number;
  workitemId: number | null;
  sdlcStepId: number | null;
  agentId: number | null;
  agentVersionId: number | null;
  executorId: number | null;
  status: string;
  attempt: number | null;
  resultSummary: string | null;
  error: string | null;
  packageOssRef: string | null;
  gmtCreate: string;
  gmtModified: string;
  workitemTitle: string | null;
  agentName: string | null;
  agentVersionNo: number | null;
  executorName: string | null;
  artifacts: ArtifactVO[] | null;
}

export interface DispatchPageVO {
  list: DispatchVO[];
  total: number;
  page: number;
  pageSize: number;
}

export interface DispatchListParams {
  page: number;
  pageSize: number;
  status?: string;
  agentId?: number;
  workitemId?: number;
  timeRange: DispatchTimeRange;
}
