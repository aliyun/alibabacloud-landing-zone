import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useState, type ReactNode } from 'react';
import { Button, ConfigProvider, Dropdown, message } from 'antd';
import { CheckOutlined, DesktopOutlined, MoonOutlined, SunOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import { APPEARANCE_KEY, DEFAULT_APPEARANCE, createAppearanceTheme, getAppearanceVariables, normalizeAppearance, resolveAppearance, type AppearanceMode, type ResolvedAppearance } from './appearance';
import './crystal.css';

const AppearanceContext = createContext<{ mode: AppearanceMode; resolved: ResolvedAppearance; setMode: (mode: AppearanceMode) => void }>({ mode: DEFAULT_APPEARANCE, resolved: DEFAULT_APPEARANCE, setMode: () => {} });
export const useAppearance = () => useContext(AppearanceContext);
function readMode() { try { return normalizeAppearance(localStorage.getItem(APPEARANCE_KEY)); } catch { return DEFAULT_APPEARANCE; } }
export function AppearanceProvider({ accent, children }: { accent: string; children: ReactNode }) {
  const [mode, updateMode] = useState<AppearanceMode>(readMode);
  const [systemDark, setSystemDark] = useState(() => window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false);
  const resolved = resolveAppearance(mode, systemDark);
  const variables = useMemo(() => getAppearanceVariables(resolved, accent), [resolved, accent]);
  const config = useMemo(() => createAppearanceTheme(resolved, accent), [resolved, accent]);
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    const syncSystem = () => setSystemDark(media?.matches ?? false);
    const syncStorage = (event: StorageEvent) => { if (event.key === APPEARANCE_KEY || event.key === null) updateMode(normalizeAppearance(event.newValue)); };
    media?.addEventListener('change', syncSystem);
    window.addEventListener('storage', syncStorage);
    syncSystem();
    return () => { media?.removeEventListener('change', syncSystem); window.removeEventListener('storage', syncStorage); };
  }, []);
  useLayoutEffect(() => {
    document.documentElement.dataset.appearance = resolved;
    Object.entries(variables).forEach(([key, value]) => document.documentElement.style.setProperty(key, value));
  }, [resolved, variables]);
  useLayoutEffect(() => {
    ConfigProvider.config({ holderRender: holder => <ConfigProvider locale={zhCN} theme={config}>{holder}</ConfigProvider> });
  }, [config]);
  function setMode(next: AppearanceMode) {
    updateMode(next);
    try { localStorage.setItem(APPEARANCE_KEY, next); } catch { /* Browser policy may deny persistence; current-tab switching still works. */ }
  }
  return <AppearanceContext.Provider value={{ mode, resolved, setMode }}><ConfigProvider locale={zhCN} theme={config}>{children}</ConfigProvider></AppearanceContext.Provider>;
}
export function AppearanceSwitch() {
  const { mode, resolved, setMode } = useAppearance();
  const options = [
    { key: 'dark', title: '深色', icon: <MoonOutlined /> },
    { key: 'light', title: '浅色', icon: <SunOutlined /> },
    { key: 'system', title: '跟随系统', icon: <DesktopOutlined /> },
  ];
  return <Dropdown trigger={['click']} placement="bottomRight" menu={{ selectable: true, selectedKeys: [mode], onClick: ({ key }) => {
    setMode(normalizeAppearance(key));
    message.info({ key: 'appearance-scope', content: '外观仅在当前浏览器生效。' });
  }, items: options.map(({ key, title, icon }) => ({ key, icon, label: <span className="appearance-option">{title}{mode === key && <CheckOutlined />}</span> })) }}>
    <Button className="appearance-switch" aria-label="切换外观" title={`外观：${options.find(item => item.key === mode)?.title}`} icon={mode === 'system' ? <DesktopOutlined /> : resolved === 'dark' ? <MoonOutlined /> : <SunOutlined />} />
  </Dropdown>;
}
