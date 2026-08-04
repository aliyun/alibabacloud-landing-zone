export interface RealtimeEvent {
  channel: string;
  type: string;
  payload: unknown;
  timestamp: number;
}

export type EventHandler = (event: RealtimeEvent) => void;

export interface Subscription {
  channel: string;
  handler: EventHandler;
  unsubscribe: () => void;
}

export type ConnectionState = 'connecting' | 'connected' | 'disconnected' | 'reconnecting';
