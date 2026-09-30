import { PlatformBrand } from './PlatformBrand';
import type { ReactNode } from 'react';
import { AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import { HelpCenterLink } from './HelpCenterLink';
import { UserMenu } from './UserMenu';

/** Same top-right alignment for app pages and standalone pages. */
export function PageHeader({ children, actions, brandTo = '/workitems' }: { children?: ReactNode; actions?: ReactNode; brandTo?: string }) {
  return <header className="aw-page-header">
    <div className="aw-page-header-context">{children ?? <PlatformBrand to={brandTo} />}</div>
    <div className="aw-page-header-actions">{actions ?? <><HelpCenterLink /><AppearanceSwitch /><UserMenu /></>}</div>
  </header>;
}
