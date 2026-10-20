import { useTranslation } from 'react-i18next';
import { Card, Button, Space } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { AiSessionPanel } from '@/shared/ui/AiSessionPanel';

export function MemoryImportPage() {
  const { t } = useTranslation('memory');
  const navigate = useNavigate();
  return (
    <div style={{ padding: 24 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined aria-hidden="true" />} onClick={() => navigate('/memories')}>{t('actions.back')}</Button>
      </Space>
      <Card title={t('import.title')} styles={{ body: { height: '70vh', padding: 0 } }}>
        <AiSessionPanel
          scene="MEMORY_IMPORT"
          bizRefType='ORG'
          bizRefId={0}
          onConfirm={() => navigate('/memories')}
        />
      </Card>
    </div>
  );
}
