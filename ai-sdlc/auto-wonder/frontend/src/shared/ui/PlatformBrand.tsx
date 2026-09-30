import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING, getPublicBranding } from '@/features/platform/brandingApi';

export function PlatformBrand({ to, collapsed = false }: { to: string; collapsed?: boolean }) {
  const { data: branding = DEFAULT_BRANDING } = useQuery({ queryKey: BRANDING_QUERY_KEY, queryFn: getPublicBranding });
  return <Link to={to} aria-label={`${branding.platformName}，返回${to === '/workspaces' ? '工作空间列表' : '工单列表'}`} style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0, color: 'var(--aw-text)', textDecoration: 'none' }}>
    <img src={branding.logoUrl || '/logo.svg'} alt="" width={28} height={28} style={{ flexShrink: 0, borderRadius: 6, objectFit: 'contain' }} onError={(event) => {
      const image = event.currentTarget;
      if (!image.dataset.fallback) { image.dataset.fallback = '1'; image.src = '/logo.svg'; }
    }} />
    {!collapsed && <strong style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{branding.platformName}</strong>}
  </Link>;
}
