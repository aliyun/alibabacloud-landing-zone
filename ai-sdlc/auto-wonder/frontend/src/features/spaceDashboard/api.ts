import { apiClient } from '@/shared/api/client';
import type { SpaceDashboardOverview } from './types';

export interface SpaceDashboardParams {
  deliveryStart: string;
  deliveryEnd: string;
  /** 已选工单类型；空数组表示全部（请求不带 workTypes）。 */
  workTypes: string[];
  costStart: string;
  costEnd: string;
}

export async function getSpaceDashboardOverview(params: SpaceDashboardParams): Promise<SpaceDashboardOverview> {
  const resp = await apiClient.get<SpaceDashboardOverview>('/api/space-dashboard/overview', {
    params: {
      deliveryStart: params.deliveryStart,
      deliveryEnd: params.deliveryEnd,
      workTypes: params.workTypes.length > 0 ? params.workTypes.join(',') : undefined,
      costStart: params.costStart,
      costEnd: params.costEnd,
    },
  });
  return resp.data;
}
