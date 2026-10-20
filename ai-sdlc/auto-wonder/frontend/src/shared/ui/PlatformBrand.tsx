import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { BRANDING_QUERY_KEY, getPublicBranding } from '@/features/platform/brandingApi';

export function PlatformBrand({ to, collapsed = false }: { to: string; collapsed?: boolean }) {
  const { t } = useTranslation('shared');
  const { data: branding } = useQuery({ queryKey: BRANDING_QUERY_KEY, queryFn: getPublicBranding });
  if (!branding) {
    return <span aria-hidden="true" style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
      <span style={{ width: 28, height: 28, flexShrink: 0, borderRadius: 6, background: 'var(--aw-raised)' }} />
      {!collapsed && <span style={{ width: 96, height: 14, borderRadius: 4, background: 'var(--aw-raised)' }} />}
    </span>;
  }
  return <Link to={to} aria-label={t(to === '/workspaces' ? 'brand.workspaces' : 'brand.workitems', { name: branding.platformName })} style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0, color: 'var(--aw-text)', textDecoration: 'none' }}>
    <img src={branding.logoUrl || '/logo.svg'} alt="" width={28} height={28} style={{ flexShrink: 0, borderRadius: 6, objectFit: 'contain' }} onError={(event) => {
      const image = event.currentTarget;
      if (!image.dataset.fallback) { image.dataset.fallback = '1'; image.src = '/logo.svg'; }
    }} />
    {!collapsed && <strong style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{branding.platformName}</strong>}
  </Link>;
}
