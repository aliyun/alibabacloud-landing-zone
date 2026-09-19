import { Button, Card, List, Space, Tag, Typography, Spin } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { listDebugLogs } from '../api';
import type { DebugLogEntry } from '../api';

const { Text } = Typography;

const STATUS_COLOR: Record<string, string> = {
  SUCCEEDED: 'green',
  FAILED: 'red',
  TIMEOUT: 'orange',
  CANCELED: 'default',
  UPLOADED: 'green',
  PENDING: 'blue',
};

function formatSize(sizeBytes: number | null): string {
  if (sizeBytes == null) return '-';
  if (sizeBytes >= 1024 * 1024) return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
  if (sizeBytes >= 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`;
  return `${sizeBytes} B`;
}

interface DebugLogListProps {
  workitemId: string;
}

/** 小队 debug 日志（设计文档 §4.7 查看）：无数据时整卡不渲染，不打扰未开启 debug 的工单。 */
export function DebugLogList({ workitemId }: DebugLogListProps) {
  const { data, isLoading } = useQuery({
    queryKey: ['workitem-debug-logs', workitemId],
    queryFn: () => listDebugLogs(workitemId),
  });
  const logs = data ?? [];
  if (!isLoading && logs.length === 0) {
    return null;
  }
  return (
    <Card size="small" title="Debug 日志" styles={{ body: { padding: '8px 12px' } }}>
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Spin size="small" />
        </div>
      ) : (
        <List
          size="small"
          dataSource={logs}
          renderItem={(log: DebugLogEntry) => (
            <List.Item
              key={log.id}
              actions={log.downloadUrl ? [
                <Button
                  key="download"
                  type="link"
                  size="small"
                  icon={<DownloadOutlined />}
                  href={log.downloadUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  下载
                </Button>,
              ] : []}
            >
              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                <Space size={6}>
                  <Text style={{ fontSize: 13 }}>第 {log.runNo} 轮</Text>
                  {/* dispatchStatus 可能是非终态临时值（RUNNING，relay 补插路径），原样展示 */}
                  <Tag color={STATUS_COLOR[log.dispatchStatus] ?? 'default'}>{log.dispatchStatus}</Tag>
                  <Tag color={STATUS_COLOR[log.status] ?? 'default'}>{log.status}</Tag>
                  {log.truncated ? <Tag color="orange">截断</Tag> : null}
                </Space>
                <Text type="secondary" style={{ fontSize: 11 }}>
                  dispatch #{log.dispatchId} · {formatSize(log.sizeBytes)}
                  {log.uploadChannel ? ` · ${log.uploadChannel}` : ''}
                </Text>
                {/* S9/S12 契约：UPLOADED 行的 errorMessage 是 Writer Close 收尾注记，
                    必须渲染为 warning；只有 FAILED 行才是 danger。不得以 error 非空推断失败。 */}
                {log.errorMessage ? (
                  <Text
                    type={log.status === 'FAILED' ? 'danger' : 'warning'}
                    style={{ fontSize: 11 }}
                  >
                    {log.errorMessage}
                  </Text>
                ) : null}
              </Space>
            </List.Item>
          )}
        />
      )}
    </Card>
  );
}
