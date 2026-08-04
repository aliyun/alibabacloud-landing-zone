import { Card, Form, Input, Select, Button, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useCreateWorkitem } from './hooks';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { TextArea } = Input;

export function WorkitemCreatePage() {
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const createMut = useCreateWorkitem();
  const accessCommand = useAccessCommand();

  const handleSubmit = (values: {
    workType: string;
    title: string;
    contentMd: string;
    priority: number;
  }) => {
    accessCommand('READ_WRITE', '创建工单', async () => {
      try {
        const wi = await createMut.mutateAsync({
          workType: values.workType,
          title: values.title,
          contentMd: values.contentMd,
          priority: values.priority,
        });
        message.success('工单创建成功');
        navigate(`/workitems/${wi.id}`);
      } catch {
        return; // create failed; interceptor already showed the error, stay on form to retry
      }
    });
  };

  return (
    <Card title="新建工单" style={{ maxWidth: 720, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} initialValues={{ workType: 'REQ', priority: 2 }}>
        <Form.Item name="workType" label="类型" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'REQ', label: '需求' },
              { value: 'TASK', label: '任务' },
              { value: 'BUG', label: '缺陷' },
            ]}
          />
        </Form.Item>
        <Form.Item name="title" label="标题" rules={[{ required: true, message: '标题不能为空' }]}>
          <Input placeholder="请输入工单标题" maxLength={200} />
        </Form.Item>
        <Form.Item name="contentMd" label="描述" rules={[{ required: true, message: '描述不能为空' }]}>
          <TextArea rows={6} placeholder="请输入工单描述（支持 Markdown）" />
        </Form.Item>
        <Form.Item name="priority" label="优先级">
          <Select
            options={[
              { value: 0, label: 'P0 - 紧急' },
              { value: 1, label: 'P1 - 高' },
              { value: 2, label: 'P2 - 中' },
              { value: 3, label: 'P3 - 低' },
            ]}
          />
        </Form.Item>
        <Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            loading={createMut.isPending}
            style={{ marginRight: 8 }}
          >
            创建
          </Button>
          <Button onClick={() => navigate('/workitems')}>取消</Button>
        </Form.Item>
      </Form>
    </Card>
  );
}
