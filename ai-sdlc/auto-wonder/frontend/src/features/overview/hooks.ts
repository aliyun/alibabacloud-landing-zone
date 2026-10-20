import { useQuery } from '@tanstack/react-query';
import { getCreatedWorkitems, getDeliveryOverview, getPendingWorkitems } from './api';

export function useDeliveryOverview(startDate: string, endDate: string) {
  return useQuery({
    queryKey: ['delivery-overview', startDate, endDate],
    queryFn: () => getDeliveryOverview({ startDate, endDate }),
    staleTime: 60 * 1000,
  });
}

export function usePendingWorkitems(page: number, pageSize: number) {
  return useQuery({
    queryKey: ['delivery-overview-pending', page, pageSize],
    queryFn: () => getPendingWorkitems({ page, size: pageSize }),
    staleTime: 60 * 1000,
  });
}

export function useCreatedWorkitems(startDate: string, endDate: string, page: number, pageSize: number) {
  return useQuery({
    queryKey: ['delivery-overview-created', startDate, endDate, page, pageSize],
    queryFn: () => getCreatedWorkitems({ startDate, endDate, page, size: pageSize }),
    staleTime: 60 * 1000,
  });
}
