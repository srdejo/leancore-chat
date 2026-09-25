import { ServerFrame } from '../domain/chat.models';
import { WebSocketFactory, WebSocketLike } from './chat-config';

/** Server close codes that mean "do not retry". */
export const CLOSE_INVALID_PARAMETERS = 4400;
export const CLOSE_CONVERSATION_NOT_FOUND = 4404;

const BASE_DELAY_MS = 500;
const MAX_DELAY_MS = 10_000;

export interface ChatSocketHandlers {
  /** The socket is open; the server starts replaying history, then sends SYNCED. */
  onOpen(): void;
  onFrame(frame: ServerFrame): void;
  /** The socket closed and a reconnection is scheduled. */
  onReconnecting(): void;
  /** Closed for good: by the caller (code undefined) or by a fatal server close. */
  onClosed(code?: number): void;
}

/**
 * One WebSocket with automatic reconnection (exponential backoff with jitter, 0.5 s up to 10 s).
 * The URL is rebuilt on every attempt, so each reconnection carries the current lastSeq.
 */
export class ChatSocket {
  private socket?: WebSocketLike;
  private isOpen = false;
  private attempt = 0;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private closedByCaller = false;

  constructor(
    private readonly factory: WebSocketFactory,
    private readonly urlFor: () => string,
    private readonly handlers: ChatSocketHandlers,
    private readonly random: () => number = Math.random,
  ) {}

  connect(): void {
    this.closedByCaller = false;
    this.open();
  }

  /** Returns false when there is no open socket; the caller keeps the message pending. */
  send(data: string): boolean {
    if (!this.socket || !this.isOpen) {
      return false;
    }
    this.socket.send(data);
    return true;
  }

  close(): void {
    this.closedByCaller = true;
    clearTimeout(this.reconnectTimer);
    const socket = this.socket;
    this.socket = undefined;
    this.isOpen = false;
    socket?.close(1000, 'bye');
    this.handlers.onClosed();
  }

  /** Delay before the given attempt (0-based): half fixed, half random, so clients do not reconnect in lockstep. */
  delayFor(attempt: number): number {
    const base = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** attempt);
    return base / 2 + this.random() * (base / 2);
  }

  private open(): void {
    const socket = this.factory(this.urlFor());
    this.socket = socket;
    socket.onopen = () => {
      if (this.socket !== socket) return;
      this.isOpen = true;
      this.attempt = 0;
      this.handlers.onOpen();
    };
    socket.onmessage = (event) => {
      if (this.socket !== socket) return;
      this.handlers.onFrame(JSON.parse(event.data as string) as ServerFrame);
    };
    socket.onclose = (event) => {
      if (this.socket !== socket) return;
      this.socket = undefined;
      this.isOpen = false;
      if (this.closedByCaller) return;
      if (event.code === CLOSE_CONVERSATION_NOT_FOUND || event.code === CLOSE_INVALID_PARAMETERS) {
        this.handlers.onClosed(event.code);
        return;
      }
      this.scheduleReconnect();
    };
    socket.onerror = () => {
      // An error is always followed by close, which decides whether to reconnect.
    };
  }

  private scheduleReconnect(): void {
    this.handlers.onReconnecting();
    const delay = this.delayFor(this.attempt);
    this.attempt++;
    this.reconnectTimer = setTimeout(() => this.open(), delay);
  }
}
