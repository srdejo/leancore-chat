import { ChatStore } from './chat-store';
import { message } from '../testing/fake-web-socket';

describe('ChatStore', () => {
  let store: ChatStore;

  beforeEach(() => (store = new ChatStore()));

  it('shows a duplicated seq only once', () => {
    expect(store.applyMessage(message(5))).toBe(true);
    expect(store.applyMessage(message(5))).toBe(false);
    expect(store.messages().map((m) => m.seq)).toEqual([5]);
  });

  it('orders messages by seq whatever the arrival order', () => {
    store.applyMessage(message(1));
    store.applyMessage(message(2));
    store.applyMessage(message(8));
    store.applyMessage(message(7));
    expect(store.messages().map((m) => m.seq)).toEqual([1, 2, 7, 8]);
  });

  it('tracks the contiguous lastSeq and detects gaps', () => {
    store.applyMessage(message(1));
    store.applyMessage(message(2));
    store.applyMessage(message(4));
    expect(store.lastSeq()).toBe(2);
    expect(store.hasGap()).toBe(true);
    store.applyMessage(message(3));
    expect(store.lastSeq()).toBe(4);
    expect(store.hasGap()).toBe(false);
  });

  it('confirms a pending message with its ACK even before the MESSAGE arrives', () => {
    const pending = store.addPending('Ana', 'Hola');
    expect(store.pending()).toHaveLength(1);

    store.applyAck(pending.clientMessageId, 1, '2026-09-24T10:00:00Z');

    expect(store.pending()).toHaveLength(0);
    expect(store.messages()).toEqual([expect.objectContaining({ seq: 1, content: 'Hola', senderRole: 'CUSTOMER' })]);
    // The echo that arrives later is the same seq: not shown twice.
    expect(store.applyMessage(message(1, { clientMessageId: pending.clientMessageId, content: 'Hola' }))).toBe(false);
  });

  it('an ACK confirms an agent message with the AGENT role', () => {
    const pending = store.addPending('Luis', 'Hola Ana', 'AGENT');
    store.applyAck(pending.clientMessageId, 4, 'now');
    expect(store.messages()[0]).toEqual(expect.objectContaining({ seq: 4, senderRole: 'AGENT', senderName: 'Luis' }));
  });

  it('confirms a pending message when its MESSAGE arrives before the ACK', () => {
    const pending = store.addPending('Ana', 'Hola');
    store.applyMessage(message(3, { clientMessageId: pending.clientMessageId, content: 'Hola' }));
    store.applyAck(pending.clientMessageId, 3, '2026-09-24T10:00:00Z');

    expect(store.pending()).toHaveLength(0);
    expect(store.messages()).toHaveLength(1);
  });

  it('keeps rejected messages visible as failed and does not resend them', () => {
    const ok = store.addPending('Ana', 'uno');
    const bad = store.addPending('Ana', 'dos');
    store.markFailed(bad.clientMessageId, 'Demasiado largo');

    expect(store.pending().map((p) => p.status)).toEqual(['pending', 'failed']);
    expect(store.unacknowledged().map((p) => p.clientMessageId)).toEqual([ok.clientMessageId]);
  });

  it('turns typing off when the bot or the system answers', () => {
    store.botTyping.set(true);
    store.applyMessage(message(1));
    expect(store.botTyping()).toBe(true);
    store.applyMessage(message(2, { senderRole: 'BOT' }));
    expect(store.botTyping()).toBe(false);
  });
});
