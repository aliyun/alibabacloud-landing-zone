import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Collapse, Descriptions, Drawer, Empty, Space, Spin, Tag, Tooltip } from 'antd';
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import { formatDateTime } from '@/shared/i18n/format';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import {
  getLatestFrontendProjectProbe,
  isProbeActive,
  listFrontendProjectProbes,
  probeFrontendProjects,
  PROBE_STATUS,
  type FrontendProjectProbe,
  type FrontendProjectProfile,
} from './api';

// Git represents an application at the repository root with an empty relative path.
const REPOSITORY_ROOT = '.';

const PROBE_POLL_MS = 3000;
const IDLE_POLL_MS = 60000;

// Placeholders and separators live at module scope so they are not treated as translatable copy:
// a missing value renders the same typographic dash in both languages.
const EMPTY_VALUE = '—';
const LIST_SEPARATOR = ', ';
const UNKNOWN_SEPARATOR = '；';
const COMMIT_PREVIEW_LENGTH = 12;

/**
 * Frontend sub-project detection for one repo: start or re-start a probe, poll while it runs, and
 * show each detected application with its evidence and the fact/inference/unknown split.
 *
 * Progress survives a page refresh because the probe is a durable server-side run; this panel only
 * re-reads the latest run and never owns that state.
 */
export function FrontendProjectsPanel({ repoId }: { repoId: string }) {
  const { t } = useTranslation('repo');
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const [selected, setSelected] = useState<FrontendProjectProfile | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);

  const probeQuery = useQuery({
    queryKey: ['repo-frontend-projects-latest', repoId],
    queryFn: () => getLatestFrontendProjectProbe(repoId),
    refetchInterval: (query) => (isProbeActive(query.state.data?.status) ? PROBE_POLL_MS : IDLE_POLL_MS),
  });

  // History is opt-in: it only loads once the user expands the section.
  const historyQuery = useQuery({
    queryKey: ['repo-frontend-projects-probes', repoId],
    queryFn: () => listFrontendProjectProbes(repoId),
    enabled: historyOpen,
  });

  const probeMutation = useMutation({
    mutationFn: () => probeFrontendProjects(repoId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['repo-frontend-projects-latest', repoId] });
    },
  });

  const probe = probeQuery.data ?? null;
  const profiles = probe?.profiles ?? [];
  const active = isProbeActive(probe?.status);

  const invalidate = () => void queryClient.invalidateQueries({
    queryKey: ['repo-frontend-projects-latest', repoId],
  });

  const startProbe = () => runWithAccess(
    'READ_WRITE',
    t('frontendProjects.probeCommand'),
    () => probeMutation.mutate(),
    'REPO',
  );

  if (probeQuery.isLoading) {
    return <Spin data-testid="frontend-projects-loading" />;
  }

  return (
    <div data-testid="frontend-projects-panel">
      <Alert
        type="info"
        showIcon
        message={t('frontendProjects.title')}
        description={t('frontendProjects.hint')}
      />

      <Space style={{ margin: '16px 0' }}>
        <Button
          type="primary"
          icon={<ThunderboltOutlined aria-hidden="true" />}
          onClick={startProbe}
          loading={probeMutation.isPending}
          disabled={active}
        >
          {probe ? t('frontendProjects.reprobe') : t('frontendProjects.probe')}
        </Button>
        {probe && (
          <Button icon={<ReloadOutlined aria-hidden="true" />} onClick={invalidate}>
            {t('frontendProjects.refresh')}
          </Button>
        )}
      </Space>

      {probeMutation.isError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('frontendProjects.probeFailed')}
          description={t('frontendProjects.probeActive')}
        />
      )}

      {probeQuery.isError && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} message={t('frontendProjects.loadFailed')} />
      )}

      {!probe && !probeQuery.isError && (
        <Empty
          data-testid="frontend-projects-empty"
          description={
            <Space direction="vertical" size={0}>
              <span>{t('frontendProjects.neverProbed')}</span>
              <span>{t('frontendProjects.neverProbedHint')}</span>
            </Space>
          }
        />
      )}

      {probe && (
        <Card
          size="small"
          title={t('frontendProjects.lastProbe')}
          style={{ marginBottom: 16 }}
          data-testid="frontend-projects-probe"
        >
          {active && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message={t('frontendProjects.running')}
              description={t('frontendProjects.runningHint')}
            />
          )}
          {probe.status === PROBE_STATUS.FAILED && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 12 }}
              message={t('frontendProjects.statusFailed')}
              description={probe.error ?? t('frontendProjects.retryHint')}
            />
          )}
          <Descriptions column={3} size="small">
            <Descriptions.Item label={t('frontendProjects.status')}>
              <ProbeStatusTag status={probe.status} />
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.branch')}>{probe.branch || EMPTY_VALUE}</Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.commit')}>
              {probe.commitSha
                ? <EllipsisText tooltip={probe.commitSha}>{probe.commitSha.slice(0, COMMIT_PREVIEW_LENGTH)}</EllipsisText>
                : EMPTY_VALUE}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.candidateCount')}>
              {probe.candidateCount ?? EMPTY_VALUE}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.detectorVersion')}>
              {probe.detectorVersion}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.finishedAt')}>
              {probe.finishedAt ? formatDateTime(probe.finishedAt) : EMPTY_VALUE}
            </Descriptions.Item>
          </Descriptions>
        </Card>
      )}

      {probe?.status === PROBE_STATUS.SUCCEEDED && profiles.length === 0 && (
        <Empty
          description={
            <Space direction="vertical" size={0}>
              <span>{t('frontendProjects.noCandidate')}</span>
              <span>{t('frontendProjects.noCandidateHint')}</span>
            </Space>
          }
        />
      )}

      {profiles.length > 0 && (
        <>
          <Table<FrontendProjectProfile>
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={profiles}
            columns={buildColumns(t, setSelected)}
            onRow={(record) => ({ onClick: () => setSelected(record) })}
          />
          <Alert
            type="info"
            showIcon
            style={{ marginTop: 12 }}
            message={t('frontendProjects.selectHint')}
          />
        </>
      )}

      {probe && (
        <Collapse
          ghost
          style={{ marginTop: 16 }}
          onChange={(keys) => setHistoryOpen(Array.isArray(keys) ? keys.length > 0 : keys !== undefined)}
          items={[{
            key: 'history',
            label: t('frontendProjects.probeHistory'),
            children: historyQuery.isLoading
              ? <Spin size="small" />
              : <ProbeHistory probes={historyQuery.data ?? []} />,
          }]}
        />
      )}

      <ProfileDrawer profile={selected} onClose={() => setSelected(null)} />
    </div>
  );
}

