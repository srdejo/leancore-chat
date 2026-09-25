import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ChatSession, ChatStore, ClosedReason, ConnectionState, Conversation, ConversationApi } from '@leancore/chat-core';
import { LIST_REFRESH_MS, MonitorPage } from './monitor.page';
import { AgentNameStorage } from '../infrastructure/agent-name-storage';

class FakeSession {
  readonly store = new ChatStore();
  readonly connection = signal<ConnectionState>('connected');
  readonly closedReason = signal<ClosedReason>(null);
  readonly open = vi.fn();
  readonly close = vi.fn();
  readonly send = vi.fn((content: string) => this.store.addPending('Luis', content));
}

const conversation = (id: string, customerName: string, lastSeq: number, lastPreview: string | null,
                      agentName: string | null = null): Conversation => ({
  id,
  customerName,
  lastSeq,
  lastSenderRole: lastPreview ? 'BOT' : null,
  lastPreview,
  createdAt: '2026-09-24T10:00:00Z',
  lastActivityAt: '2026-09-24T10:00:00Z',
  mode: agentName ? 'HUMAN' : 'BOT',
  agentName,
});

describe('MonitorPage', () => {
  let session: FakeSession;
  let api: { list: ReturnType<typeof vi.fn>; takeOver: ReturnType<typeof vi.fn>; release: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    // Only the list polling (rxjs timer -> setInterval) is faked; Angular keeps its real scheduler.
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    session = new FakeSession();
    api = {
      list: vi.fn().mockReturnValue(of([
        conversation('a', 'Ana', 4, 'Lubrica la cadena'),
        conversation('b', 'Luis', 0, null, 'Marta'),
      ])),
      takeOver: vi.fn().mockReturnValue(of(conversation('a', 'Ana', 5, null, 'Luis'))),
      release: vi.fn().mockReturnValue(of(conversation('a', 'Ana', 6, null))),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ConversationApi, useValue: api },
        { provide: AgentNameStorage, useValue: { load: () => 'Luis', save: vi.fn() } },
      ],
    });
    TestBed.overrideComponent(MonitorPage, { set: { providers: [{ provide: ChatSession, useValue: session }] } });
  });

  afterEach(() => vi.useRealTimers());

  async function render(id?: string) {
    const fixture = TestBed.createComponent(MonitorPage);
    if (id) fixture.componentRef.setInput('id', id);
    await vi.advanceTimersByTimeAsync(0);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const button = (text: string) => Array.from(element.querySelectorAll('button')).find((b) => b.textContent?.includes(text));
    return { fixture, element, button };
  }

  it('lists every conversation with who attends it, refreshing without reload', async () => {
    const { fixture, element } = await render();
    const rows = () => Array.from<HTMLElement>(element.querySelectorAll('tbody tr'));

    expect(rows()).toHaveLength(2);
    expect(rows()[0].textContent).toContain('Bot:');
    expect(rows()[0].textContent).toContain('Asistente');
    expect(rows()[1].querySelector('.pill--human')!.textContent).toContain('Marta');

    api.list.mockReturnValue(of([conversation('c', 'Sofía', 1, 'Hola'), conversation('a', 'Ana', 4, 'x')]));
    await vi.advanceTimersByTimeAsync(LIST_REFRESH_MS);
    await fixture.whenStable();
    expect(rows()[0].textContent).toContain('Sofía');
  });

  it('connects as the remembered agent and offers to take a conversation the bot attends', async () => {
    const { element, button } = await render('a');

    expect(session.open).toHaveBeenCalledWith('a', 'AGENT', 'Luis');
    expect(element.textContent).toContain('La atiende el asistente');
    expect(element.querySelector('textarea')).toBeNull();

    button('Tomar conversación')!.click();
    expect(api.takeOver).toHaveBeenCalledWith('a', 'Luis');
  });

  it('once it attends the conversation it can answer and hand it back', async () => {
    const { fixture, element, button } = await render('a');
    session.store.setMode('HUMAN', 'Luis');
    await fixture.whenStable();

    expect(element.textContent).toContain('La atiendes tú');
    const textarea = element.querySelector('textarea')!;
    textarea.value = 'Hola Ana, ¿en qué te ayudo?';
    textarea.dispatchEvent(new Event('input'));
    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(session.send).toHaveBeenCalledWith('Hola Ana, ¿en qué te ayudo?');

    button('Devolver al asistente')!.click();
    expect(api.release).toHaveBeenCalledWith('a', 'Luis');
  });

  it('shows who attends a conversation taken by another agent, without composer', async () => {
    const { fixture, element, button } = await render('b');
    session.store.setMode('HUMAN', 'Marta');
    await fixture.whenStable();

    expect(element.textContent).toContain('Atendida por Marta');
    expect(element.querySelector('textarea')).toBeNull();
    expect(button('Tomar conversación')).toBeUndefined();
  });

  it('explains a conflict when another agent took it first', async () => {
    api.takeOver.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { message: 'La conversación la atiende Marta.', agentName: 'Marta' },
    })));
    const { fixture, element, button } = await render('a');

    button('Tomar conversación')!.click();
    await fixture.whenStable();

    expect(element.querySelector('[role=alert]')!.textContent).toContain('La conversación la atiende Marta.');
  });

  it('navigates to the conversation when a row is clicked', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const { element } = await render();

    element.querySelector<HTMLElement>('tbody tr')!.click();
    expect(navigate).toHaveBeenCalledWith(['/conversations', 'a']);
  });
});
