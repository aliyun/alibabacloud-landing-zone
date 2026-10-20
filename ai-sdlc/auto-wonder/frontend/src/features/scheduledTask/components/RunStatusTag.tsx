import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';
import { Tag } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, ExclamationCircleOutlined, StopOutlined, SyncOutlined } from '@ant-design/icons';

export function RunStatusTag({ status }: { status: string }) {
  const { t } = useTranslation('scheduledTask');
  const RUN_STATUS_META: Record<string, { label: string; color: string; icon: ReactNode }> = {
    QUEUED: { label: t('runStatus.queued'), color: 'default', icon: null },
    STARTING: { label: t('runStatus.starting'), color: 'default', icon: null },
    WAITING_EXECUTOR: { label: t('runStatus.waitingExecutor'), color: 'default', icon: null },
    WAITING_HUMAN: { label: t('runStatus.waitingHuman'), color: 'default', icon: null },
    PAUSED: { label: t('runStatus.paused'), color: 'default', icon: null },
    TIMED_OUT: { label: t('runStatus.timedOut'), color: 'default', icon: null },
    SUCCEEDED: { label: t('runStatus.succeeded'), color: 'success', icon: <CheckCircleOutlined aria-hidden="true" /> },
    FAILED: { label: t('runStatus.failed'), color: 'error', icon: <CloseCircleOutlined aria-hidden="true" /> },
    SKIPPED: { label: t('runStatus.skipped'), color: 'warning', icon: <ExclamationCircleOutlined aria-hidden="true" /> },
    RUNNING: { label: t('runStatus.running'), color: 'processing', icon: <SyncOutlined aria-hidden="true" spin /> },
    CANCELED: { label: t('runStatus.canceled'), color: 'default', icon: <StopOutlined aria-hidden="true" /> },
  };
  const meta = Object.prototype.hasOwnProperty.call(RUN_STATUS_META, status) ? RUN_STATUS_META[status] : { label: status, color: 'default', icon: null };
  return <Tag color={meta.color} icon={meta.icon}>{meta.label}</Tag>;
}
