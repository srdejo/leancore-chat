import { Component, ElementRef, effect, input, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ChatMessage, PendingMessage } from '../domain/chat.models';

/** Conversation timeline: persisted messages in seq order, then the customer's pending ones. */
@Component({
  selector: 'lib-message-list',
  imports: [DatePipe],
  template: `
    <ol class="timeline" #timeline aria-live="polite">
      @for (message of messages(); track message.seq) {
        <li class="bubble" [class]="'bubble bubble--' + message.senderRole.toLowerCase()" [attr.data-seq]="message.seq">
          <div class="bubble__meta">
            <span class="bubble__who">
              {{ message.senderName }}
              @if (showBotOrigin() && message.botProvider) {
                <span class="bubble__origin mono" title="Proveedor y modelo que generaron la respuesta">
                  {{ message.botProvider }}{{ message.botModel ? ' · ' + message.botModel : '' }}
                </span>
              }
            </span>
            <span class="mono muted">#{{ message.seq }} · {{ message.createdAt | date: 'HH:mm:ss' }}</span>
          </div>
          <p class="bubble__text">{{ message.content }}</p>
        </li>
      }
      @for (message of pending(); track message.clientMessageId) {
        <li class="bubble bubble--customer bubble--pending" [class.bubble--failed]="message.status === 'failed'">
          <div class="bubble__meta">
            <span class="bubble__who">{{ message.senderName }}</span>
            <span class="mono muted">{{ message.status === 'failed' ? 'no enviado' : 'pendiente…' }}</span>
          </div>
          <p class="bubble__text">{{ message.content }}</p>
          @if (message.error) {
            <p class="bubble__error">{{ message.error }}</p>
          }
        </li>
      }
      @if (messages().length === 0 && pending().length === 0) {
        <li class="empty">{{ emptyText() }}</li>
      }
    </ol>
  `,
  styles: `
    :host { display: block; min-height: 0; overflow-y: auto; }
    .timeline { list-style: none; margin: 0; padding: 16px 4px; display: flex; flex-direction: column; gap: 12px; }
    .bubble { max-width: min(560px, 85%); padding: 10px 14px; border-radius: var(--radius); border: 1px solid var(--line); background: var(--white); }
    .bubble--customer { align-self: flex-end; background: var(--ink); color: var(--paper); border-color: var(--ink); }
    .bubble--customer .muted { color: var(--muted-on-ink); }
    .bubble--bot { align-self: flex-start; }
    .bubble--agent { align-self: flex-start; border-color: var(--green); box-shadow: inset 3px 0 0 var(--green); }
    .bubble--system { align-self: center; background: var(--surface); border-style: dashed; font-size: 13px; }
    .bubble--pending { opacity: .6; }
    .bubble--failed { opacity: 1; background: var(--red-bg); color: var(--red); border-color: var(--red); }
    .bubble__meta { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; margin-bottom: 4px; }
    .bubble__who { font-weight: 600; }
    .bubble__origin { margin-left: 6px; padding: 1px 6px; border: 1px solid var(--line-strong); border-radius: 999px; font-size: 11px; font-weight: 400; color: var(--green); }
    .bubble__text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.45; }
    .bubble__error { margin: 6px 0 0; font-size: 12px; }
  `,
})
export class MessageList {
  readonly messages = input.required<readonly ChatMessage[]>();
  readonly pending = input<readonly PendingMessage[]>([]);
  readonly emptyText = input('Aún no hay mensajes.');
  /** Admin only: label BOT replies with the provider and model that wrote them. */
  readonly showBotOrigin = input(false);

  private readonly timeline = viewChild.required<ElementRef<HTMLElement>>('timeline');

  constructor() {
    // Keep the newest message in view.
    effect(() => {
      this.messages();
      this.pending();
      const host = this.timeline().nativeElement.parentElement;
      queueMicrotask(() => host?.scrollTo?.({ top: host.scrollHeight }));
    });
  }
}
