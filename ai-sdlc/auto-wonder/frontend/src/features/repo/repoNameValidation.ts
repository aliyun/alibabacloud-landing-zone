import { i18n } from '@/shared/i18n';

export function repoNameInvalidMessage() {
  return i18n.t('repo:validation.nameInvalid');
}

export function isValidRepoName(value?: string): boolean {
  const name = value?.trim();
  if (!name) {
    return true;
  }
  if (name.includes('/') || name.includes('\\')) {
    return false;
  }
  if (name === '.' || name === '..') {
    return false;
  }
  if (/^[a-zA-Z]:/u.test(name)) {
    return false;
  }
  if (name.startsWith('~') || name.startsWith('%')) {
    return false;
  }
  return !/\p{Cc}/u.test(name);
}
