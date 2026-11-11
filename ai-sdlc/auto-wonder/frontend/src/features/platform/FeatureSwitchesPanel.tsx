import { Card, Flex, Switch, Typography, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useEffect, useId, useState, type ReactNode } from 'react';
import { i18n } from '@/shared/i18n';
import {
  FEATURE_SWITCH_QUERY_KEY,
  getFeatureSwitches,
  updateFeatureSwitches,
  type FeatureSwitches,
  type UpdateFeatureSwitchesParams,
} from './featureSwitchApi';

const { Text, Title } = Typography;

export interface FeatureSwitchesPanelProps {
  canManage?: boolean;
}

interface FeatureSwitchCardProps {
  testId: string;
  title: string;
  enabled: boolean;
  status: string;
  toggle: ReactNode;
  children: ReactNode;
}

function FeatureSwitchCard({ testId, title, enabled, status, toggle, children }: FeatureSwitchCardProps) {
  const headingId = useId();

  return (
    <Card role="region" aria-labelledby={headingId} data-testid={testId}>
      <Flex align="start" gap={24}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Title id={headingId} level={4} style={{ margin: '0 0 8px' }}>{title}</Title>
          {children}
        </div>
        <Flex align="center" gap={12} style={{ flexShrink: 0, whiteSpace: 'nowrap' }}>
          <Text type={enabled ? 'success' : 'secondary'}>{status}</Text>
          {toggle}
        </Flex>
      </Flex>
    </Card>
  );
}

/**
 * 平台级开关配置。每个开关独立存储与版本，分别更新时互不覆盖。
 */
export function FeatureSwitchesPanel({ canManage = false }: FeatureSwitchesPanelProps) {
  const { t } = useTranslation('platform');
  const queryClient = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: FEATURE_SWITCH_QUERY_KEY,
    queryFn: getFeatureSwitches,
    retry: false,
  });
  const [duplicateEnabled, setDuplicateEnabled] = useState(false);
  const [reviewEnabled, setReviewEnabled] = useState(false);
  const [creationEnabled, setCreationEnabled] = useState(true);

  useEffect(() => {
    if (data) {
      setDuplicateEnabled(data.duplicateWorkitemIntentEnabled);
      setReviewEnabled(data.userRegistrationReviewEnabled);
      setCreationEnabled(data.workspaceCreationDefaultAllowed);
    }
  }, [data]);

  const mutation = useMutation({
    mutationFn: updateFeatureSwitches,
    onSuccess: (next: FeatureSwitches) => {
      queryClient.setQueryData(FEATURE_SWITCH_QUERY_KEY, next);
      setDuplicateEnabled(next.duplicateWorkitemIntentEnabled);
      setReviewEnabled(next.userRegistrationReviewEnabled);
      setCreationEnabled(next.workspaceCreationDefaultAllowed);
      message.success(i18n.t('platform:featureSwitches.saved'));
    },
    onError: (error: Error, variables: UpdateFeatureSwitchesParams) => {
      // 保存失败只回滚本次提交的开关，其他开关保持界面现值。
      if (variables.duplicateWorkitemIntentEnabled !== undefined) {
        setDuplicateEnabled(Boolean(data?.duplicateWorkitemIntentEnabled));
      }
      if (variables.userRegistrationReviewEnabled !== undefined) {
        setReviewEnabled(Boolean(data?.userRegistrationReviewEnabled));
      }
      if (variables.workspaceCreationDefaultAllowed !== undefined) {
        setCreationEnabled(data?.workspaceCreationDefaultAllowed ?? true);
      }
      message.error(error.message || i18n.t('platform:featureSwitches.saveFailed'));
    },
  });

  if (isLoading) {
    return <Card loading />;
  }

  return (
    <Flex vertical gap={12}>
      <FeatureSwitchCard
        testId="feature-switch-duplicate"
        title={t('featureSwitches.duplicateIntent.title')}
        enabled={duplicateEnabled}
        status={duplicateEnabled
          ? t('featureSwitches.duplicateIntent.statusOn')
          : t('featureSwitches.duplicateIntent.statusOff')}
        toggle={(
          <Switch
            aria-label={t('featureSwitches.duplicateIntent.toggle')}
            checked={duplicateEnabled}
            disabled={!canManage || mutation.isPending}
            onChange={(checked) => {
              setDuplicateEnabled(checked);
              mutation.mutate({
                duplicateWorkitemIntentEnabled: checked,
                expectedVersion: data?.version ?? 0,
              });
            }}
          />
        )}
      >
        <Text type="secondary">
          <Text strong>{t('featureSwitches.duplicateIntent.scopeLabel')}</Text>
          {t('featureSwitches.duplicateIntent.scopeValue')}
        </Text>
        <div><Text type="secondary">{t('featureSwitches.duplicateIntent.scopeHint')}</Text></div>
        <div><Text type="secondary">{t('featureSwitches.duplicateIntent.description')}</Text></div>
        <div><Text type="secondary">{t('featureSwitches.duplicateIntent.eligibility')}</Text></div>
        <div><Text type="secondary" style={{ fontSize: 12 }}>{t('featureSwitches.duplicateIntent.hint')}</Text></div>
      </FeatureSwitchCard>

      <FeatureSwitchCard
        testId="feature-switch-registration-review"
        title={t('featureSwitches.registrationReview.title')}
        enabled={reviewEnabled}
        status={reviewEnabled
          ? t('featureSwitches.registrationReview.statusOn')
          : t('featureSwitches.registrationReview.statusOff')}
        toggle={(
          <Switch
            aria-label={t('featureSwitches.registrationReview.toggle')}
            checked={reviewEnabled}
            disabled={!canManage || mutation.isPending}
            onChange={(checked) => {
              setReviewEnabled(checked);
              mutation.mutate({
                userRegistrationReviewEnabled: checked,
                userRegistrationReviewVersion: data?.userRegistrationReviewVersion ?? 0,
              });
            }}
          />
        )}
      >
        <Text type="secondary">{t('featureSwitches.registrationReview.hint')}</Text>
      </FeatureSwitchCard>

      <FeatureSwitchCard
        testId="feature-switch-workspace-creation"
        title={t('featureSwitches.workspaceCreationDefault.title')}
        enabled={creationEnabled}
        status={creationEnabled
          ? t('featureSwitches.workspaceCreationDefault.statusOn')
          : t('featureSwitches.workspaceCreationDefault.statusOff')}
        toggle={(
          <Switch
            aria-label={t('featureSwitches.workspaceCreationDefault.toggle')}
            checked={creationEnabled}
            disabled={!canManage || mutation.isPending}
            onChange={(checked) => {
              setCreationEnabled(checked);
              mutation.mutate({
                workspaceCreationDefaultAllowed: checked,
                workspaceCreationDefaultAllowedVersion: data?.workspaceCreationDefaultAllowedVersion ?? 0,
              });
            }}
          />
        )}
      >
        <Text type="secondary">{t('featureSwitches.workspaceCreationDefault.hint')}</Text>
      </FeatureSwitchCard>

      {!canManage ? <Text type="secondary">{t('featureSwitches.readOnly')}</Text> : null}
    </Flex>
  );
}
