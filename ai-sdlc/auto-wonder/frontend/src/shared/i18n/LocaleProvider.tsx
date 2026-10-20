import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { message } from 'antd';
import { useAuthStore } from '@/shared/auth/store';
import { getMySetting, putMySetting } from '@/features/profile/userSettingApi';
import { browserLocale, i18n, normalizeLocale, type Locale } from './index';

const SETTING_KEY = 'ui.locale';
const STORAGE_KEY = 'aw.locale';
const cacheKey = (account: number | null) => account === null ? STORAGE_KEY : `${STORAGE_KEY}.user.${account}`;
function currentAccount() { const state = useAuthStore.getState(); return state.accessToken ? state.user?.id ?? null : null; }
function readLocale(account: number | null): Locale {
  try { const cached = localStorage.getItem(cacheKey(account)); if (cached === 'en-US' || cached === 'zh-CN') return cached; } catch { /* Storage can be disabled. */ }
  return browserLocale();
}
function cacheLocale(account: number | null, locale: Locale) {
  try { localStorage.setItem(cacheKey(account), locale); } catch { /* Keep current-tab switching available. */ }
}
const LocaleContext = createContext<{ locale: Locale; setLocale: (locale: Locale) => void } | null>(null);
export function LocaleProvider({ children }: { children: ReactNode }) {
  const account = useAuthStore(state => state.accessToken ? state.user?.id ?? null : null);
  const [locale, updateLocale] = useState<Locale>(() => readLocale(account));
  const identity = useRef({ account, epoch: 0 });
  if (identity.current.account !== account) identity.current = { account, epoch: identity.current.epoch + 1 };
  const revision = useRef(0);
  const writes = useRef<Promise<unknown>>(Promise.resolve());
  useEffect(() => {
    const sync = () => { const next = normalizeLocale(i18n.language); updateLocale(next); document.documentElement.lang = next; };
    i18n.on('languageChanged', sync);
    sync();
    return () => { i18n.off('languageChanged', sync); };
  }, []);
  useEffect(() => {
    let active = true;
    const started = revision.current;
    void i18n.changeLanguage(readLocale(account));
    if (account !== null) {
      void getMySetting(SETTING_KEY).then(setting => {
        if (!active || currentAccount() !== account || revision.current !== started || !setting.valueJson) return;
        let value: unknown;
        try { value = JSON.parse(setting.valueJson); } catch { return; }
        if (value !== 'zh-CN' && value !== 'en-US') return;
        cacheLocale(account, value);
        void i18n.changeLanguage(value);
      }).catch(() => { /* Cached preference remains available while offline. */ });
    }
    const syncStorage = (event: StorageEvent) => {
      if (event.key !== cacheKey(account) && event.key !== null) return;
      revision.current++;
      void i18n.changeLanguage(event.newValue === 'en-US' || event.newValue === 'zh-CN' ? event.newValue : readLocale(account));
    };
    window.addEventListener('storage', syncStorage);
    return () => { active = false; window.removeEventListener('storage', syncStorage); };
  }, [account]);
  function setLocale(next: Locale) {
    if (next !== 'zh-CN' && next !== 'en-US') return;
    revision.current++;
    cacheLocale(account, next);
    void i18n.changeLanguage(next);
    if (account !== null) {
      const epoch = identity.current.epoch;
      writes.current = writes.current.catch(() => {}).then(() => {
        if (currentAccount() === account && identity.current.epoch === epoch) return putMySetting(SETTING_KEY, JSON.stringify(next));
      }).catch(() => {
        if (currentAccount() === account && identity.current.epoch === epoch) message.error(i18n.t('common:language.syncFailed'));
      });
    }
  }
  return <LocaleContext.Provider value={{ locale, setLocale }}>{children}</LocaleContext.Provider>;
}
export function useLocale(): { locale: Locale; setLocale: (locale: Locale) => void } {
  return useContext(LocaleContext) ?? { locale: normalizeLocale(i18n.language), setLocale: locale => { void i18n.changeLanguage(locale); } };
}
