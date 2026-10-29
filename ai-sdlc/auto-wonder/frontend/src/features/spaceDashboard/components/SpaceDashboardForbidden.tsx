import { Button, Card } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

/**
 * 无权限访问空间级看板的空态：接口 403 与「直接访问 `/workspaces/dashboard` 但无管理权限」共用，
 * 保证两条路径的文案与返回动作一致。
 */
export function SpaceDashboardForbidden() {
  const { t } = useTranslation('spaceDashboard');
  const navigate = useNavigate();
  return (
    <Card data-testid="space-dashboard-forbidden">
      <div className="space-dashboard-empty-title">{t('state.forbiddenTitle')}</div>
      <p className="space-dashboard-empty-text">{t('state.forbiddenDescription')}</p>
      <Button onClick={() => navigate('/workspaces')}>{t('state.backToWorkspaces')}</Button>
    </Card>
  );
}
