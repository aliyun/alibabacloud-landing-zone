import { useMemo } from 'react';
import { Alert, Descriptions, Empty, Space, Table, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ColumnsType } from 'antd/es/table';
import type { AiResultRendererProps } from './types';
import type { PrdDesignResult, PrdInterfaceDependency, PrdRequirement } from '@/shared/types/ai';

const { Text, Paragraph, Title } = Typography;

// A separator, not copy: "METHOD path" reads the same in every language, so it lives at module scope
// rather than in the locale files.
const METHOD_PATH_SEPARATOR = ' ';

/**
 * Read-only review of the generated design, shown beside the conversation before the reviewer
 * confirms it as a version.
 *
 * <p>Deliberately not editable: the design data is the single source of truth and the server renders
 * the HTML from it, so hand-editing JSON here would risk the document and requirements drifting apart.
 * Revisions go back through the conversation, which regenerates a coherent whole. What this view must
 * do is make the confirmation honest — requirements and their criteria, blocking questions, and
 * interfaces flagged by whether they already exist — so the reviewer can tell what they are accepting.
 */
export function PrdDesignRenderer({ value: rawValue }: AiResultRendererProps<PrdDesignResult>) {
  const { t } = useTranslation('prd');
  const value = useMemo(() => (rawValue && typeof rawValue === 'object' ? rawValue : {} as PrdDesignResult), [rawValue]);

  const requirements = value.requirements ?? [];
  const openQuestions = value.openQuestions ?? [];
  const interfaces = value.interfaces ?? [];
  const pages = value.pages ?? [];
  const blockingOpen = openQuestions.filter((q) => q.blocking && !q.resolved);

  if (!value.title && requirements.length === 0) {
    return <Empty description={t('renderer.empty')} />;
  }

  const requirementColumns: ColumnsType<PrdRequirement> = [
    {
      title: t('renderer.requirement'),
      dataIndex: 'key',
      align: 'left',
      render: (_key: string, record) => (
        <Space direction="vertical" size={2}>
          <Space size={6} wrap>
            <Text strong>{record.key}</Text>
            {record.priority && <Tag color="blue">{record.priority}</Tag>}
            {record.excluded && <Tag>{t('renderer.excluded')}</Tag>}
          </Space>
          <Text>{record.title}</Text>
          {record.acceptanceCriteria && record.acceptanceCriteria.length > 0 && (
            <Text type="secondary">{t('renderer.criteria', { count: record.acceptanceCriteria.length })}</Text>
          )}
        </Space>
      ),
    },
  ];

  const interfaceColumns: ColumnsType<PrdInterfaceDependency> = [
    { title: t('renderer.interface'), dataIndex: 'name', align: 'left' },
    {
      title: t('renderer.methodPath'),
      dataIndex: 'path',
      align: 'left',
      render: (_path: string, record) => (
        <Text code>{[record.method, record.path].filter(Boolean).join(METHOD_PATH_SEPARATOR)}</Text>
      ),
    },
    {
      title: t('renderer.status'),
      dataIndex: 'status',
      render: (status: string | undefined) => <InterfaceStatusTag status={status} />,
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <Title level={5} style={{ marginBottom: 4 }}>{value.title}</Title>
        {value.goals && <Paragraph type="secondary" style={{ marginBottom: 0 }}>{value.goals}</Paragraph>}
      </div>

      {blockingOpen.length > 0 && (
        <Alert
          type="warning"
          showIcon
          message={t('renderer.blockingQuestions', { count: blockingOpen.length })}
          description={t('renderer.blockingHint')}
        />
      )}

      {value.inScope && value.inScope.length > 0 && (
        <div>
          <Text type="secondary">{t('renderer.inScope')}</Text>
          <div><Space size={[4, 4]} wrap>{value.inScope.map((item) => <Tag key={item}>{item}</Tag>)}</Space></div>
        </div>
      )}

      {value.targetProjectAdaptation && (
        <div>
          <Text type="secondary">{t('renderer.adaptation')}</Text>
          <Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>{value.targetProjectAdaptation}</Paragraph>
        </div>
      )}

      <div>
        <Text type="secondary">{t('renderer.requirementsTitle', { count: requirements.length })}</Text>
        <Table<PrdRequirement>
          rowKey="key"
          size="small"
          pagination={false}
          dataSource={requirements}
          columns={requirementColumns}
          expandable={{
            rowExpandable: (record) => Boolean(record.description || (record.acceptanceCriteria?.length ?? 0) > 0),
            expandedRowRender: (record) => (
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                {record.description && <Paragraph style={{ marginBottom: 0 }}>{record.description}</Paragraph>}
                {record.acceptanceCriteria && record.acceptanceCriteria.length > 0 && (
                  <ul style={{ margin: 0, paddingLeft: 20 }}>
                    {record.acceptanceCriteria.map((criterion) => <li key={criterion}>{criterion}</li>)}
                  </ul>
                )}
              </Space>
            ),
          }}
        />
      </div>

      {pages.length > 0 && (
        <div>
          <Text type="secondary">{t('renderer.pagesTitle', { count: pages.length })}</Text>
          <div><Space size={[4, 4]} wrap>{pages.map((page) => <Tag key={page.pageKey}>{page.name}</Tag>)}</Space></div>
        </div>
      )}

      {interfaces.length > 0 && (
        <div>
          <Text type="secondary">{t('renderer.interfacesTitle')}</Text>
          <Table<PrdInterfaceDependency>
            rowKey={(record) => `${record.method ?? ''} ${record.path ?? record.name}`}
            size="small"
            pagination={false}
            dataSource={interfaces}
            columns={interfaceColumns}
          />
        </div>
      )}

      {openQuestions.length > 0 && (
        <div>
          <Text type="secondary">{t('renderer.openQuestionsTitle', { count: openQuestions.length })}</Text>
          <Descriptions column={1} size="small" bordered>
            {openQuestions.map((question) => (
              <Descriptions.Item
                key={question.key}
                label={
                  <Space size={4}>
                    <Text>{question.key}</Text>
                    {question.blocking && <Tag color="error">{t('renderer.blocking')}</Tag>}
                    {question.resolved && <Tag color="success">{t('renderer.resolved')}</Tag>}
                  </Space>
                }
              >
                <Paragraph style={{ marginBottom: 0 }}>{question.question}</Paragraph>
              </Descriptions.Item>
            ))}
          </Descriptions>
        </div>
      )}

      {value.risks && value.risks.length > 0 && (
        <div>
          <Text type="secondary">{t('renderer.risks')}</Text>
          <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
            {value.risks.map((risk) => <li key={risk}>{risk}</li>)}
          </ul>
        </div>
      )}
    </Space>
  );
}

function InterfaceStatusTag({ status }: { status: string | undefined }) {
  const { t } = useTranslation('prd');
  if (status === 'EXISTING') return <Tag color="success">{t('interface.existing')}</Tag>;
  if (status === 'PLANNED') return <Tag color="processing">{t('interface.planned')}</Tag>;
  return <Tag color="warning">{t('interface.toBeConfirmed')}</Tag>;
}
