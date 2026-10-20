import { apiClient } from '@/shared/api/client';
import type { CreatedWorkitemPage, DeliveryOverview, PendingWorkitemPage } from './types';

export async function getDeliveryOverview(params: { startDate: string; endDate: string }): Promise<DeliveryOverview> {
  const resp = await apiClient.get<DeliveryOverview>('/api/insights/delivery-overview', {
    params: { start_date: params.startDate, end_date: params.endDate },
  });
  return resp.data;
}

export async function getPendingWorkitems(params: { page: number; size: number }): Promise<PendingWorkitemPage> {
  const resp = await apiClient.get<PendingWorkitemPage>('/api/insights/delivery-overview/pending', {
    params: { page: params.page, size: params.size },
  });
  return resp.data;
}

export async function getCreatedWorkitems(
  params: { startDate: string; endDate: string; page: number; size: number },
): Promise<CreatedWorkitemPage> {
  const resp = await apiClient.get<CreatedWorkitemPage>('/api/insights/delivery-overview/created', {
    params: { start_date: params.startDate, end_date: params.endDate, page: params.page, size: params.size },
  });
  return resp.data;
}