function ProbeStatusTag({ status }: { status: string }) {
  const { t } = useTranslation('repo');
  const color = status === PROBE_STATUS.SUCCEEDED ? 'success'
    : status === PROBE_STATUS.FAILED ? 'error'
      : status === PROBE_STATUS.CANCELED ? 'default'
        : 'processing';
  const label = status === PROBE_STATUS.QUEUED ? t('frontendProjects.statusQueued')
    : status === PROBE_STATUS.RUNNING ? t('frontendProjects.statusRunning')
      : status === PROBE_STATUS.SUCCEEDED ? t('frontendProjects.statusSucceeded')
        : status === PROBE_STATUS.FAILED ? t('frontendProjects.statusFailed')
          : t('frontendProjects.statusCanceled');
  return <Tag color={color}>{label}</Tag>;
}

function buildColumns(
  t: (key: string, options?: Record<string, unknown>) => string,
  onSelect: (profile: FrontendProjectProfile) => void,
): ColumnsType<FrontendProjectProfile> {
  return [
    {
      title: t('frontendProjects.appRoot'),
      dataIndex: 'appRoot',
      align: 'left',
      render: (value: string, record) => (
        <Space size={4}>
          <Button
            type="link"
            size="small"
            style={{ padding: 0 }}
            onClick={(event) => { event.stopPropagation(); onSelect(record); }}
          >
            {value || REPOSITORY_ROOT}
          </Button>
          {record.looksLikeLibrary
            ? <Tag>{t('frontendProjects.libraryTag')}</Tag>
            : <Tag color="blue">{t('frontendProjects.appTag')}</Tag>}
          {record.stale && <Tag color="warning">{t('frontendProjects.staleTag')}</Tag>}
        </Space>
      ),
    },
    {
      title: t('frontendProjects.packageName'),
      dataIndex: 'packageName',
      align: 'left',
      render: (value: string | null) => value || EMPTY_VALUE,
    },
    {
      title: t('frontendProjects.frameworks'),
      dataIndex: 'frameworks',
      align: 'left',
      render: (value: string[]) => value.join(LIST_SEPARATOR) || EMPTY_VALUE,
    },
    {
      title: t('frontendProjects.uiLibraries'),
      dataIndex: 'uiLibraries',
      align: 'left',
      render: (value: string[]) => value.join(LIST_SEPARATOR) || EMPTY_VALUE,
    },
    {
      title: t('frontendProjects.language'),
      dataIndex: 'language',
      render: (value: string | null) => value || EMPTY_VALUE,
    },
    {
      title: t('frontendProjects.unknowns'),
      dataIndex: 'unknowns',
      render: (value: string[]) => (value.length
        ? <Tooltip title={value.join(UNKNOWN_SEPARATOR)}><Tag color="warning">{value.length}</Tag></Tooltip>
        : EMPTY_VALUE),
    },
  ];
}

