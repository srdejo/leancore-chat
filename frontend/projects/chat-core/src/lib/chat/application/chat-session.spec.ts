import { effect } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ChatSession, GAP_GRACE_MS } from './chat-session';
import { ConversationApi } from '../infrastructure/conversation-api';
import { CHAT_CONFIG, WEB_SOCKET_FACTORY } from '../infrastructure/chat-config';
import { FakeWebSocketServer, message } from '../testing/fake-web-socket';

describe('ChatSession', () => {
  let server: FakeWebSocketServer;
  let api: { messages: ReturnType<typeof vi.fn> };
  let session: ChatSession;

  beforeEach(() => {
    vi.useFakeTimers();
    server = new FakeWebSocketServer();
    api = { messages: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        ChatSession,
        { provide: ConversationApi, useValue: api },
        { provide: WEB_SOCKET_FACTORY, useValue: server.factory },
        { provide: CHAT_CONFIG, useValue: { apiBaseUrl: '', wsBaseUrl: 'ws://chat' } },
      ],
    });
    session = TestBed.inject(ChatSession);
  });

  afterEach(() => vi.useRealTimers());

  function openSynced(lastSeq = 0): void {
    session.open('c1', 'CUSTOMER', 'Ana María');
    server.last.serverOpen();
    server.last.serverSends({ type: 'SYNCED', lastSeq });
  }

  it('opens the socket with role, name and lastSeq, and is connected only after SYNCED', () => {
    session.open('c1', 'CUSTOMER', 'Ana María');
    expect(server.last.url).toBe('ws://chat/ws/conversations/c1?role=CUSTOMER&name=Ana+Mar%C3%ADa&lastSeq=0');
    server.last.serverOpen();
    expect(session.connection()).toBe('reconnecting');
    server.last.serverSends({ type: 'SYNCED', lastSeq: 0 });
    expect(session.connection()).toBe('connected');
  });

  it('sends immediately when connected and confirms with the ACK', () => {
    openSynced();
    const pending = session.send('  Hola  ')!;

    expect(server.last.sentFrames()).toEqual([{ type: 'SEND', clientMessageId: pending.clientMessageId, content: 'Hola' }]);
    server.last.serverSends({ type: 'ACK', clientMessageId: pending.clientMessageId, seq: 1, createdAt: 'now' });
    expect(session.store.pending()).toHaveLength(0);
    expect(session.store.messages()[0].seq).toBe(1);
  });

  it('keeps messages written while offline as pending and resends them in order after SYNCED', () => {
    openSynced();
    server.last.serverCloses(1006);
    expect(session.connection()).toBe('reconnecting');

    const first = session.send('uno')!;
    const second = session.send('dos')!;
    expect(session.store.pending().map((p) => p.status)).toEqual(['pending', 'pending']);

    vi.advanceTimersByTime(10_000);
    server.last.serverOpen();
    expect(server.last.sent).toEqual([]);
    server.last.serverSends({ type: 'SYNCED', lastSeq: 0 });

    expect(server.last.sentFrames().map((f) => f.clientMessageId)).toEqual([first.clientMessageId, second.clientMessageId]);
  });

  it('reconnects with the contiguous lastSeq it has', () => {
    openSynced();
    server.last.serverSends({ type: 'MESSAGE', message: message(1) });
    server.last.serverSends({ type: 'MESSAGE', message: message(2) });
    server.last.serverCloses(1006);
    vi.advanceTimersByTime(10_000);

    expect(server.last.url).toContain('lastSeq=2');
  });

  it('fills a live gap from the REST history after a short wait', async () => {
    openSynced(4);
    [1, 2, 3, 4].forEach((seq) => server.last.serverSends({ type: 'MESSAGE', message: message(seq) }));
    api.messages.mockReturnValue(of([message(5), message(6), message(7)]));

    server.last.serverSends({ type: 'MESSAGE', message: message(7) });
    expect(api.messages).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(GAP_GRACE_MS);
    expect(api.messages).toHaveBeenCalledWith('c1', 4, 500);
    expect(session.store.messages().map((m) => m.seq)).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it('does not fetch when the missing message arrives within the wait', () => {
    openSynced();
    server.last.serverSends({ type: 'MESSAGE', message: message(1) });
    server.last.serverSends({ type: 'MESSAGE', message: message(3) });
    server.last.serverSends({ type: 'MESSAGE', message: message(2) });
    vi.advanceTimersByTime(GAP_GRACE_MS);

    expect(api.messages).not.toHaveBeenCalled();
  });

  it('follows TYPING and marks rejected messages as failed', () => {
    openSynced();
    server.last.serverSends({ type: 'TYPING', active: true });
    expect(session.store.botTyping()).toBe(true);

    const pending = session.send('hola')!;
    server.last.serverSends({ type: 'ERROR', clientMessageId: pending.clientMessageId, code: 'VALIDATION', message: 'Muy largo' });
    expect(session.store.pending()[0]).toEqual(expect.objectContaining({ status: 'failed', error: 'Muy largo' }));
  });

  it('knows who attends the conversation from SYNCED and follows MODE changes', () => {
    session.open('c1', 'CUSTOMER', 'Ana');
    server.last.serverOpen();
    server.last.serverSends({ type: 'SYNCED', lastSeq: 0, mode: 'HUMAN', agentName: 'Luis' });
    expect(session.store.mode()).toBe('HUMAN');
    expect(session.store.agentName()).toBe('Luis');

    server.last.serverSends({ type: 'TYPING', active: true });
    server.last.serverSends({ type: 'MODE', mode: 'BOT', agentName: null });
    expect(session.store.mode()).toBe('BOT');
    expect(session.store.agentName()).toBeNull();

    server.last.serverSends({ type: 'TYPING', active: true });
    server.last.serverSends({ type: 'MODE', mode: 'HUMAN', agentName: 'Marta' });
    expect(session.store.botTyping()).toBe(false);
  });

  it('an agent can send, and a NOT_ASSIGNED rejection marks the message as failed', () => {
    session.open('c1', 'AGENT', 'Luis');
    server.last.serverOpen();
    server.last.serverSends({ type: 'SYNCED', lastSeq: 0, mode: 'BOT', agentName: null });

    const pending = session.send('Hola Ana')!;
    expect(server.last.url).toContain('role=AGENT&name=Luis');
    expect(server.last.sentFrames()[0].content).toBe('Hola Ana');
    server.last.serverSends({ type: 'ERROR', clientMessageId: pending.clientMessageId, code: 'NOT_ASSIGNED', message: 'No asignada' });
    expect(session.store.pending()[0].status).toBe('failed');
  });

  it('reports a conversation that no longer exists', () => {
    session.open('c1', 'CUSTOMER', 'Ana');
    server.last.serverCloses(4404);
    expect(session.connection()).toBe('offline');
    expect(session.closedReason()).toBe('not-found');
  });

  it('opening from an effect does not make incoming messages reopen the socket', () => {
    const opens = vi.fn();
    TestBed.runInInjectionContext(() =>
      effect(() => {
        opens();
        session.open('c1', 'OBSERVER', 'Admin');
      }),
    );
    TestBed.tick();
    server.last.serverOpen();
    server.last.serverSends({ type: 'SYNCED', lastSeq: 0 });
    server.last.serverSends({ type: 'MESSAGE', message: message(1) });
    server.last.serverSends({ type: 'MESSAGE', message: message(2) });
    TestBed.tick();

    expect(opens).toHaveBeenCalledTimes(1);
    expect(server.sockets).toHaveLength(1);
    expect(session.connection()).toBe('connected');
  });

  it('observers cannot send and switching conversation closes the previous socket', () => {
    session.open('c1', 'OBSERVER', 'Admin');
    const first = server.last;
    expect(session.send('hola')).toBeNull();

    session.open('c2', 'OBSERVER', 'Admin');
    expect(first.closed).toBe(true);
    expect(server.last.url).toContain('/ws/conversations/c2?role=OBSERVER');
    expect(session.store.messages()).toEqual([]);
  });
});
