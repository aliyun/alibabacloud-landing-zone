import { Result, Button, Typography } from 'antd';

const { Text } = Typography;

interface PageErrorProps {
  status?: '403' | '404' | '500';
  title?: string;
  subTitle?: string;
  traceId?: string | null;
  onBack?: () => void;
}

export function PageError({ status = '500', title, subTitle, traceId, onBack }: PageErrorProps) {
  return (
    <Result
      status={status}
      title={title || (status === '403' ? '无权限' : status === '404' ? '页面不存在' : '系统错误')}
      subTitle={subTitle}
      extra={[
        onBack && <Button type="primary" key="back" onClick={onBack}>返回</Button>,
        traceId && <Text key="trace" copyable type="secondary" style={{ display: 'block', marginTop: 8 }}>TraceId: {traceId}</Text>,
      ].filter(Boolean)}
    />
  );
}
