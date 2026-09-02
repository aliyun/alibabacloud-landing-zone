import type { RealtimeEvent, EventHandler, Subscription, ConnectionState } from './types';

function normalizeWsProtocol(protocol: string): string {
  if (protocol === 'https:') return 'wss:';
  if (protocol === 'http:') return 'ws:';
  return protocol;
}

type RealtimeEnv = { VITE_WS_URL?: string; VITE_WS_BASE?: string };

const defaultRealtimeEnv = import.meta.env as RealtimeEnv;

export function getConfiguredRealtimeBase(env: RealtimeEnv = defaultRealtimeEnv): string | undefined {
  return env.VITE_WS_URL?.trim() || env.VITE_WS_BASE?.trim() || undefined;
}

export function buildRealtimeUrl(base = getConfiguredRealtimeBase()): string {
  const currentHref = typeof window !== 'undefined' ? window.location.href : 'http://localhost/';
  const currentUrl = new URL(currentHref);
  const trimmedBase = base?.trim();

  let wsUrl: URL;
  if (trimmedBase) {
    const isAbsolute = /^[a-z]+:\/\//i.test(trimmedBase);
    wsUrl = new URL(isAbsolute ? trimmedBase : trimmedBase.startsWith('/') ? trimmedBase : `/${trimmedBase}`, currentUrl);
  } else {
    wsUrl = new URL(currentUrl.origin);
  }

  wsUrl.protocol = normalizeWsProtocol(wsUrl.protocol === ':' ? currentUrl.protocol : wsUrl.protocol);
  const normalizedPath = wsUrl.pathname.replace(/\/+$/, '');
  wsUrl.pathname = normalizedPath.endsWith('/ws') ? normalizedPath || '/ws' : `${normalizedPath || ''}/ws`;
  return wsUrl.toString();
}

export class RealtimeClient {
  private url: string;
  private ws: WebSocket | null = null;
  private token: string | null = null;
  private subscribers = new Map<string, Set<EventHandler>>();
  private serverSubscribedChannels = new Set<string>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempt = 0;
  private maxReconnectDelay = 30000;
  private _state: ConnectionState = 'disconnected';
  private stateListeners = new Set<(s: ConnectionState) => void>();

  constructor(url: string) {
    this.url = url;
  }

  get state(): ConnectionState {
    return this._state;
  }

  onStateChange(listener: (s: ConnectionState) => void): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  private setState(s: ConnectionState) {
    this._state = s;
    this.stateListeners.forEach((l) => l(s));
  }

  connect(token: string) {
    this.token = token;
    this.doConnect();
  }

  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
    this.serverSubscribedChannels.clear();
    this.setState('disconnected');
  }

  subscribe(channel: string, handler: EventHandler): Subscription {
    const isNewChannel = !this.subscribers.has(channel);
    if (!this.subscribers.has(channel)) {
      this.subscribers.set(channel, new Set());
    }
    this.subscribers.get(channel)!.add(handler);

    if (isNewChannel && this.isServerSubscriptionChannel(channel)) {
      this.sendServerSubscribe(channel);
    }

    return {
      channel,
      handler,
      unsubscribe: () => {
        const set = this.subscribers.get(channel);
        if (set) {
          set.delete(handler);
          if (set.size === 0) {
            this.subscribers.delete(channel);
            if (this.isServerSubscriptionChannel(channel)) {
              this.sendServerUnsubscribe(channel);
            }
          }
        }
      },
    };
  }

  private isServerSubscriptionChannel(channel: string): boolean {
    return channel.startsWith('conversation:') || channel.startsWith('scheduled-run:');
  }

  private sendServerSubscribe(channel: string) {
    this.serverSubscribedChannels.add(channel);
    this.sendFrame({ type: 'subscribe', channel });
  }

  private sendServerUnsubscribe(channel: string) {
    this.serverSubscribedChannels.delete(channel);
    this.sendFrame({ type: 'unsubscribe', channel });
  }

  private sendFrame(frame: Record<string, unknown>) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(frame));
    }
  }

  private resubscribeAll() {
    for (const channel of this.serverSubscribedChannels) {
      this.sendFrame({ type: 'subscribe', channel });
    }
  }

  private doConnect() {
    this.setState('connecting');
    this.ws = new WebSocket(this.url);

    this.ws.onopen = () => {
      this.ws!.send(JSON.stringify({ type: 'auth', token: this.token }));
      this.reconnectAttempt = 0;
      this.setState('connected');
      this.resubscribeAll();
    };

    this.ws.onmessage = (e: MessageEvent) => {
      try {
        const event = JSON.parse(e.data) as RealtimeEvent;
        const handlers = this.subscribers.get(event.channel);
        if (handlers) {
          handlers.forEach((h) => h(event));
        }
      } catch { /* malformed message, skip */ }
    };

    this.ws.onclose = () => {
      if (this._state === 'disconnected') return;
      this.scheduleReconnect();
    };

    this.ws.onerror = () => {
      this.ws?.close();
    };
  }

  private scheduleReconnect() {
    this.setState('reconnecting');
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempt), this.maxReconnectDelay);
    this.reconnectAttempt++;
    this.reconnectTimer = setTimeout(() => {
      this.doConnect();
    }, delay);
  }
}

let _instance: RealtimeClient | null = null;

export function getRealtimeClient(): RealtimeClient {
  if (!_instance) {
    _instance = new RealtimeClient(buildRealtimeUrl());
  }
  return _instance;
}
