import { useQuery } from '@tanstack/react-query';
import { listDispatches, getDispatch } from './api';
import type { DispatchListParams } from './types';

export function useDispatches(params: DispatchListParams) {
  return useQuery({
    queryKey: ['dispatches', params],
    queryFn: () => listDispatches(params),
    refetchInterval: 10000,
    refetchIntervalInBackground: false,
  });
}

export function useDispatch(id: number | null) {
  return useQuery({
    queryKey: ['dispatch', id],
    queryFn: () => getDispatch(id as number),
    enabled: id != null,
  });
}