function ProbeHistory({ probes }: { probes: FrontendProjectProbe[] }) {
  const { t } = useTranslation('repo');
  return (
    <Table<FrontendProjectProbe>
      rowKey="id"
      size="small"
      pagination={false}
      dataSource={probes}
      columns={[
        { title: t('fields.id'), dataIndex: 'id' },
        {
          title: t('frontendProjects.status'),
          dataIndex: 'status',
          render: (status: string) => <ProbeStatusTag status={status} />,
        },
        {
          title: t('frontendProjects.commit'),
          dataIndex: 'commitSha',
          align: 'left',
          render: (value: string | null) => value ?? EMPTY_VALUE,
        },
        {
          title: t('frontendProjects.candidateCount'),
          dataIndex: 'candidateCount',
          render: (value: number | null) => value ?? EMPTY_VALUE,
        },
        {
          title: t('frontendProjects.finishedAt'),
          dataIndex: 'finishedAt',
          render: (value: string | null) => (value ? formatDateTime(value) : EMPTY_VALUE),
        },
      ]}
    />
  );
}

function ProfileDrawer({ profile, onClose }: {
  profile: FrontendProjectProfile | null;
  onClose: () => void;
}) {
  const { t } = useTranslation('repo');
  return (
    <Drawer
      open={profile !== null}
      onClose={onClose}
      title={t('frontendProjects.title')}
      width={720}
      destroyOnClose
    >
      {profile && (
        <Space direction="vertical" size={16} style={{ width: '100%' }} data-testid="frontend-project-detail">
          <Descriptions column={1} size="small">
            <Descriptions.Item label={t('frontendProjects.appRoot')}>{profile.appRoot || REPOSITORY_ROOT}</Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.packageName')}>
              {profile.packageName || EMPTY_VALUE}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.branch')}>{profile.branch || EMPTY_VALUE}</Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.commit')}>
              <EllipsisText tooltip={profile.commitSha}>{profile.commitSha}</EllipsisText>
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.language')}>
              {profile.language || EMPTY_VALUE}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.packageManager')}>
              {profile.packageManager || EMPTY_VALUE}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.buildTools')}>
              {profile.buildTools.join(LIST_SEPARATOR) || EMPTY_VALUE}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.routers')}>
              {profile.routers.join(LIST_SEPARATOR) || EMPTY_VALUE}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.i18nLibraries')}>
              {profile.i18nLibraries.join(LIST_SEPARATOR) || EMPTY_VALUE}
            </Descriptions.Item>
            <Descriptions.Item label={t('frontendProjects.testTools')}>
              {profile.testTools.join(LIST_SEPARATOR) || EMPTY_VALUE}
            </Descriptions.Item>
          </Descriptions>

          <Card size="small" title={t('frontendProjects.factsLabel')}>
            <Table
              rowKey={(row: { fact: string; filePath: string }) => `${row.filePath}::${row.fact}`}
              size="small"
              pagination={false}
              dataSource={profile.evidence}
              columns={[
                { title: t('frontendProjects.evidenceFact'), dataIndex: 'fact', align: 'left' },
                { title: t('frontendProjects.evidenceFile'), dataIndex: 'filePath', align: 'left' },
              ]}
            />
          </Card>

          {profile.inferences.length > 0 && (
            <Card size="small" title={t('frontendProjects.inferencesLabel')}>
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {profile.inferences.map((item) => <li key={item}>{item}</li>)}
              </ul>
            </Card>
          )}

          {profile.unknowns.length > 0 && (
            <Card size="small" title={t('frontendProjects.unknownsLabel')}>
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {profile.unknowns.map((item) => <li key={item}>{item}</li>)}
              </ul>
            </Card>
          )}
        </Space>
      )}
    </Drawer>
  );
}
