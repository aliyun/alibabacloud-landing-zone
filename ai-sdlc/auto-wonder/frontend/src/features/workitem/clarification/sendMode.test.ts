import { describe, expect, it } from 'vitest';
import {
  CLARIFICATION_SEND_MODE_KEY,
  DEFAULT_SEND_MODE,
  SEND_MODE_LABELS,
  SEND_MODE_OPTIONS,
  parseSendMode,
  shouldSendOnKey,
} from './sendMode';

describe('parseSendMode', () => {
  it('falls back to Shift+Enter when the user has never set the preference', () => {
    expect(DEFAULT_SEND_MODE).toBe('shift-enter');
    expect(parseSendMode(null)).toBe('shift-enter');
    expect(parseSendMode(undefined)).toBe('shift-enter');
  });

  it('reads back both stored modes', () => {
    expect(parseSendMode('"enter"')).toBe('enter');
    expect(parseSendMode('"shift-enter"')).toBe('shift-enter');
  });

  it('falls back to the default instead of an undefined mode for anything unrecognisable', () => {
    // 不是合法 JSON
    expect(parseSendMode('enter')).toBe('shift-enter');
    // 合法 JSON 但不是这两种模式：可能是后续版本写的别的形状
    expect(parseSendMode('{"mode":"enter"}')).toBe('shift-enter');
    expect(parseSendMode('null')).toBe('shift-enter');
    expect(parseSendMode('123')).toBe('shift-enter');
    expect(parseSendMode('"ENTER"')).toBe('shift-enter');
  });
});

describe('shouldSendOnKey', () => {
  it('sends on Shift+Enter and leaves plain Enter to insert a newline in the default mode', () => {
    expect(shouldSendOnKey('shift-enter', { key: 'Enter', shiftKey: true })).toBe(true);
    expect(shouldSendOnKey('shift-enter', { key: 'Enter', shiftKey: false })).toBe(false);
  });

  it('sends on plain Enter and leaves Shift+Enter to insert a newline after switching', () => {
    expect(shouldSendOnKey('enter', { key: 'Enter', shiftKey: false })).toBe(true);
    expect(shouldSendOnKey('enter', { key: 'Enter', shiftKey: true })).toBe(false);
  });

  it('never treats a non-Enter key as a send in either mode', () => {
    expect(shouldSendOnKey('enter', { key: 'a', shiftKey: false })).toBe(false);
    expect(shouldSendOnKey('shift-enter', { key: 'a', shiftKey: true })).toBe(false);
    expect(shouldSendOnKey('enter', { key: 'NumpadEnter', shiftKey: false })).toBe(false);
  });
});

describe('send mode presentation', () => {
  it('offers exactly the two modes the requirement names, each labelled with itself', () => {
    expect(SEND_MODE_OPTIONS.map((option) => option.value)).toEqual(['shift-enter', 'enter']);
    expect(SEND_MODE_LABELS['shift-enter']).toBe('Shift+回车发送');
    expect(SEND_MODE_LABELS.enter).toBe('回车发送');
  });

  it('stores under the documented user_setting key so backend and frontend agree', () => {
    expect(CLARIFICATION_SEND_MODE_KEY).toBe('clarification_send_mode');
  });
});
