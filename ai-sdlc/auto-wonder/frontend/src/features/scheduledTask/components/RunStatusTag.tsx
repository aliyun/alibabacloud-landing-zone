import type { ReactNode } from 'react';
import { Tag } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, ExclamationCircleOutlined, StopOutlined, SyncOutlined } from '@ant-design/icons';

const RUN_STATUS_META: Record<string, { label: string; color: string; icon: ReactNode }> = {
  SUCCEEDED: { label: '成功', color: 'success', icon: <CheckCircleOutlined /> },
  FAILED: { label: '失败', color: 'error', icon: <CloseCircleOutlined /> },
  SKIPPED: { label: '跳过', color: 'warning', icon: <ExclamationCircleOutlined /> },
  RUNNING: { label: '运行中', color: 'processing', icon: <SyncOutlined spin /> },
  CANCELED: { label: '已取消', color: 'default', icon: <StopOutlined /> },
};

export function RunStatusTag({ status }: { status: string }) {
  const meta = RUN_STATUS_META[status] ?? { label: status, color: 'default', icon: null };
  return <Tag color={meta.color} icon={meta.icon}>{meta.label}</Tag>;
}
