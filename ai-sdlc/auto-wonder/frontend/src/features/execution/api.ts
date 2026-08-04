import { apiClient } from '@/shared/api/client';
import type { DispatchPageVO, DispatchVO, DispatchListParams } from './types';

export async function listDispatches(params: DispatchListParams): Promise<DispatchPageVO> {
  const resp = await apiClient.get<DispatchPageVO>('/api/dispatches', {
    params: {
      page: params.page,
      page_size: params.pageSize,
      status: params.status || undefined,
      agent_id: params.agentId || undefined,
      workitem_id: params.workitemId || undefined,
      time_range: params.timeRange,
    },
  });
  return resp.data;
}

export async function getDispatch(id: number): Promise<DispatchVO> {
  const resp = await apiClient.get<DispatchVO>(`/api/dispatches/${id}`);
  return resp.data;
}
