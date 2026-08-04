import { useEffect, useRef } from 'react';
import { getRealtimeClient } from './client';
import type { EventHandler } from './types';
import { useAuthStore } from '@/shared/auth/store';

interface UseRealtimeOptions {
  onEvent: EventHandler;
  enabled?: boolean;
}

export function useRealtime(channel: string | null, { onEvent, enabled = true }: UseRealtimeOptions) {
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    if (!channel || !enabled) return;
    const client = getRealtimeClient();
    if (client.state === 'disconnected') {
      client.connect(useAuthStore.getState().accessToken || '');
    }
    const sub = client.subscribe(channel, (event) => {
      handlerRef.current(event);
    });
    return () => sub.unsubscribe();
  }, [channel, enabled]);
}
