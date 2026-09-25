import { Component, input } from '@angular/core';

@Component({
  selector: 'lib-typing-indicator',
  template: `
    @if (active()) {
      <p class="typing" role="status">El asistente está escribiendo<span class="dots">…</span></p>
    }
  `,
  styles: `
    .typing { margin: 0; padding: 4px 4px 8px; font-size: 13px; color: var(--muted); font-style: italic; }
    .dots { display: inline-block; animation: blink 1.2s infinite; }
    @keyframes blink { 50% { opacity: .2; } }
  `,
})
export class TypingIndicator {
  readonly active = input.required<boolean>();
}
