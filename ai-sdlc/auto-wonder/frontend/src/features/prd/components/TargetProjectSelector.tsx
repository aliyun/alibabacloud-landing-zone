import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, Select, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { listRepos } from '@/features/repo/api';
import type { PrdDesign, SelectTargetRequest } from '../api';

interface TargetProjectSelectorProps {
  design: PrdDesign;
  onConfirm: (request: SelectTargetRequest) => void;
  confirming: boolean;
  disabled?: boolean;
}

export function TargetProjectSelector({ design, onConfirm, confirming, disabled }: TargetProjectSelectorProps) {
  const { t } = useTranslation('prd');
  const [repoId, setRepoId] = useState<number | undefined>(design.targetRepoId ?? undefined);
  const [appRoot, setAppRoot] = useState(design.targetAppRoot || '.');
  const [notes, setNotes] = useState(design.targetProjectNotes || '');
  useEffect(() => {
    setRepoId(design.targetRepoId ?? undefined);
    setAppRoot(design.targetAppRoot || '.');
    setNotes(design.targetProjectNotes || '');
  }, [design.targetRepoId, design.targetAppRoot, design.targetProjectNotes]);
  const repos = useQuery({
    queryKey: ['prd', 'target', 'repos'],
    queryFn: () => listRepos({ page: 1, size: 100 }),
  });
  return (
    <Card size="small" title={t('target.title')} data-testid="prd-target-selector">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Typography.Text type="secondary">{t('target.manualHint')}</Typography.Text>
        {repos.isError && <Alert type="error" closable message={t('target.repoLoadFailed')} action={<Button type="link" onClick={() => void repos.refetch()}>{t('preview.retry')}</Button>} />}
        <Form layout="vertical">
          <Form.Item label={t('target.repoLabel')} required>
            <Select aria-label={t('target.repoLabel')} value={repoId}
              placeholder={t('target.repoPlaceholder')} loading={repos.isFetching}
              disabled={disabled || confirming} onChange={setRepoId}
              options={(repos.data ?? []).map(repo => ({ value: repo.id, label: repo.name }))} />
          </Form.Item>
          <Form.Item label={t('target.directoryLabel')} required extra={t('target.directoryHint')}>
            <Input aria-label={t('target.directoryLabel')} value={appRoot} maxLength={512}
              disabled={disabled || confirming} onChange={event => setAppRoot(event.target.value)} />
          </Form.Item>
          <Form.Item label={t('target.notesLabel')}>
            <Input.TextArea aria-label={t('target.notesLabel')} value={notes} maxLength={8000}
              rows={4} placeholder={t('target.notesPlaceholder')} disabled={disabled || confirming}
              onChange={event => setNotes(event.target.value)} />
          </Form.Item>
          <Button type="primary" loading={confirming} disabled={disabled || repoId == null || !appRoot.trim()}
            onClick={() => onConfirm({ repoId, appRoot: appRoot.trim(), projectNotes: notes })}>
            {t('target.confirm')}
          </Button>
        </Form>
      </Space>
    </Card>
  );
}
