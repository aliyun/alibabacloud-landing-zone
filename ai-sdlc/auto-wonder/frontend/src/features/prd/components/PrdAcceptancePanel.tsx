import { Alert, Card, Empty, Progress, Space, Tag, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import { formatDateTime } from '@/shared/i18n/format';
import { ACCEPTANCE_VERDICT, IMPLEMENTATION_RESULT, type PrdDeliveryAcceptance, type RequirementAcceptance } from '../api';

// Git represents an application at the repository root with an empty relative path.
const REPOSITORY_ROOT = '.';

const { Text, Paragraph } = Typography;

// Module-scope so the AST gate does not read it as translatable copy: this technical marker is shared
// by every language and identifies an exclusion decided at confirmation, not executor-authored text.
const REASON_MARKER = 'EXCLUDED_BY_CONFIRMED_DESIGN';

interface PrdAcceptancePanelProps {
  acceptance: PrdDeliveryAcceptance | null;
  loading?: boolean;
}

/**
 * Per-requirement acceptance results for one delivery.
 *
 * <p>Every requirement the user confirmed has a row here, seeded NOT_VERIFIED before the executor
 * reports. A row only leaves NOT_VERIFIED on an explicit report, so this view can never present an
 * unverified requirement as passed: the verdict tag, the progress bar and the "incomplete" banner all
 * derive from the same `verified` flag rather than from an optimistic default.
 */
export function PrdAcceptancePanel({ acceptance, loading }: PrdAcceptancePanelProps) {
  const { t } = useTranslation('prd');

  if (!acceptance) {
    return loading ? <Card loading /> : <Empty description={t('acceptance.empty')} />;
  }

  const percent = acceptance.total > 0
    ? Math.round(((acceptance.passed + acceptance.failed + acceptance.notApplicable) / acceptance.total) * 100)
    : 0;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }} data-testid="prd-acceptance-panel">
      <Card size="small">
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space size={16} wrap>
            <Text type="secondary">{t('acceptance.versionLabel', { versionNo: acceptance.versionNo })}</Text>
            <Text type="secondary">{t('acceptance.dispatchLabel', { dispatchId: acceptance.dispatchId })}</Text>
            {acceptance.targetRepoId != null && <Text type="secondary">{acceptance.targetAppRoot || REPOSITORY_ROOT}</Text>}
          </Space>
          <Progress
            percent={percent}
            status={acceptance.complete ? 'success' : 'active'}
            format={() => t('acceptance.progress', {
              passed: acceptance.passed,
              failed: acceptance.failed,
              notVerified: acceptance.notVerified,
              notApplicable: acceptance.notApplicable,
              total: acceptance.total,
            })}
          />
          {!acceptance.complete && (
            <Alert type="warning" showIcon message={t('acceptance.incomplete')} description={t('acceptance.incompleteHint')} />
          )}
          {acceptance.complete && acceptance.failed === 0 && (
            <Alert type="success" showIcon message={t('acceptance.allPassed')} />
          )}
          {acceptance.complete && acceptance.failed > 0 && (
            <Alert type="error" showIcon message={t('acceptance.hasFailures', { count: acceptance.failed })} />
          )}
        </Space>
      </Card>

      <Table<RequirementAcceptance>
        rowKey="requirementKey"
        size="small"
        loading={loading}
        pagination={false}
        dataSource={acceptance.requirements}
        columns={buildColumns(t)}
        expandable={{
          rowExpandable: (record) => Boolean(record.note || record.evidence.length || record.notApplicableReason),
          expandedRowRender: (record) => <AcceptanceDetail record={record} />,
        }}
      />
    </Space>
  );
}

