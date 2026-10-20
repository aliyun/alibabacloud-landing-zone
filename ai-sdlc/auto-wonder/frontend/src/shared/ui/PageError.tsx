import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation('shared');
  return (
    <Result
      status={status}
      title={title || (status === '403' ? t('error.forbidden') : status === '404' ? t('error.notFound') : t('error.system'))}
      subTitle={subTitle}
      extra={[
        onBack && <Button type="primary" key="back" onClick={onBack}>{t('error.back')}</Button>,
        traceId && <Text key="trace" copyable type="secondary" style={{ display: 'block', marginTop: 8 }}>TraceId: {traceId}</Text>,
      ].filter(Boolean)}
    />
  );
}
