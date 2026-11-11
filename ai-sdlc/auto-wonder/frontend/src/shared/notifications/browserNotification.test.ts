import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getBrowserNotificationPermission,
  requestBrowserNotificationPermission,
  showBrowserNotification,
} from './browserNotification';

type NotificationStub = {
  permission: string;
  requestPermission: ReturnType<typeof vi.fn>;
};

function installNotification(stub: NotificationStub | null) {
  if (stub === null) {
    delete (window as unknown as { Notification?: unknown }).Notification;
    return;
  }
  Object.defineProperty(window, 'Notification', {
    configurable: true,
    writable: true,
    value: stub,
  });
}

function constructedNotification() {
  return { onclick: null as ((event: Event) => void) | null, close: vi.fn() };
}

function baseInput(overrides: Partial<Parameters<typeof showBrowserNotification>[0]> = {}) {
  return {
    key: 'notification:10002:10018:9',
    title: '工单有新评论',
    body: '请查看评论',
    workspaceId: 10002,
    userId: 10018,
    onClick: vi.fn(),
    ...overrides,
  };
}

afterEach(() => {
  installNotification(null);
  vi.unstubAllGlobals();
});

describe('getBrowserNotificationPermission', () => {
  it('reports unsupported when the environment has no Notification API', () => {
    installNotification(null);
    expect(getBrowserNotificationPermission()).toBe('unsupported');
  });

  it('normalizes the browser permission values', () => {
    installNotification({ permission: 'granted', requestPermission: vi.fn() });
    expect(getBrowserNotificationPermission()).toBe('granted');
    installNotification({ permission: 'denied', requestPermission: vi.fn() });
    expect(getBrowserNotificationPermission()).toBe('denied');
    installNotification({ permission: 'something-else', requestPermission: vi.fn() });
    expect(getBrowserNotificationPermission()).toBe('default');
  });
});

describe('requestBrowserNotificationPermission', () => {
  it('returns unsupported without asking the browser', async () => {
    installNotification(null);
    await expect(requestBrowserNotificationPermission()).resolves.toBe('unsupported');
  });

  it('returns the granted decision without prompting again', async () => {
    const requestPermission = vi.fn().mockResolvedValue('granted');
    installNotification({ permission: 'default', requestPermission });

    await expect(requestBrowserNotificationPermission()).resolves.toBe('granted');
    expect(requestPermission).toHaveBeenCalledTimes(1);
  });

  it('falls back to the current permission when the request throws', async () => {
    installNotification({ permission: 'denied', requestPermission: vi.fn().mockRejectedValue(new Error('insecure')) });

    await expect(requestBrowserNotificationPermission()).resolves.toBe('denied');
  });

  it('falls back to the current permission when the browser returns nothing', async () => {
    installNotification({ permission: 'default', requestPermission: vi.fn().mockResolvedValue(undefined) });

    await expect(requestBrowserNotificationPermission()).resolves.toBe('default');
  });
});

describe('showBrowserNotification', () => {
  it('never constructs without granted permission', async () => {
    const construct = vi.fn();
    for (const permission of ['default', 'denied', 'unsupported'] as const) {
      await expect(showBrowserNotification(baseInput(), {
        permission: () => permission,
        visibility: () => 'hidden',
        claim: vi.fn().mockResolvedValue(true),
        construct,
      })).resolves.toBeNull();
    }
    expect(construct).not.toHaveBeenCalled();
  });

  it('does not notify while the tab is visible', async () => {
    const construct = vi.fn();
    await expect(showBrowserNotification(baseInput(), {
      permission: () => 'granted',
      visibility: () => 'visible',
      claim: vi.fn().mockResolvedValue(true),
      construct,
    })).resolves.toBeNull();
    expect(construct).not.toHaveBeenCalled();
  });

  it('constructs one notification when hidden, granted and the key was claimed', async () => {
    const notification = constructedNotification();
    const construct = vi.fn().mockReturnValue(notification);
    const claim = vi.fn().mockResolvedValue(true);

    const result = await showBrowserNotification(baseInput(), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim,
      construct,
      now: () => 1000,
    });

    expect(result).toBe(notification);
    expect(claim).toHaveBeenCalledWith('notification:10002:10018:9', 1000);
    expect(construct).toHaveBeenCalledWith('工单有新评论', { body: '请查看评论' });
  });

  it('skips when another tab already claimed the key', async () => {
    const construct = vi.fn();
    await expect(showBrowserNotification(baseInput(), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockResolvedValue(false),
      construct,
    })).resolves.toBeNull();
    expect(construct).not.toHaveBeenCalled();
  });

  it('skips when claiming throws or the key is unusable', async () => {
    const construct = vi.fn();
    await expect(showBrowserNotification(baseInput(), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockRejectedValue(new Error('storage gone')),
      construct,
    })).resolves.toBeNull();
    await expect(showBrowserNotification(baseInput({ key: '' }), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockResolvedValue(true),
      construct,
    })).resolves.toBeNull();
    await expect(showBrowserNotification(baseInput({ userId: 0 }), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockResolvedValue(true),
      construct,
    })).resolves.toBeNull();
    expect(construct).not.toHaveBeenCalled();
  });

  it('drops the reminder when the captured identity no longer matches', async () => {
    const construct = vi.fn();
    await expect(showBrowserNotification(baseInput(), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockResolvedValue(true),
      construct,
      resolveIdentity: () => ({ workspaceId: 10002, userId: 10019 }),
    })).resolves.toBeNull();
    await expect(showBrowserNotification(baseInput(), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockResolvedValue(true),
      construct,
      resolveIdentity: () => null,
    })).resolves.toBeNull();
    expect(construct).not.toHaveBeenCalled();
  });

  it('keeps working when constructing the notification throws', async () => {
    await expect(showBrowserNotification(baseInput(), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockResolvedValue(true),
      construct: () => { throw new Error('permission revoked'); },
    })).resolves.toBeNull();
  });

  it('focuses the window and navigates when the system notification is clicked', async () => {
    const notification = constructedNotification();
    const focus = vi.fn();
    const onClick = vi.fn();
    await showBrowserNotification(baseInput({ onClick }), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockResolvedValue(true),
      construct: () => notification as unknown as Notification,
      focus,
    });

    notification.onclick?.({} as Event);

    expect(focus).toHaveBeenCalledTimes(1);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('still navigates when focusing the window fails', async () => {
    const notification = constructedNotification();
    const onClick = vi.fn();
    await showBrowserNotification(baseInput({ onClick }), {
      permission: () => 'granted',
      visibility: () => 'hidden',
      claim: vi.fn().mockResolvedValue(true),
      construct: () => notification as unknown as Notification,
      focus: () => { throw new Error('not focusable'); },
    });

    notification.onclick?.({} as Event);

    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