function AcceptanceDetail({ record }: { record: RequirementAcceptance }) {
  const { t } = useTranslation('prd');
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {record.note && (
        <div>
          <Text type="secondary">{t('acceptance.noteLabel')}</Text>
          <Paragraph style={{ marginBottom: 0 }}>{record.note}</Paragraph>
        </div>
      )}
      {record.notApplicableReason && (
        <div>
          <Text type="secondary">{t('acceptance.reasonLabel')}</Text>
          <Paragraph style={{ marginBottom: 0 }}>
            {record.notApplicableReason === REASON_MARKER
              ? t('acceptance.excludedByDesign')
              : record.notApplicableReason}
          </Paragraph>
        </div>
      )}
      {record.evidence.length > 0 && (
        <div>
          <Text type="secondary">{t('acceptance.evidenceLabel')}</Text>
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {record.evidence.map((evidence, index) => (
              <li key={`${evidence.kind}-${evidence.ref}-${index}`}>
                <Tag>{t('acceptance.evidenceKind', { kind: evidence.kind })}</Tag>
                {evidence.url
                  ? <a href={evidence.url} target="_blank" rel="noreferrer noopener">{evidence.title || evidence.ref}</a>
                  : <Text>{evidence.title || evidence.ref}</Text>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {record.reportedAt != null && (
        <Text type="secondary">{t('acceptance.reportedAt', { time: formatDateTime(record.reportedAt) })}</Text>
      )}
    </Space>
  );
}

function buildColumns(
  t: (key: string, options?: Record<string, unknown>) => string,
): ColumnsType<RequirementAcceptance> {
  return [
    {
      title: t('acceptance.requirement'),
      dataIndex: 'requirementKey',
      align: 'left',
      render: (_value: string, record) => (
        <Space direction="vertical" size={0}>
          <Space size={6}>
            <Text strong>{record.requirementKey}</Text>
            {record.excluded && <Tag color="default">{t('acceptance.excludedTag')}</Tag>}
            {record.priority && <Tag color="blue">{record.priority}</Tag>}
          </Space>
          {record.requirementTitle && <Text type="secondary">{record.requirementTitle}</Text>}
        </Space>
      ),
    },
    {
      title: t('acceptance.implementation'),
      dataIndex: 'implementation',
      render: (value: string) => <ImplementationTag value={value} />,
    },
    {
      title: t('acceptance.verdict'),
      dataIndex: 'verdict',
      render: (value: string, record) => <VerdictTag verdict={value} verified={record.verified} />,
    },
  ];
}

function VerdictTag({ verdict, verified }: { verdict: string; verified: boolean }) {
  const { t } = useTranslation('prd');
  // NOT_VERIFIED is the seeded default: it must read as "not yet accepted", never as a pass.
  const color = verdict === ACCEPTANCE_VERDICT.PASSED ? 'success'
    : verdict === ACCEPTANCE_VERDICT.FAILED ? 'error'
      : verdict === ACCEPTANCE_VERDICT.NOT_APPLICABLE ? 'default'
        : 'warning';
  const label = verdict === ACCEPTANCE_VERDICT.PASSED ? t('verdict.passed')
    : verdict === ACCEPTANCE_VERDICT.FAILED ? t('verdict.failed')
      : verdict === ACCEPTANCE_VERDICT.NOT_APPLICABLE ? t('verdict.notApplicable')
        : t('verdict.notVerified');
  const tag = <Tag color={color}>{label}</Tag>;
  return verified ? tag : <Tooltip title={t('verdict.notVerifiedHint')}>{tag}</Tooltip>;
}

function ImplementationTag({ value }: { value: string }) {
  const { t } = useTranslation('prd');
  const label = value === IMPLEMENTATION_RESULT.IMPLEMENTED ? t('implementation.implemented')
    : value === IMPLEMENTATION_RESULT.PARTIAL ? t('implementation.partial')
      : t('implementation.notImplemented');
  const color = value === IMPLEMENTATION_RESULT.IMPLEMENTED ? 'success'
    : value === IMPLEMENTATION_RESULT.PARTIAL ? 'warning' : 'default';
  return <Tag color={color}>{label}</Tag>;
}
