import { useTranslation } from 'react-i18next';
import { formatNumber } from '@/shared/i18n/format';
import { Drawer, Descriptions, Tag, Button, Space, Empty, Spin, Alert, Timeline, List, Typography } from 'antd';
import { CloseCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useDispatch } from '../hooks';
import { statusMeta, HAPPY_PATH, FAILURE_STATES, ACCENT } from '../statusMeta';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import './executionDetailDrawer.css';

const { Text } = Typography;

interface Props {
  dispatchId: number | null;
  open: boolean;
  onClose: () => void;
}

export function ExecutionDetailDrawer({ dispatchId, open, onClose }: Props) {
  const { t } = useTranslation('execution');
  const navigate = useNavigate();
  const { data, isLoading } = useDispatch(open ? dispatchId : null);

  const currentIdx = data ? HAPPY_PATH.indexOf(data.status) : -1;
  const isFailure = data ? FAILURE_STATES.includes(data.status) : false;

  const timelineItems = HAPPY_PATH.map((s, i) => {
    const reached = currentIdx >= 0 && i <= currentIdx;
    return {
      color: reached ? ACCENT : 'var(--aw-border)',
      children: (
        <Text
          style={
            reached
              ? { color: 'var(--aw-text)', fontWeight: i === currentIdx ? 600 : 400 }
              : { color: 'var(--aw-muted)' }
          }
        >
          {statusMeta(s).label}
        </Text>
      ),
    };
  });
  if (isFailure && data) {
    timelineItems.push({
      color: 'var(--aw-error)',
      children: (
        <Text style={{ color: 'var(--aw-error)', fontWeight: 600 }}>{statusMeta(data.status).label}</Text>
      ),
    });
  }

  return (
    <Drawer
      title={
        <span style={{ borderLeft: `4px solid ${ACCENT}`, paddingLeft: 10 }}>
          {data ? t('detail.titleWithId', { id: data.id }) : t('detail.title')}
        </span>
      }
      width={640}
      open={open}
      onClose={onClose}
      closable={{ 'aria-label': t('detail.close') }}
      maskClosable
    >
      {isLoading || !data ? (
        <Spin aria-label={t('detail.loading')} />
      ) : (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Timeline items={timelineItems} />

          {data.error ? (
            <Alert type="error" message={t('detail.error')} description={data.error} showIcon icon={<CloseCircleOutlined aria-hidden="true" />} />
          ) : null}

          <Descriptions
            column={1}
            size="small"
            bordered
            className="execution-detail-descriptions"
            styles={{ label: { width: 100 } }}
            items={[
              {
                key: 'wi',
                label: t('detail.workitem'),
                children: data.workitemId ? (
                  <a
                    title={`#${data.workitemId} ${data.workitemTitle}`}
                    style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                    onClick={() => navigate(`/workitems/${data.workitemId}`)}
                  >
                    #{data.workitemId} {data.workitemTitle}
                  </a>
                ) : (
                  '—'
                ),
              },
              { key: 'step', label: t('detail.step'), children: data.sdlcStepId ?? '—' },
              {
                key: 'agent',
                label: t('detail.agent'),
                children: data.agentName ? (
                  <>
                    {data.agentName}{' '}
                    {data.agentVersionNo ? (
                      <Text type="secondary">v{data.agentVersionNo}</Text>
                    ) : null}
                  </>
                ) : (
                  '—'
                ),
              },
              { key: 'exec', label: t('detail.executor'), children: data.executorName ?? '—' },
              { key: 'attempt', label: t('detail.attempt'), children: data.attempt ?? '—' },
              {
                key: 'status',
                label: t('detail.status'),
                children: (
                  <Tag color={statusMeta(data.status).color}>
                    {statusMeta(data.status).label}
                  </Tag>
                ),
              },
              {
                key: 'result',
                label: t('detail.summary'),
                children: data.resultSummary ? <MarkdownView content={data.resultSummary} /> : '—',
              },
            ]}
          />

          <div>
            <Text type="secondary">{t('detail.artifacts', { count: data.artifacts?.length ?? 0, total: formatNumber(data.artifacts?.length ?? 0) })}</Text>
            {data.artifacts && data.artifacts.length > 0 ? (
              <List
                size="small"
                dataSource={data.artifacts}
                renderItem={(a) => (
                  <List.Item style={{ paddingInline: 0 }}>
                    <Tag color="orange">{a.type}</Tag> {a.name}
                  </List.Item>
                )}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('detail.noArtifacts')} />
            )}
          </div>

          <Space>
            <Button
              type="primary"
              disabled={!data.workitemId}
              onClick={() => navigate(`/workitems/${data.workitemId}`)}
            >
              {t('detail.openWorkitem')}
            </Button>
            <Button disabled={!data.agentId} onClick={() => navigate(`/agents/${data.agentId}`)}>
              {t('detail.viewAgent')}
            </Button>
          </Space>
        </Space>
      )}
    </Drawer>
  );
}
