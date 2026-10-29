import { useQuery } from '@tanstack/react-query';
import { getSpaceDashboardOverview, type SpaceDashboardParams } from './api';

/** 看板数据非实时：一次请求覆盖三个版块，60 秒内不重复取数。 */
export function useSpaceDashboardOverview(params: SpaceDashboardParams) {
  return useQuery({
    queryKey: [
      'space-dashboard-overview',
      params.deliveryStart,
      params.deliveryEnd,
      params.workTypes.join(','),
      params.costStart,
      params.costEnd,
    ],
    queryFn: () => getSpaceDashboardOverview(params),
    staleTime: 60 * 1000,
    // 切换时间窗或类型时保留上一次结果，避免筛选控件随骨架屏卸载。
    placeholderData: (previous) => previous,
    // 403 无权限与参数错误重试也不会成功，交给页面渲染对应空态。
    retry: false,
  });
}
