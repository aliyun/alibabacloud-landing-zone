import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  hasRecentUserActivity,
  installUserActivityTracking,
  isUserActive,
  markUserActivity,
  USER_ACTIVITY_WINDOW_MS,
} from './activity';

function setDocumentVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
}

describe('user activity', () => {
  beforeEach(() => {
    markUserActivity(0);
    installUserActivityTracking();
  });

  afterEach(() => {
    delete (document as unknown as Record<string, unknown>).visibilityState;
  });

  it('treats a recent interaction on a visible page as user activity', () => {
    markUserActivity();

    expect(hasRecentUserActivity()).toBe(true);
    expect(isUserActive()).toBe(true);
  });

  it('treats an interaction older than the activity window as idle', () => {
    markUserActivity(Date.now() - USER_ACTIVITY_WINDOW_MS - 1);

    expect(hasRecentUserActivity()).toBe(false);
    expect(isUserActive()).toBe(false);
  });

  it('treats a hidden page as background even right after interaction', () => {
    markUserActivity();
    setDocumentVisibility('hidden');

    expect(hasRecentUserActivity()).toBe(true);
    expect(isUserActive()).toBe(false);
  });

  it('records pointer and keyboard interaction once tracking is installed', () => {
    expect(hasRecentUserActivity()).toBe(false);

    window.dispatchEvent(new Event('pointerdown'));
    expect(hasRecentUserActivity()).toBe(true);

    markUserActivity(0);
    window.dispatchEvent(new Event('keydown'));
    expect(hasRecentUserActivity()).toBe(true);
  });
});
