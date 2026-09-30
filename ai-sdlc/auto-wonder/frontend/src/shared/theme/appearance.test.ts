import { theme } from 'antd';
import { THEME_PRESETS } from '@/features/platform/brandingApi';
import { describe, expect, it } from 'vitest';
import { normalizeAppearance, resolveAppearance, getAppearanceVariables, createAppearanceTheme, contrastRatio } from './appearance';

describe('appearance', () => {
  it('keeps every valid preference, defaults unknown ones to light, and only follows the OS in system mode', () => {
    expect(normalizeAppearance('dark')).toBe('dark');
    expect(normalizeAppearance('light')).toBe('light');
    expect(normalizeAppearance('system')).toBe('system');
    expect(normalizeAppearance(null)).toBe('light');
    expect(normalizeAppearance('broken')).toBe('light');
    expect(resolveAppearance('light', true)).toBe('light');
    expect(resolveAppearance('dark', false)).toBe('dark');
    expect(resolveAppearance('system', true)).toBe('dark');
    expect(resolveAppearance('system', false)).toBe('light');
  });
  it('keeps neutral surfaces and semantic colors independent of customer accent', () => {
    for (const mode of ['dark', 'light'] as const) {
      const orange = getAppearanceVariables(mode, '#f97316');
      const blue = getAppearanceVariables(mode, '#2563eb');
      for (const key of ['--aw-bg','--aw-panel','--aw-text','--aw-border','--aw-info','--aw-success','--aw-error','--aw-warning']) expect(orange[key]).toBe(blue[key]);
      expect(createAppearanceTheme(mode, '#f97316').token?.colorError).toBe(createAppearanceTheme(mode, '#2563eb').token?.colorError);
      for (const key of ['--aw-info','--aw-success','--aw-error','--aw-warning']) expect(contrastRatio(orange[key], orange['--aw-panel'])).toBeGreaterThanOrEqual(4.5);
      expect(orange['--aw-accent']).not.toBe(blue['--aw-accent']);
    }
  });
  it('keeps primary-button text readable for presets and extreme customer colors', () => {
    for (const mode of ['dark', 'light'] as const) for (const accent of ['#f97316','#2563eb','#059669','#4f46e5','#e11d48','#0891b2','#d97706','#7c3aed','#374151','#0f766e','#ffffff','#000000']) {
      const v = getAppearanceVariables(mode, accent);
      for (const key of ['--aw-primary','--aw-primary-hover','--aw-primary-active']) expect(contrastRatio(v[key], v['--aw-primary-ink'])).toBeGreaterThanOrEqual(4.5);
    }
  });
  it('preserves every selected theme color for fills, links and highlights in both modes', () => {
    for (const mode of ['dark', 'light'] as const) for (const accent of [...THEME_PRESETS.map(p => p.primaryColor), '#ffffff', '#000000', '#809010']) {
      const variables = getAppearanceVariables(mode, accent);
      for (const key of ['--aw-accent', '--aw-accent-text', '--aw-primary']) expect(variables[key]).toBe(accent);
      const config = createAppearanceTheme(mode, accent);
      const tokens = theme.getDesignToken(config);
      for (const value of [tokens.colorPrimary, tokens.colorLink, tokens.colorLinkHover, tokens.colorLinkActive]) expect(value).toBe(accent);
      expect(config.components?.Button?.colorPrimary).toBe(accent);
      expect(config.components?.Checkbox?.colorPrimary).toBe(accent);
      expect(config.components?.Switch?.colorPrimary).toBe(accent);
      expect(config.components?.Radio?.buttonSolidCheckedBg).toBe(accent);
      expect(config.components?.Menu?.itemSelectedColor).toBe(accent);
    }
  });
  it('rejects malformed brand colors before using them in CSS', () => {
    expect(getAppearanceVariables('dark', 'url(x)')['--aw-accent']).toBe('#f97316');
  });
});

it('uses the accent tint for shared segmented selection and animation', () => {
  for (const mode of ['dark', 'light'] as const) {
    const v = getAppearanceVariables(mode, '#2563eb');
    expect(createAppearanceTheme(mode, '#2563eb').components?.Segmented).toMatchObject({itemSelectedBg: `rgba(${v['--aw-accent-rgb']},.13)`, itemSelectedColor: v['--aw-accent-text']});
  }
});
