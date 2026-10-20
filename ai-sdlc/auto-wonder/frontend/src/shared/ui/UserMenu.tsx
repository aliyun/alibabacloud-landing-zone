import { Dropdown } from 'antd';
import { useTranslation } from 'react-i18next';
import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import { logout } from '@/features/auth/api';
/** Shared signed-in account control for workspace selection and app headers. */
export function UserMenu() {
  const { t } = useTranslation('common');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const refreshToken = useAuthStore((s) => s.refreshToken);
  const clear = useAuthStore((s) => s.clear);
  const avatarText = Array.from(user?.nickname?.trim() || user?.username?.trim() || 'U')[0].toUpperCase();
  const handleLogout = async () => {
    if (refreshToken) {
      try { await logout(refreshToken); } catch { /* ignore */ }
    }
    clear();
    queryClient.clear();
    navigate('/login');
  };

  const userMenuItems = [
    { key: 'profile-settings', label: t('account.settings'), icon: <UserOutlined />, onClick: () => navigate('/profile/settings') },
    { type: 'divider' as const },
    { key: 'logout', label: t('account.logout'), icon: <LogoutOutlined />, onClick: handleLogout },
  ];

  return (
    <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" trigger={['click']}>
      <button type="button" aria-label={t('account.menu')} className="aw-user-avatar aw-initial-mark" style={{
        width: 36, height: 36, borderRadius: '50%', padding: 0, flexShrink: 0,
        display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 600,
        border: '1px solid var(--aw-border)', cursor: 'pointer',
      }}>
        <span aria-hidden="true">{avatarText}</span>
      </button>
    </Dropdown>
  );
}
