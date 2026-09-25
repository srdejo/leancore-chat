import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { catchError, of, switchMap, timer } from 'rxjs';
import {
  CONTENT_MAX_LENGTH,
  ChatSession,
  ConnectionBadge,
  Conversation,
  ConversationApi,
  MessageList,
  NAME_MAX_LENGTH,
  SenderRole,
  TypingIndicator,
} from '@leancore/chat-core';
import { AgentNameStorage } from '../infrastructure/agent-name-storage';

export const LIST_REFRESH_MS = 3_000;

const ROLE_LABELS: Record<SenderRole, string> = { CUSTOMER: 'Cliente', BOT: 'Bot', AGENT: 'Agente', SYSTEM: 'Sistema' };

/**
 * Every conversation (polled) plus a live view of the selected one. The admin connects as an AGENT:
 * read-only while the bot attends; after "Tomar conversación" it answers the customer itself (design D15).
 */
@Component({
  selector: 'app-monitor-page',
  imports: [DatePipe, MessageList, TypingIndicator, ConnectionBadge],
  providers: [ChatSession],
  template: `
    <div class="monitor">
      <section aria-label="Conversaciones">
        <div class="toolbar">
          <span class="section-label">{{ conversations().length }} conversaciones</span>
          <label class="agent-field">
            Tu nombre de agente
            <input
              class="input"
              name="agentName"
              [maxLength]="nameMaxLength"
              [value]="agentName()"
              (change)="setAgentName($any($event.target).value)"
            />
          </label>
          @if (listError()) {
            <span class="pill pill--alert">No se pudo actualizar la lista</span>
          }
        </div>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>Cliente</th>
                <th class="num">Mensajes</th>
                <th>Último mensaje</th>
                <th>Atiende</th>
                <th>Actividad</th>
              </tr>
            </thead>
            <tbody>
              @for (conversation of conversations(); track conversation.id) {
                <tr class="clickable" [class.selected]="conversation.id === id()" (click)="select(conversation.id)">
                  <td>{{ conversation.customerName }}</td>
                  <td class="num">{{ conversation.lastSeq }}</td>
                  <td class="preview">
                    @if (conversation.lastSenderRole) {
                      <span class="muted">{{ roleLabel(conversation.lastSenderRole) }}:</span>
                      {{ conversation.lastPreview }}
                    } @else {
                      <span class="muted">Sin mensajes</span>
                    }
                  </td>
                  <td>
                    @if (conversation.mode === 'HUMAN') {
                      <span class="pill pill--human">{{ conversation.agentName }}</span>
                    } @else {
                      <span class="muted">Asistente</span>
                    }
                  </td>
                  <td class="mono">{{ conversation.lastActivityAt | date: 'dd/MM HH:mm:ss' }}</td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="empty">Aún no hay conversaciones.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <section class="viewer" aria-label="Conversación seleccionada">
        @if (id()) {
          <div class="toolbar">
            @if (attendedByMe()) {
              <span class="section-label">La atiendes tú</span>
              <button type="button" class="link-btn" [disabled]="busy()" (click)="handBack()">Devolver al asistente</button>
            } @else if (session.store.mode() === 'HUMAN') {
              <span class="pill pill--human">Atendida por {{ session.store.agentName() }}</span>
            } @else {
              <span class="section-label">La atiende el asistente</span>
              <button type="button" class="btn btn--small" [disabled]="busy() || session.connection() !== 'connected'" (click)="takeOver()">
                Tomar conversación
              </button>
            }
            <lib-connection-badge [state]="session.connection()" />
          </div>
          @if (actionError()) {
            <p class="alert" role="alert">{{ actionError() }}</p>
          }
          <div class="chat-panel">
            <lib-message-list
              [messages]="session.store.messages()"
              [pending]="session.store.pending()"
              [showBotOrigin]="true"
              emptyText="Esta conversación aún no tiene mensajes."
            />
            <lib-typing-indicator [active]="session.store.botTyping()" />
          </div>
          @if (attendedByMe()) {
            <form class="composer" (submit)="send($event, box)">
              <textarea
                #box
                class="input"
                rows="2"
                aria-label="Respuesta del agente"
                placeholder="Responde al cliente (Enter envía, Shift+Enter salta de línea)"
                [maxLength]="contentMaxLength"
                [value]="draft()"
                (input)="draft.set($any($event.target).value)"
                (keydown.enter)="onEnter($event, box)"
              ></textarea>
              <button class="btn" type="submit" [disabled]="!draft().trim()">Enviar</button>
            </form>
          }
        } @else {
          <p class="note">Selecciona una conversación para seguirla en vivo o tomarla.</p>
        }
      </section>
    </div>
  `,
  styles: `
    .monitor { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 32px; align-items: start; }
    .viewer { display: flex; flex-direction: column; gap: 12px; position: sticky; top: 16px; }
    .table { min-width: 0; }
    .preview { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    tr.selected { background: var(--surface); box-shadow: inset 3px 0 0 var(--green); }
    .agent-field { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--muted); }
    .agent-field .input { width: 140px; padding: 6px 10px; }
    .pill--human { border-color: var(--green); color: var(--green); }
    .btn--small { padding: 8px 14px; font-size: 14px; }
    @media (max-width: 900px) { .monitor { grid-template-columns: 1fr; } .viewer { position: static; } }
  `,
})
export class MonitorPage {
  /** Selected conversation, bound from the route (/conversations/:id). */
  readonly id = input<string>();

