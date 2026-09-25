import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import {
  CONTENT_MAX_LENGTH,
  ChatSession,
  ConnectionBadge,
  ConversationApi,
  MessageList,
  NAME_MAX_LENGTH,
  TypingIndicator,
} from '@leancore/chat-core';
import { ConversationStorage } from '../infrastructure/conversation-storage';

type Phase = 'loading' | 'ask-name' | 'chat';

@Component({
  selector: 'app-support-chat-page',
  imports: [MessageList, TypingIndicator, ConnectionBadge],
  providers: [ChatSession],
  template: `
    <main class="page page--narrow">
      <header class="page-header">
        <div class="page-header__titles">
          <span class="eyebrow">LeanCore · Soporte</span>
          <h1 class="title">¿En qué te ayudamos?</h1>
        </div>
        @if (phase() === 'chat') {
          <div class="toolbar">
            <lib-connection-badge [state]="session.connection()" />
            <button type="button" class="link-btn" (click)="newConversation()">Nueva conversación</button>
          </div>
        }
      </header>

      @switch (phase()) {
        @case ('loading') {
          <p class="muted">Cargando tu conversación…</p>
        }
        @case ('ask-name') {
          <form class="field" (submit)="start($event)">
            <label class="field" for="customer-name">
              Tu nombre
              <input
                id="customer-name"
                class="input"
                autocomplete="name"
                [maxLength]="nameMaxLength"
                [value]="name()"
                (input)="name.set($any($event.target).value)"
              />
            </label>
            @if (error()) {
              <p class="alert" role="alert">{{ error() }}</p>
            }
            <button class="btn" type="submit" [disabled]="!canStart()">Iniciar chat</button>
          </form>
        }
        @case ('chat') {
          @if (session.store.mode() === 'HUMAN') {
            <p class="note agent-banner" role="status">
              Estás hablando con <strong>{{ session.store.agentName() }}</strong> (agente de soporte).
            </p>
          }
          <section class="chat-panel" aria-label="Conversación">
            <lib-message-list
              [messages]="session.store.messages()"
              [pending]="session.store.pending()"
              emptyText="Escribe tu pregunta: el asistente te responderá en segundos."
            />
            <lib-typing-indicator [active]="session.store.botTyping()" />
          </section>
          <form class="composer" (submit)="send($event, box)">
            <textarea
              #box
              class="input"
              rows="2"
              aria-label="Mensaje"
              placeholder="Escribe un mensaje (Enter envía, Shift+Enter salta de línea)"
              [maxLength]="contentMaxLength"
              [value]="draft()"
              (input)="draft.set($any($event.target).value)"
              (keydown.enter)="onEnter($event, box)"
            ></textarea>
            <button class="btn" type="submit" [disabled]="!canSend()">Enviar</button>
          </form>
          <span class="composer__count">{{ draft().length }}/{{ contentMaxLength }}</span>
        }
      }
    </main>
  `,
})
export class SupportChatPage implements OnInit {
  protected readonly session = inject(ChatSession);
  private readonly api = inject(ConversationApi);
  private readonly storage = inject(ConversationStorage);

  protected readonly nameMaxLength = NAME_MAX_LENGTH;
  protected readonly contentMaxLength = CONTENT_MAX_LENGTH;

  protected readonly phase = signal<Phase>('loading');
  protected readonly name = signal('');
  protected readonly draft = signal('');
  protected readonly error = signal<string | null>(null);
  private readonly creating = signal(false);

  protected readonly canStart = computed(() => this.name().trim().length > 0 && !this.creating());
  protected readonly canSend = computed(() => this.draft().trim().length > 0);

  constructor() {
    // The remembered conversation was deleted on the server: forget it and start over.
    effect(() => {
      if (this.session.closedReason() === 'not-found') {
        this.forgetAndAskName();
      }
    });
  }

  ngOnInit(): void {
    const saved = this.storage.load();
    if (!saved) {
      this.phase.set('ask-name');
      return;
    }
    this.api.get(saved.id).subscribe({
      next: (conversation) => this.openChat(conversation.id, conversation.customerName),
      error: (error: HttpErrorResponse) => {
        if (error.status === 404) {
          this.forgetAndAskName();
        } else {
          this.error.set('No pudimos recuperar tu conversación. Intenta de nuevo.');
          this.phase.set('ask-name');
        }
      },
    });
  }

  protected start(event: Event): void {
    event.preventDefault();
    if (!this.canStart()) return;
    this.creating.set(true);
    this.error.set(null);
    this.api.create(this.name().trim()).subscribe({
      next: (conversation) => {
        this.creating.set(false);
        this.storage.save({ id: conversation.id, customerName: conversation.customerName });
        this.openChat(conversation.id, conversation.customerName);
      },
      error: (error: HttpErrorResponse) => {
        this.creating.set(false);
        this.error.set(error.error?.message ?? 'No se pudo iniciar el chat.');
      },
    });
  }

  protected send(event: Event, box: HTMLTextAreaElement): void {
    event.preventDefault();
    if (this.session.send(this.draft())) {
      this.draft.set('');
      // Cleared directly too: with zoneless change detection the [value] binding may not have seen
      // the typed text yet, so '' -> '' would not touch the element.
      box.value = '';
    }
  }

  protected onEnter(event: Event, box: HTMLTextAreaElement): void {
    if (!(event as KeyboardEvent).shiftKey) {
      this.send(event, box);
    }
  }

  protected newConversation(): void {
    this.forgetAndAskName();
  }

  private openChat(id: string, customerName: string): void {
    this.name.set(customerName);
    this.session.open(id, 'CUSTOMER', customerName);
    this.phase.set('chat');
  }

  private forgetAndAskName(): void {
    this.session.close();
    this.storage.clear();
    this.draft.set('');
    this.phase.set('ask-name');
  }
}
