import { theme, type ThemeConfig } from 'antd';

export type AppearanceMode = 'dark' | 'light' | 'system';
export type ResolvedAppearance = Exclude<AppearanceMode, 'system'>;
export const APPEARANCE_KEY = 'autowonder.appearance';
export const DEFAULT_APPEARANCE = 'light' as const;
export function normalizeAppearance(value: unknown): AppearanceMode {
  return value === 'dark' || value === 'light' || value === 'system' ? value : DEFAULT_APPEARANCE;
}
export function resolveAppearance(mode: AppearanceMode, systemDark: boolean): ResolvedAppearance {
  return mode === 'system' ? (systemDark ? 'dark' : 'light') : mode;
}
function rgb(hex: string) { return [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16)); }
function luminance(hex: string) {
  const c = rgb(hex).map(v => { const n = v / 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; });
  return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
}
export function contrastRatio(a: string, b: string) {
  const x = luminance(a), y = luminance(b);
  return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
}
function mix(hex: string, target: string, amount: number) {
  const end = rgb(target);
  return '#' + rgb(hex).map((v, i) => Math.round(v * (1 - amount) + end[i] * amount).toString(16).padStart(2, '0')).join('');
}
export function getAppearanceVariables(mode: ResolvedAppearance, color: string): Record<string, string> {
  const dark = mode === 'dark';
  const accent = /^#[\da-f]{6}$/i.test(color) ? color.toLowerCase() : '#f97316';
  const panel = dark ? '#1b2329' : '#ffffff';
  // Preserve the chosen brand color in both modes; adapt foreground ink, not the fill.
  const darkInk = contrastRatio(accent, '#171e23') >= 4.5 ? '#171e23' : '#000000';
  const ink = contrastRatio(accent, '#ffffff') >= contrastRatio(accent, darkInk) ? '#ffffff' : darkInk;
  const away = ink === '#ffffff' ? '#000000' : '#ffffff';
  const primary = accent;
  const accentText = accent;
  return {
    '--aw-accent': accent, '--aw-accent-rgb': rgb(accent).join(','), '--aw-accent-text': accentText,
    '--aw-primary': primary, '--aw-primary-hover': mix(primary, away, .07), '--aw-primary-active': mix(primary, away, .14), '--aw-primary-ink': ink,
    '--aw-bg': dark ? '#171e23' : '#eef2f5', '--aw-panel': panel, '--aw-sidebar': dark ? '#222a31' : '#f8fafc',
    '--aw-raised': dark ? '#263039' : '#f4f7fa', '--aw-field': dark ? '#1a232a' : '#ffffff',
    '--aw-border': dark ? '#34404a' : '#dce3eb', '--aw-text': dark ? '#f1f3f5' : '#182435', '--aw-muted': dark ? '#a3adb9' : '#66758c',
    '--aw-button': dark ? '#28323b' : '#f5f7fa', '--aw-button-hover': dark ? '#34414c' : '#e7edf3', '--aw-button-active': dark ? '#202a32' : '#dbe3ec',
    '--aw-info': dark ? '#8cbcff' : '#175bc0', '--aw-success': dark ? '#49d8a2' : '#08724e',
    '--aw-error': dark ? '#ff938f' : '#bd272c', '--aw-warning': dark ? '#f5cc58' : '#885800',
    '--aw-glass': dark ? 'rgba(32,43,53,.88)' : 'rgba(246,250,255,.88)',
  };
}
export function createAppearanceTheme(mode: ResolvedAppearance, accent: string): ThemeConfig {
  const v = getAppearanceVariables(mode, accent);
  return {
    algorithm: (seed, map) => ({
      ...(mode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm)(seed, map),
      colorPrimary: v['--aw-accent-text'], colorPrimaryHover: v['--aw-accent-text'], colorPrimaryActive: v['--aw-accent-text'],
      colorLink: v['--aw-accent-text'], colorLinkHover: v['--aw-accent-text'], colorLinkActive: v['--aw-accent-text'],
    }),
    token: {
      colorPrimary: v['--aw-accent-text'], colorLink: v['--aw-accent-text'], colorLinkHover: v['--aw-accent-text'], colorLinkActive: v['--aw-accent-text'], colorBgBase: v['--aw-bg'], colorBgLayout: v['--aw-bg'],
      colorBgContainer: v['--aw-panel'], colorBgElevated: v['--aw-panel'], colorText: v['--aw-text'], colorTextSecondary: v['--aw-muted'],
      colorBorder: v['--aw-border'], colorBorderSecondary: v['--aw-border'], colorFillAlter: v['--aw-raised'],
      colorError: mode === 'dark' ? '#ff827e' : '#ce3034', colorSuccess: mode === 'dark' ? '#49d8a2' : '#078658',
      colorWarning: mode === 'dark' ? '#f5cc58' : '#936600', colorWarningBg: mode === 'dark' ? '#2c2618' : '#fff8e6', colorWarningBorder: mode === 'dark' ? '#554628' : '#eddcab', borderRadius: 8, borderRadiusLG: 12, controlHeight: 38, fontSize: 14,
      fontFamily: '-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif',
    },
    components: {
      Input: { colorBgContainer: v['--aw-field'], hoverBg: v['--aw-field'], activeBg: v['--aw-field'] },
      Segmented: { trackBg: v['--aw-field'], itemSelectedBg: `rgba(${v['--aw-accent-rgb']},.13)`, itemSelectedColor: v['--aw-accent-text'], itemHoverBg: `rgba(${v['--aw-accent-rgb']},.07)`, itemHoverColor: v['--aw-text'] },
      Button: { primaryColor: v['--aw-primary-ink'], colorPrimary: v['--aw-primary'], colorPrimaryHover: v['--aw-primary-hover'], colorPrimaryActive: v['--aw-primary-active'], primaryShadow: 'none', dangerShadow: 'none', defaultShadow: 'none', defaultBg: v['--aw-button'], defaultHoverBg: v['--aw-button-hover'], defaultActiveBg: v['--aw-button-active'], defaultColor: v['--aw-text'], defaultHoverColor: v['--aw-text'], defaultActiveColor: v['--aw-text'], defaultBorderColor: v['--aw-border'], defaultHoverBorderColor: v['--aw-muted'] },
      Layout: { headerBg: v['--aw-sidebar'], siderBg: v['--aw-sidebar'], bodyBg: v['--aw-bg'] },
      Table: { headerBg: v['--aw-raised'], headerColor: v['--aw-muted'], rowHoverBg: v['--aw-button'], cellPaddingBlock: 14, cellPaddingInline: 14 },
      Menu: { itemBg: 'transparent', subMenuItemBg: 'transparent', itemSelectedBg: `rgba(${v['--aw-accent-rgb']},.13)`, itemSelectedColor: v['--aw-accent-text'], itemHoverBg: `rgba(${v['--aw-accent-rgb']},${mode === 'dark' ? '.09' : '.065'})`, itemHeight: 35, itemBorderRadius: 7, groupTitleFontSize: 13, groupTitleColor: v['--aw-accent-text'] },
      Card: { headerFontSize: 18, headerFontSizeSM: 15 },
      Checkbox: { colorPrimary: v['--aw-primary'], colorPrimaryHover: v['--aw-primary-hover'], colorWhite: v['--aw-primary-ink'] },
      Switch: { colorPrimary: v['--aw-primary'], colorPrimaryHover: v['--aw-primary-hover'], colorTextLightSolid: v['--aw-primary-ink'] },
      Radio: { buttonSolidCheckedBg: v['--aw-primary'], buttonSolidCheckedHoverBg: v['--aw-primary-hover'], buttonSolidCheckedActiveBg: v['--aw-primary-active'], buttonSolidCheckedColor: v['--aw-primary-ink'] },
      Select: { selectorBg: v['--aw-field'], optionSelectedBg: `rgba(${v['--aw-accent-rgb']},.14)` },
    },
  };
}
