import { Drawer, Descriptions, Tag, Button, Space, Empty, Spin, Alert, Timeline, List, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useDispatch } from '../hooks';
import { statusMeta, HAPPY_PATH, FAILURE_STATES, ACCENT } from '../statusMeta';
import { MarkdownView } from '@/shared/ui/MarkdownView';

const { Text } = Typography;

interface Props {
  dispatchId: number | null;
  open: boolean;
  onClose: () => void;
}

export function ExecutionDetailDrawer({ dispatchId, open, onClose }: Props) {
  const navigate = useNavigate();
  const { data, isLoading } = useDispatch(open ? dispatchId : null);

  const currentIdx = data ? HAPPY_PATH.indexOf(data.status) : -1;
  const isFailure = data ? FAILURE_STATES.includes(data.status) : false;

  const timelineItems = HAPPY_PATH.map((s, i) => {
    const reached = currentIdx >= 0 && i <= currentIdx;
    return {
      color: reached ? ACCENT : '#d9d9d9',
      children: (
        <Text
          style={
            reached
              ? { color: '#333', fontWeight: i === currentIdx ? 600 : 400 }
              : { color: '#bfbfbf' }
          }
        >
          {s}
        </Text>
      ),
    };
  });
  if (isFailure && data) {
    timelineItems.push({
      color: '#ff4d4f',
      children: (
        <Text style={{ color: '#ff4d4f', fontWeight: 600 }}>{data.status}</Text>
      ),
    });
  }

  return (
    <Drawer
      title={
        <span style={{ borderLeft: `4px solid ${ACCENT}`, paddingLeft: 10 }}>
          执行详情{data ? ` · #${data.id}` : ''}
        </span>
      }
      width={640}
      open={open}
      onClose={onClose}
      maskClosable
    >
      {isLoading || !data ? (
        <Spin />
      ) : (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Timeline items={timelineItems} />

          {data.error ? (
            <Alert type="error" message="错误" description={data.error} showIcon />
          ) : null}

          <Descriptions
            column={1}
            size="small"
            bordered
            items={[
              {
                key: 'wi',
                label: '工单',
                children: data.workitemId ? (
                  <a onClick={() => navigate(`/workitems/${data.workitemId}`)}>
                    #{data.workitemId} {data.workitemTitle}
                  </a>
                ) : (
                  '—'
                ),
              },
              { key: 'step', label: 'SDLC 步骤', children: data.sdlcStepId ?? '—' },
              {
                key: 'agent',
                label: 'Agent',
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
              { key: 'exec', label: '执行器', children: data.executorName ?? '—' },
              { key: 'attempt', label: '尝试次数', children: data.attempt ?? '—' },
              {
                key: 'status',
                label: '状态',
                children: (
                  <Tag color={statusMeta(data.status).color}>
                    {statusMeta(data.status).label}
                  </Tag>
                ),
              },
              {
                key: 'result',
                label: '结果摘要',
                children: data.resultSummary ? <MarkdownView content={data.resultSummary} /> : '—',
              },
            ]}
          />

          <div>
            <Text type="secondary">产物 ({data.artifacts?.length ?? 0})</Text>
            {data.artifacts && data.artifacts.length > 0 ? (
              <List
                size="small"
                dataSource={data.artifacts}
                renderItem={(a) => (
                  <List.Item>
                    <Tag color="orange">{a.type}</Tag> {a.name}
                  </List.Item>
                )}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无产物" />
            )}
          </div>

          <Space>
            <Button
              type="primary"
              style={{ background: ACCENT, borderColor: ACCENT }}
              disabled={!data.workitemId}
              onClick={() => navigate(`/workitems/${data.workitemId}`)}
            >
              跳转工单
            </Button>
            <Button disabled={!data.agentId} onClick={() => navigate(`/agents/${data.agentId}`)}>
              查看 Agent
            </Button>
          </Space>
        </Space>
      )}
    </Drawer>
  );
}
