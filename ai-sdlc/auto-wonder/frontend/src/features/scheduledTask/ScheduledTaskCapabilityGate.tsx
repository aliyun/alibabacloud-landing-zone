import type { ReactNode } from 'react';
import { Flex, Spin, Typography } from 'antd';
import type { UseQueryResult } from '@tanstack/react-query';
import { useScheduledTaskCapability } from './hooks';
import { ScheduledTaskCapabilityErrorPage, ScheduledTaskUnavailablePage } from './ScheduledTaskUnavailablePage';
import type { ScheduledTaskCapability } from './types';

export function isScheduledTaskCapabilityReady(capability?: ScheduledTaskCapability): boolean {
  return capability?.available === true
    && capability.mode === 'V037_READY'
    && capability.clusterReady === true
    && capability.reason === null;
}

type ScheduledTaskCapabilityQueryState = Pick<
  UseQueryResult<ScheduledTaskCapability>,
  'data' | 'status' | 'fetchStatus' | 'isFetching' | 'isError'
>;

export function isScheduledTaskCapabilityQueryReady(query: ScheduledTaskCapabilityQueryState): boolean {
  return query.status === 'success'
    && query.fetchStatus === 'idle'
    && !query.isFetching
    && !query.isError
    && isScheduledTaskCapabilityReady(query.data);
}

export function ScheduledTaskCapabilityGate({ children }: { children: ReactNode }) {
  const capability = useScheduledTaskCapability();

  if (capability.isPending || capability.isFetching) {
    return (
      <Flex vertical align="center" justify="center" gap={12} style={{ minHeight: 240 }}>
        <Spin />
        <Typography.Text type="secondary">正在检查功能可用性…</Typography.Text>
      </Flex>
    );
  }
  if (capability.isError) return <ScheduledTaskCapabilityErrorPage />;
  if (!isScheduledTaskCapabilityQueryReady(capability)) {
    return <ScheduledTaskUnavailablePage reason={capability.data?.reason} />;
  }
  return <>{children}</>;
}
