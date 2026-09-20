import { Button } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';

export function HelpCenterLink() {
  return (
    <Button type="text" href="/help" target="_blank" rel="noopener noreferrer" aria-label="帮助中心" icon={<QuestionCircleOutlined />}>
      帮助中心
    </Button>
  );
}
