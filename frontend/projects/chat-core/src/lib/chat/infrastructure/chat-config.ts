import { InjectionToken } from '@angular/core';

export interface ChatConfig {
  /** Prefix of the REST API; '' = same origin (nginx or the dev-server proxy forwards /api). */
  readonly apiBaseUrl: string;
  /** ws:// or wss:// origin of the WebSocket endpoint. */
  readonly wsBaseUrl: string;
}

export const CHAT_CONFIG = new InjectionToken<ChatConfig>('CHAT_CONFIG', {
  providedIn: 'root',
  factory: () => ({
    apiBaseUrl: '',
    wsBaseUrl: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}`,
  }),
});

/** Minimal WebSocket surface, so tests can plug in a fake. */
export interface WebSocketLike {
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export type WebSocketFactory = (url: string) => WebSocketLike;

export const WEB_SOCKET_FACTORY = new InjectionToken<WebSocketFactory>('WEB_SOCKET_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new WebSocket(url),
});
