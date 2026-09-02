import { useEffect, useRef } from 'react';
import { getRealtimeClient } from './client';
import type { EventHandler } from './types';
import { useAuthStore } from '@/shared/auth/store';

interface UseRealtimeOptions {
  onEvent: EventHandler;
  enabled?: boolean;
  onReconnect?: () => void;
}

export function useRealtime(channel: string | null, { onEvent, enabled = true, onReconnect }: UseRealtimeOptions) {
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;
  const reconnectRef = useRef(onReconnect);
  reconnectRef.current = onReconnect;

  useEffect(() => {
    if (!channel || !enabled) return;
    const client = getRealtimeClient();
    if (client.state === 'disconnected') {
      client.connect(useAuthStore.getState().accessToken || '');
    }
    const sub = client.subscribe(channel, (event) => {
      handlerRef.current(event);
    });
    let previouslyConnected = client.state === 'connected';
    const unsubscribeState = client.onStateChange((state) => {
      if (state === 'connected' && !previouslyConnected) reconnectRef.current?.();
      previouslyConnected = state === 'connected';
    });
    return () => { sub.unsubscribe(); unsubscribeState(); };
  }, [channel, enabled]);
}
