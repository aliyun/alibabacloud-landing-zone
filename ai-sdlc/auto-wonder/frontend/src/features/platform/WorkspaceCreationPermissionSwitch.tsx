import { Switch, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UserAdminUser } from './userAdminApi';

const EMPTY_TEXT = '—';

interface WorkspaceCreationPermissionSwitchProps {
  user: UserAdminUser;
  pending: boolean;
  onChange: (allowed: boolean) => void;
}

export function WorkspaceCreationPermissionSwitch({
  user,
  pending,
  onChange,
}: WorkspaceCreationPermissionSwitchProps) {
  const { t } = useTranslation('userManagement');
  if (user.status === 'PENDING_REVIEW' || user.status === 'DELETED' || user.status === 'DEACTIVATED') {
    return <Typography.Text type="secondary">{EMPTY_TEXT}</Typography.Text>;
  }

  const label = t('colCanCreate');
  const disabled = pending || user.workspaceCreationEditable !== true;
  const hint = user.workspaceCreationAdminLocked
    ? t('permissionAdminHint')
    : user.workspaceCreationGlobalDefaultAllowed
      ? t('permissionGlobalHint')
      : undefined;
  const toggle = (
    <Switch
      size="small"
      aria-label={label}
      checked={user.canCreateWorkspace === true}
      disabled={disabled}
      onChange={onChange}
      style={disabled && hint ? { pointerEvents: 'none' } : undefined}
    />
  );

  return hint ? (
    <Tooltip title={hint} trigger={['hover', 'focus']}>
      {disabled ? (
        <span
          role="group"
          tabIndex={0}
          aria-label={label}
          style={{ display: 'inline-flex', cursor: 'not-allowed' }}
        >
          {toggle}
        </span>
      ) : toggle}
    </Tooltip>
  ) : toggle;
}
