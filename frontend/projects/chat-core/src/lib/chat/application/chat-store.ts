import { computed, signal } from '@angular/core';
import { ChatMessage, ConversationMode, PendingMessage, SenderRole } from '../domain/chat.models';
import { uuid } from '../domain/uuid';

/**
 * Client view of one conversation. Messages are keyed by seq, so any duplicate delivery (history + live,
 * resend, own echo) collapses into one entry, and the timeline is always shown in seq order.
 */
export class ChatStore {
  private readonly bySeq = signal<ReadonlyMap<number, ChatMessage>>(new Map());
  private readonly pendingById = signal<ReadonlyMap<string, PendingMessage>>(new Map());

  readonly botTyping = signal(false);

  /** Who attends the conversation (from SYNCED and MODE frames). */
  readonly mode = signal<ConversationMode>('BOT');
  readonly agentName = signal<string | null>(null);

  readonly messages = computed(() => [...this.bySeq().values()].sort((a, b) => a.seq - b.seq));

  /** Pending messages in the order they were written. */
  readonly pending = computed(() => [...this.pendingById().values()]);

  /** Highest seq with no gap before it: what the socket sends as lastSeq when it reconnects. */
  readonly lastSeq = computed(() => {
    const messages = this.bySeq();
    let seq = 0;
    while (messages.has(seq + 1)) {
      seq++;
    }
    return seq;
  });

  readonly maxSeq = computed(() => this.messages().at(-1)?.seq ?? 0);

  readonly hasGap = computed(() => this.maxSeq() > this.lastSeq());

  /** Adds a persisted message; returns false if its seq was already shown. */
  applyMessage(message: ChatMessage): boolean {
    if (this.bySeq().has(message.seq)) {
      return false;
    }
    this.bySeq.update((current) => new Map(current).set(message.seq, message));
    this.removePending(message.clientMessageId);
    if (message.senderRole !== 'CUSTOMER') {
      this.botTyping.set(false);
    }
    return true;
  }

  /**
   * The server stored our message with this seq. MESSAGE and ACK may arrive in any order: if the
   * MESSAGE is not here yet, the pending text is shown as confirmed with its seq right away.
   */
  applyAck(clientMessageId: string, seq: number, createdAt: string): void {
    const pending = this.pendingById().get(clientMessageId);
    if (pending && !this.bySeq().has(seq)) {
      this.applyMessage({
        seq,
        clientMessageId,
        senderRole: pending.senderRole ?? 'CUSTOMER',
        senderName: pending.senderName,
        content: pending.content,
        replyToSeq: null,
        botScopeVersion: null,
        createdAt,
      });
    }
    this.removePending(clientMessageId);
  }

  addPending(senderName: string, content: string, senderRole: SenderRole = 'CUSTOMER'): PendingMessage {
    const message: PendingMessage = { clientMessageId: uuid(), senderName, senderRole, content, status: 'pending' };
    this.pendingById.update((current) => new Map(current).set(message.clientMessageId, message));
    return message;
  }

  /** Rejected by the server (validation, read-only): kept visible with the reason, never resent. */
  markFailed(clientMessageId: string, error: string): void {
    const pending = this.pendingById().get(clientMessageId);
    if (pending) {
      this.pendingById.update((current) =>
        new Map(current).set(clientMessageId, { ...pending, status: 'failed', error }),
      );
    }
  }

  /** Messages to (re)send after a (re)connection, in their original order. */
  unacknowledged(): PendingMessage[] {
    return this.pending().filter((message) => message.status === 'pending');
  }

  setMode(mode: ConversationMode, agentName: string | null): void {
    this.mode.set(mode);
    this.agentName.set(mode === 'HUMAN' ? agentName : null);
    if (mode === 'HUMAN') {
      this.botTyping.set(false);
    }
  }

  reset(): void {
    this.bySeq.set(new Map());
    this.pendingById.set(new Map());
    this.botTyping.set(false);
    this.setMode('BOT', null);
  }

  private removePending(clientMessageId: string): void {
    if (this.pendingById().has(clientMessageId)) {
      this.pendingById.update((current) => {
        const next = new Map(current);
        next.delete(clientMessageId);
        return next;
      });
    }
  }
}
