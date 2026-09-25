import { ChatMessage, ServerFrame } from '../domain/chat.models';
import { WebSocketFactory, WebSocketLike } from '../infrastructure/chat-config';

/** Scriptable WebSocket: the test opens, feeds frames and closes it. */
export class FakeWebSocket implements WebSocketLike {
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  readonly sent: string[] = [];
  closed = false;

  constructor(readonly url: string) {}

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.closed = true;
  }

  serverOpen(): void {
    this.onopen?.(new Event('open'));
  }

  serverSends(frame: ServerFrame): void {
    this.onmessage?.({ data: JSON.stringify(frame) } as MessageEvent);
  }

  serverCloses(code = 1006): void {
    this.onclose?.({ code } as CloseEvent);
  }

  sentFrames(): { type: string; clientMessageId: string; content: string }[] {
    return this.sent.map((data) => JSON.parse(data));
  }
}

export class FakeWebSocketServer {
  readonly sockets: FakeWebSocket[] = [];
  readonly factory: WebSocketFactory = (url) => {
    const socket = new FakeWebSocket(url);
    this.sockets.push(socket);
    return socket;
  };

  get last(): FakeWebSocket {
    return this.sockets[this.sockets.length - 1];
  }
}

export function message(seq: number, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    seq,
    clientMessageId: `id-${seq}`,
    senderRole: 'CUSTOMER',
    senderName: 'Ana',
    content: `m${seq}`,
    replyToSeq: null,
    botScopeVersion: null,
    createdAt: '2026-09-24T10:00:00Z',
    ...overrides,
  };
}
