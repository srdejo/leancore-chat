import { Injectable } from '@angular/core';

export interface SavedConversation {
  readonly id: string;
  readonly customerName: string;
}

const KEY = 'leancore-chat.conversation';

/** Remembers the customer's conversation in the browser, so a reload reopens it instead of creating a new one. */
@Injectable({ providedIn: 'root' })
export class ConversationStorage {
  load(): SavedConversation | null {
    try {
      const raw = localStorage.getItem(KEY);
      const saved = raw ? (JSON.parse(raw) as SavedConversation) : null;
      return saved?.id && saved.customerName ? saved : null;
    } catch {
      return null;
    }
  }

  save(conversation: SavedConversation): void {
    try {
      localStorage.setItem(KEY, JSON.stringify(conversation));
    } catch {
      // Storage unavailable (private mode): the chat still works, it just won't survive a reload.
    }
  }

  clear(): void {
    try {
      localStorage.removeItem(KEY);
    } catch {
      // Nothing to clear.
    }
  }
}
