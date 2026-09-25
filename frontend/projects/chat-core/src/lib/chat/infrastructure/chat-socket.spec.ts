import { ChatSocket, ChatSocketHandlers } from './chat-socket';
import { FakeWebSocketServer } from '../testing/fake-web-socket';

describe('ChatSocket', () => {
  let server: FakeWebSocketServer;
  let handlers: ChatSocketHandlers;
  let lastSeq: number;
  let socket: ChatSocket;

  beforeEach(() => {
    vi.useFakeTimers();
    server = new FakeWebSocketServer();
    handlers = {
      onOpen: vi.fn<() => void>(),
      onFrame: vi.fn<ChatSocketHandlers['onFrame']>(),
      onReconnecting: vi.fn<() => void>(),
      onClosed: vi.fn<ChatSocketHandlers['onClosed']>(),
    };
    lastSeq = 0;
    socket = new ChatSocket(server.factory, () => `ws://x/ws?lastSeq=${lastSeq}`, handlers, () => 0.5);
  });

  afterEach(() => vi.useRealTimers());

  it('parses frames and reports the open socket', () => {
    socket.connect();
    server.last.serverOpen();
    server.last.serverSends({ type: 'SYNCED', lastSeq: 0 });

    expect(handlers.onOpen).toHaveBeenCalled();
    expect(handlers.onFrame).toHaveBeenCalledWith({ type: 'SYNCED', lastSeq: 0 });
  });

  it('reconnects with growing delays and the current lastSeq', () => {
    socket.connect();
    server.last.serverOpen();
    lastSeq = 10;

    server.last.serverCloses(1006);
    expect(handlers.onReconnecting).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(374);
    expect(server.sockets).toHaveLength(1);
    vi.advanceTimersByTime(1); // 0.5 s base: 0.25 fixed + 0.5 * 0.25 jitter = 375 ms
    expect(server.sockets).toHaveLength(2);
    expect(server.last.url).toContain('lastSeq=10');

    server.last.serverCloses(1006); // still down: next delay doubles
    vi.advanceTimersByTime(749);
    expect(server.sockets).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(server.sockets).toHaveLength(3);
  });

  it('caps the delay at 10 seconds and resets it after a successful open', () => {
    expect(socket.delayFor(0)).toBe(375);
    expect(socket.delayFor(1)).toBe(750);
    expect(socket.delayFor(10)).toBe(7_500);

    const withMaxJitter = new ChatSocket(server.factory, () => 'ws://x', handlers, () => 1);
    expect(withMaxJitter.delayFor(20)).toBe(10_000);
  });

  it('does not reconnect when the conversation does not exist', () => {
    socket.connect();
    server.last.serverCloses(4404);
    vi.advanceTimersByTime(60_000);

    expect(server.sockets).toHaveLength(1);
    expect(handlers.onClosed).toHaveBeenCalledWith(4404);
  });

  it('does not reconnect after the caller closes it', () => {
    socket.connect();
    server.last.serverOpen();
    socket.close();
    vi.advanceTimersByTime(60_000);

    expect(server.last.closed).toBe(true);
    expect(server.sockets).toHaveLength(1);
    expect(socket.send('x')).toBe(false);
  });

  it('only sends while open', () => {
    socket.connect();
    expect(socket.send('a')).toBe(false);
    server.last.serverOpen();
    expect(socket.send('b')).toBe(true);
    expect(server.last.sent).toEqual(['b']);
  });
});
