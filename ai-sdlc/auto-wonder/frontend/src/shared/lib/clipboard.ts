import { i18n } from '@/shared/i18n';
import copy from 'copy-to-clipboard';

export async function copyTextToClipboard(text: string): Promise<boolean> {
  const clipboard = navigator.clipboard;
  if (clipboard?.writeText) {
    try {
      await clipboard.writeText(text);
      return true;
    } catch {
      // Fall through for insecure contexts and denied clipboard permissions.
    }
  }
  try {
    return copy(text, { message: i18n.t('shared:clipboard.manual') });
  } catch {
    return false;
  }
}
