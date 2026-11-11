import { ChiefOfStaff } from '@/features/chiefOfStaff/ChiefOfStaff';
import { LanguageSwitch } from '@/shared/i18n/LanguageSwitch';
import { AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import { HelpCenterLink } from '@/shared/ui/HelpCenterLink';
import { NotificationBell } from '@/shared/ui/NotificationBell';
import { PageHeader } from '@/shared/ui/PageHeader';
import { UserMenu } from '@/shared/ui/UserMenu';
import { ChiefEntryButton } from './ChiefEntryButton';

export function WorkspacePageHeader() {
  return <>
    <PageHeader brandTo="/workspaces" actions={<>
      <ChiefEntryButton standalone />
      <HelpCenterLink />
      <LanguageSwitch />
      <AppearanceSwitch />
      <NotificationBell />
      <UserMenu />
    </>} />
    <ChiefOfStaff hideLauncher />
  </>;
}
