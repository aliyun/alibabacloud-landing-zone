import { Button, Tooltip } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';

/** Icon-only beside navigated page titles; full label on standalone pages. */
export function PageBackButton({ to, label, compact = true }: { to: string; label: string; compact?: boolean }) {
  const navigate = useNavigate();
  const button = <Button aria-label={label} icon={<ArrowLeftOutlined />} onClick={() => navigate(to)} style={{ flexShrink: 0 }}>
    {compact ? undefined : label}
  </Button>;
  return compact ? <Tooltip title={label}>{button}</Tooltip> : button;
}
