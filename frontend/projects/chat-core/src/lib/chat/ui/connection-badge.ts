import { Component, computed, input } from '@angular/core';
import { ConnectionState } from '../domain/chat.models';

const LABELS: Record<ConnectionState, string> = {
  connected: 'Conectado',
  reconnecting: 'Reconectando…',
  offline: 'Sin conexión',
};

@Component({
  selector: 'lib-connection-badge',
  template: `<span class="pill" [class]="'pill pill--' + state()" role="status">{{ label() }}</span>`,
  styles: `
    .pill--connected { border-color: var(--green); color: var(--green); }
    .pill--reconnecting { border-color: var(--muted); color: var(--muted); }
    .pill--offline { border-color: var(--red); color: var(--red); background: var(--red-bg); }
  `,
})
export class ConnectionBadge {
  readonly state = input.required<ConnectionState>();
  protected readonly label = computed(() => LABELS[this.state()]);
}
