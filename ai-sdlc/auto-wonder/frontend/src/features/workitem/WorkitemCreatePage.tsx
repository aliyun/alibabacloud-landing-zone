import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { Card, Form, Input, Select, Button, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useCreateWorkitem } from './hooks';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { priorityMap, workTypeMap } from './constants';

const { TextArea } = Input;

export function WorkitemCreatePage() {
  const { t } = useTranslation('workitem');
  const navigate = useNavigate();
  const [form] = Form.useForm();
  useLocalizedValidation(form);
  const createMut = useCreateWorkitem();
  const accessCommand = useAccessCommand();

  const handleSubmit = (values: {
    workType: string;
    title: string;
    contentMd: string;
    priority: number;
  }) => {
    accessCommand('READ_WRITE', t('actions.create'), async () => {
      try {
        const wi = await createMut.mutateAsync({
          workType: values.workType,
          title: values.title,
          contentMd: values.contentMd,
          priority: values.priority,
        });
        message.success(i18n.t('workitem:messages.created'));
        navigate(`/workitems/${wi.id}`);
      } catch {
        return; // create failed; interceptor already showed the error, stay on form to retry
      }
    });
  };

  return (
    <Card className="aw-content-card" title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to="/workitems" label={t('create.back')} /><span>{t('create.title')}</span></span>} />}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} initialValues={{ workType: 'REQ', priority: 2 }}>
        <Form.Item name="workType" label={t('fields.type')} rules={[{ required: true }]}>
          <Select
            options={Object.entries(workTypeMap).map(([value, meta]) => ({ value, label: meta.label }))}
          />
        </Form.Item>
        <Form.Item name="title" label={t('fields.title')} rules={[{ required: true, message: t('create.titleRequired') }]}>
          <Input placeholder={t('create.titlePlaceholder')} maxLength={200} />
        </Form.Item>
        <Form.Item name="contentMd" label={t('fields.description')} rules={[{ required: true, message: t('create.descriptionRequired') }]}>
          <TextArea rows={6} placeholder={t('create.descriptionPlaceholder')} />
        </Form.Item>
        <Form.Item name="priority" label={t('fields.priority')}>
          <Select
            options={[0, 1, 2, 3].map(value => ({ value, label: t('priority.option', { priority: value, label: priorityMap[value].label }) }))}
          />
        </Form.Item>
        <Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            loading={createMut.isPending}
            style={{ marginRight: 8 }}
          >
            {t('create.submit')}
          </Button>
          <Button onClick={() => navigate('/workitems')}>{t('create.cancel')}</Button>
        </Form.Item>
      </Form>
    </Card>
  );
}
