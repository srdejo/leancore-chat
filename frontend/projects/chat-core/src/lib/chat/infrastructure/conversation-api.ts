import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { BotScope, BotScopeDraft, ChatMessage, Conversation } from '../domain/chat.models';
import { CHAT_CONFIG } from './chat-config';

@Injectable({ providedIn: 'root' })
export class ConversationApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(CHAT_CONFIG).apiBaseUrl}/api/v1`;

  create(customerName: string): Observable<Conversation> {
    return this.http.post<Conversation>(`${this.base}/conversations`, { customerName });
  }

  get(id: string): Observable<Conversation> {
    return this.http.get<Conversation>(`${this.base}/conversations/${id}`);
  }

  list(): Observable<Conversation[]> {
    return this.http.get<Conversation[]>(`${this.base}/conversations`);
  }

  messages(id: string, afterSeq: number, limit?: number): Observable<ChatMessage[]> {
    let params = new HttpParams().set('afterSeq', afterSeq);
    if (limit !== undefined) {
      params = params.set('limit', limit);
    }
    return this.http.get<ChatMessage[]>(`${this.base}/conversations/${id}/messages`, { params });
  }

  /** A human agent takes the conversation; 409 when another agent attends it. */
  takeOver(id: string, agentName: string): Observable<Conversation> {
    return this.http.post<Conversation>(`${this.base}/conversations/${id}/takeover`, { agentName });
  }

  /** The attending agent hands the conversation back to the bot. */
  release(id: string, agentName: string): Observable<Conversation> {
    return this.http.post<Conversation>(`${this.base}/conversations/${id}/release`, { agentName });
  }

  activeScope(): Observable<BotScope> {
    return this.http.get<BotScope>(`${this.base}/bot-scope`);
  }

  saveScope(draft: BotScopeDraft): Observable<BotScope> {
    return this.http.put<BotScope>(`${this.base}/bot-scope`, draft);
  }

  scopeVersions(): Observable<BotScope[]> {
    return this.http.get<BotScope[]>(`${this.base}/bot-scope/versions`);
  }
}
