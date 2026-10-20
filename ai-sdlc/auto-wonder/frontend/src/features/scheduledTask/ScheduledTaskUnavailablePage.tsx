import { useTranslation } from 'react-i18next';
import { Result } from 'antd';
import type { ScheduledTaskUnavailableReason } from './types';

export function ScheduledTaskUnavailablePage({ reason }: { reason?: ScheduledTaskUnavailableReason | null }) {
  const { t } = useTranslation('scheduledTask');
  const unavailableCopy: Record<ScheduledTaskUnavailableReason, { title: string; description: string }> = {
    DATABASE_UPGRADE_REQUIRED: {
      title: t('capability.databaseTitle'),
      description: t('capability.databaseDescription'),
    },
    FEATURE_DISABLED: {
      title: t('capability.disabledTitle'),
      description: t('capability.disabledDescription'),
    },
    CLUSTER_NOT_READY: {
      title: t('capability.clusterTitle'),
      description: t('capability.clusterDescription'),
    },
  };
  const copy = reason && Object.prototype.hasOwnProperty.call(unavailableCopy, reason) ? unavailableCopy[reason] : undefined;
  return (
    <Result
      status="info"
      title={copy?.title ?? t('capability.unavailableTitle')}
      subTitle={copy?.description ?? t('capability.unavailableDescription')}
    />
  );
}

export function ScheduledTaskCapabilityErrorPage() {
  const { t } = useTranslation('scheduledTask');
  return (
    <Result
      status="warning"
      title={t('capability.errorTitle')}
      subTitle={t('capability.errorDescription')}
    />
  );
}
