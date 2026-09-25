import { Injectable } from '@angular/core';

const KEY = 'leancore-chat.agent-name';
export const DEFAULT_AGENT_NAME = 'Agente';

/** The admin's agent name, remembered in this browser (there is no login in the exercise). */
@Injectable({ providedIn: 'root' })
export class AgentNameStorage {
  load(): string {
    try {
      return localStorage.getItem(KEY)?.trim() || DEFAULT_AGENT_NAME;
    } catch {
      return DEFAULT_AGENT_NAME;
    }
  }

  save(name: string): void {
    try {
      localStorage.setItem(KEY, name);
    } catch {
      // Storage unavailable: the name lasts until the page is closed.
    }
  }
}