  protected readonly session = inject(ChatSession);
  private readonly api = inject(ConversationApi);
  private readonly router = inject(Router);
  private readonly agentNames = inject(AgentNameStorage);

  protected readonly nameMaxLength = NAME_MAX_LENGTH;
  protected readonly contentMaxLength = CONTENT_MAX_LENGTH;

  protected readonly conversations = signal<Conversation[]>([]);
  protected readonly listError = signal(false);
  protected readonly agentName = signal(this.agentNames.load());
  protected readonly draft = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly attendedByMe = computed(
    () => this.session.store.mode() === 'HUMAN' && this.session.store.agentName() === this.agentName(),
  );

  constructor() {
    timer(0, LIST_REFRESH_MS)
      .pipe(
        switchMap(() => this.api.list().pipe(catchError(() => of(null)))),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((list) => {
        // On a failed refresh keep showing the last known list.
        this.listError.set(list === null);
        if (list) this.conversations.set(list);
      });

    // One agent socket at a time: switching conversation (or agent name) closes the previous one.
    effect(() => {
      const id = this.id();
      const agent = this.agentName();
      this.actionError.set(null);
      if (id) {
        this.session.open(id, 'AGENT', agent);
      } else {
        this.session.close();
      }
    });
  }

  protected select(id: string): void {
    void this.router.navigate(['/conversations', id]);
  }

  protected setAgentName(value: string): void {
    const name = value.trim();
    if (name && name !== this.agentName()) {
      this.agentNames.save(name);
      this.agentName.set(name);
    }
  }

  protected takeOver(): void {
    this.runAction((id) => this.api.takeOver(id, this.agentName()));
  }

  protected handBack(): void {
    this.runAction((id) => this.api.release(id, this.agentName()));
  }

  protected send(event: Event, box: HTMLTextAreaElement): void {
    event.preventDefault();
    if (this.session.send(this.draft())) {
      this.draft.set('');
      box.value = '';
    }
  }

  protected onEnter(event: Event, box: HTMLTextAreaElement): void {
    if (!(event as KeyboardEvent).shiftKey) {
      this.send(event, box);
    }
  }

  protected roleLabel(role: SenderRole): string {
    return ROLE_LABELS[role];
  }

  /** The mode itself arrives through the socket (MODE frame); the HTTP call only reports conflicts. */
  private runAction(call: (id: string) => ReturnType<ConversationApi['takeOver']>): void {
    const id = this.id();
    if (!id) return;
    this.busy.set(true);
    this.actionError.set(null);
    call(id).subscribe({
      next: () => this.busy.set(false),
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.actionError.set(error.error?.message ?? 'No se pudo completar la acción.');
      },
    });
  }
}
