import { TestBed } from '@angular/core/testing';
import { ConnectionBadge } from './connection-badge';
import { MessageList } from './message-list';
import { TypingIndicator } from './typing-indicator';
import { message } from '../testing/fake-web-socket';

describe('chat UI', () => {
  it('renders messages by role, then pending and failed ones', async () => {
    const fixture = TestBed.createComponent(MessageList);
    fixture.componentRef.setInput('messages', [
      message(1, { content: 'Hola' }),
      message(2, { senderRole: 'BOT', senderName: 'Asistente', content: 'Hola, ¿en qué te ayudo?' }),
      message(3, { senderRole: 'SYSTEM', senderName: 'Sistema', content: 'No disponible' }),
    ]);
    fixture.componentRef.setInput('pending', [
      { clientMessageId: 'p1', senderName: 'Ana', content: 'en camino', status: 'pending' },
      { clientMessageId: 'p2', senderName: 'Ana', content: 'rechazado', status: 'failed', error: 'Muy largo' },
    ]);
    await fixture.whenStable();

    const items: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('li.bubble'));
    expect(items.map((li) => li.className)).toEqual([
      expect.stringContaining('bubble--customer'),
      expect.stringContaining('bubble--bot'),
      expect.stringContaining('bubble--system'),
      expect.stringContaining('bubble--pending'),
      expect.stringContaining('bubble--failed'),
    ]);
    expect(items[3].textContent).toContain('pendiente');
    expect(items[4].textContent).toContain('Muy largo');
  });

  it('labels bot replies with provider and model only when showBotOrigin is on (admin)', async () => {
    const fixture = TestBed.createComponent(MessageList);
    fixture.componentRef.setInput('messages', [
      message(1, { content: 'Hola' }),
      message(2, { senderRole: 'BOT', senderName: 'Asistente', botProvider: 'openai', botModel: 'gpt-5-mini' }),
    ]);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.bubble__origin')).toBeNull();

    fixture.componentRef.setInput('showBotOrigin', true);
    await fixture.whenStable();
    const labels = fixture.nativeElement.querySelectorAll('.bubble__origin');
    expect(labels).toHaveLength(1);
    expect(labels[0].textContent.trim()).toBe('openai · gpt-5-mini');
  });

  it('styles agent messages apart from the bot', async () => {
    const fixture = TestBed.createComponent(MessageList);
    fixture.componentRef.setInput('messages', [message(1, { senderRole: 'AGENT', senderName: 'Luis', content: 'Hola' })]);
    await fixture.whenStable();
    const bubble = fixture.nativeElement.querySelector('li.bubble');
    expect(bubble.className).toContain('bubble--agent');
    expect(bubble.textContent).toContain('Luis');
  });

  it('shows the empty text without messages', async () => {
    const fixture = TestBed.createComponent(MessageList);
    fixture.componentRef.setInput('messages', []);
    fixture.componentRef.setInput('emptyText', 'Sin mensajes todavía');
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Sin mensajes todavía');
  });

  it('shows the typing indicator only while active', async () => {
    const fixture = TestBed.createComponent(TypingIndicator);
    fixture.componentRef.setInput('active', false);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).not.toContain('escribiendo');
    fixture.componentRef.setInput('active', true);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('El asistente está escribiendo');
  });

  it('labels each connection state', async () => {
    const fixture = TestBed.createComponent(ConnectionBadge);
    for (const [state, label] of [
      ['connected', 'Conectado'],
      ['reconnecting', 'Reconectando…'],
      ['offline', 'Sin conexión'],
    ] as const) {
      fixture.componentRef.setInput('state', state);
      await fixture.whenStable();
      expect(fixture.nativeElement.textContent.trim()).toBe(label);
    }
  });
});
