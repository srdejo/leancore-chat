import { Injectable, OnDestroy, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  CONTENT_MAX_LENGTH,
  ConnectionState,
  ParticipantRole,
  PendingMessage,
  SendFrame,
  ServerFrame,
} from '../domain/chat.models';
import { CHAT_CONFIG, WEB_SOCKET_FACTORY } from '../infrastructure/chat-config';
import { CLOSE_CONVERSATION_NOT_FOUND, ChatSocket } from '../infrastructure/chat-socket';
import { ConversationApi } from '../infrastructure/conversation-api';
import { ChatStore } from './chat-store';

/** Wait before treating a jump in seq as a gap: two commits can be published in inverted order. */
export const GAP_GRACE_MS = 300;
const GAP_PAGE_SIZE = 500;

export type ClosedReason = 'not-found' | 'invalid' | null;

/**
 * One open conversation seen by one participant: socket + store + recovery rules.
 * Provide it per component (`providers: [ChatSession]`) so each view owns its connection.
 */
@Injectable()
export class ChatSession implements OnDestroy {
  private readonly api = inject(ConversationApi);
  private readonly socketFactory = inject(WEB_SOCKET_FACTORY);
  private readonly config = inject(CHAT_CONFIG);

  readonly store = new ChatStore();
  readonly connection = signal<ConnectionState>('offline');
  readonly closedReason = signal<ClosedReason>(null);
  readonly conversationId = signal<string | null>(null);

  private socket?: ChatSocket;
  private role: ParticipantRole = 'OBSERVER';
  private name = '';
  private gapTimer?: ReturnType<typeof setTimeout>;
  private fillingGap = false;
  /** Highest seq the server reported in SYNCED; above our contiguous lastSeq means messages are missing. */
  private serverSeq = 0;

  /**
   * Untracked: callers often open from an effect, and opening reads store signals (lastSeq for the URL).
   * Tracked, every new message would re-run the caller's effect and reopen the socket in a loop.
   */
  open(conversationId: string, role: ParticipantRole, name: string): void {
    untracked(() => this.openNow(conversationId, role, name));
  }

  private openNow(conversationId: string, role: ParticipantRole, name: string): void {
    this.close();
    this.store.reset();
    this.serverSeq = 0;
    this.closedReason.set(null);
    this.conversationId.set(conversationId);
    this.role = role;
    this.name = name;
    this.connection.set('reconnecting');
    this.socket = new ChatSocket(this.socketFactory, () => this.socketUrl(conversationId), {
      onOpen: () => this.connection.set('reconnecting'),
      onFrame: (frame) => this.onFrame(frame),
      onReconnecting: () => {
        this.connection.set('reconnecting');
        this.store.botTyping.set(false);
      },
      onClosed: (code) => {
        this.connection.set('offline');
        if (code !== undefined) {
          this.closedReason.set(code === CLOSE_CONVERSATION_NOT_FOUND ? 'not-found' : 'invalid');
        }
      },
    });
    this.socket.connect();
  }

  /**
   * Adds the message as pending and sends it if the conversation is synced; otherwise it goes out
   * right after the next SYNCED. Returns null when the text is empty or too long.
   */
  send(content: string): PendingMessage | null {
    const text = content.trim();
    if (!text || text.length > CONTENT_MAX_LENGTH || this.role === 'OBSERVER' || !this.socket) {
      return null;
    }
    const pending = this.store.addPending(this.name, text, this.role === 'AGENT' ? 'AGENT' : 'CUSTOMER');
    if (this.connection() === 'connected') {
      this.transmit(pending);
    }
    return pending;
  }

  close(): void {
    clearTimeout(this.gapTimer);
    this.gapTimer = undefined;
    const socket = this.socket;
    this.socket = undefined;
    socket?.close();
    this.connection.set('offline');
  }

  ngOnDestroy(): void {
    this.close();
  }

  private onFrame(frame: ServerFrame): void {
    switch (frame.type) {
      case 'MESSAGE':
        this.store.applyMessage(frame.message);
        this.checkGap();
        break;
      case 'ACK':
        this.store.applyAck(frame.clientMessageId, frame.seq, frame.createdAt);
        this.checkGap();
        break;
      case 'SYNCED':
        this.serverSeq = Math.max(this.serverSeq, frame.lastSeq);
        if (frame.mode) {
          this.store.setMode(frame.mode, frame.agentName ?? null);
        }
        this.connection.set('connected');
        // Everything without an ACK is sent again; the server deduplicates by clientMessageId.
        this.store.unacknowledged().forEach((pending) => this.transmit(pending));
        this.checkGap();
        break;
      case 'TYPING':
        this.store.botTyping.set(frame.active);
        break;
      case 'MODE':
        this.store.setMode(frame.mode, frame.agentName);
        break;
      case 'ERROR':
        if (frame.clientMessageId) {
          this.store.markFailed(frame.clientMessageId, frame.message);
        }
        break;
    }
  }

  private transmit(pending: PendingMessage): void {
    const frame: SendFrame = { type: 'SEND', clientMessageId: pending.clientMessageId, content: pending.content };
    this.socket?.send(JSON.stringify(frame));
  }

  private checkGap(): void {
    const missing = this.store.hasGap() || this.serverSeq > this.store.lastSeq();
    if (missing && !this.gapTimer && !this.fillingGap) {
      this.gapTimer = setTimeout(() => {
        this.gapTimer = undefined;
        void this.fillGap();
      }, GAP_GRACE_MS);
    }
  }

  /** Reads the missing messages from the REST history (the database is the source of truth). */
  private async fillGap(): Promise<void> {
    const conversationId = this.conversationId();
    if (!conversationId || !(this.store.hasGap() || this.serverSeq > this.store.lastSeq())) {
      return;
    }
    this.fillingGap = true;
    try {
      const messages = await firstValueFrom(this.api.messages(conversationId, this.store.lastSeq(), GAP_PAGE_SIZE));
      if (this.conversationId() === conversationId) {
        messages.forEach((message) => this.store.applyMessage(message));
      }
    } catch {
      // The next frame or reconnection will retry.
    } finally {
      this.fillingGap = false;
    }
    if (this.conversationId() === conversationId) {
      this.checkGap();
    }
  }

  private socketUrl(conversationId: string): string {
    const params = new URLSearchParams({
      role: this.role,
      name: this.name,
      lastSeq: String(this.store.lastSeq()),
    });
    return `${this.config.wsBaseUrl}/ws/conversations/${conversationId}?${params}`;
  }
}
