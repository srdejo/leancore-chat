import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { ChatSession, ChatStore, ClosedReason, ConnectionState, ConversationApi } from '@leancore/chat-core';
import { SupportChatPage } from './support-chat.page';
import { ConversationStorage, SavedConversation } from '../infrastructure/conversation-storage';

class FakeSession {
  readonly store = new ChatStore();
  readonly connection = signal<ConnectionState>('connected');
  readonly closedReason = signal<ClosedReason>(null);
  readonly open = vi.fn();
  readonly close = vi.fn();
  readonly send = vi.fn((content: string) => (content.trim() ? this.store.addPending('Ana', content) : null));
}

class MemoryStorage {
  saved: SavedConversation | null = null;
  load = () => this.saved;
  save = (conversation: SavedConversation) => (this.saved = conversation);
  clear = () => (this.saved = null);
}

const conversation = (id: string, customerName = 'Ana') => ({
  id,
  customerName,
  lastSeq: 0,
  lastSenderRole: null,
  lastPreview: null,
  createdAt: '',
  lastActivityAt: '',
});

describe('SupportChatPage', () => {
  let session: FakeSession;
  let storage: MemoryStorage;
  let api: { create: ReturnType<typeof vi.fn>; get: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    session = new FakeSession();
    storage = new MemoryStorage();
    api = { create: vi.fn(), get: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ConversationApi, useValue: api },
        { provide: ConversationStorage, useValue: storage },
      ],
    });
    TestBed.overrideComponent(SupportChatPage, { set: { providers: [{ provide: ChatSession, useValue: session }] } });
  });

  async function render() {
    const fixture = TestBed.createComponent(SupportChatPage);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('asks for the name, creates the conversation, remembers it and opens the chat', async () => {
    api.create.mockReturnValue(of(conversation('c1')));
    const { fixture, element } = await render();

    const input = element.querySelector<HTMLInputElement>('#customer-name')!;
    input.value = 'Ana';
    input.dispatchEvent(new Event('input'));
    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(api.create).toHaveBeenCalledWith('Ana');
    expect(storage.saved).toEqual({ id: 'c1', customerName: 'Ana' });
    expect(session.open).toHaveBeenCalledWith('c1', 'CUSTOMER', 'Ana');
    expect(element.querySelector('textarea')).not.toBeNull();
  });

  it('reopens the remembered conversation after a reload instead of creating another', async () => {
    storage.saved = { id: 'c9', customerName: 'Ana' };
    api.get.mockReturnValue(of(conversation('c9')));

    const { element } = await render();

    expect(api.get).toHaveBeenCalledWith('c9');
    expect(api.create).not.toHaveBeenCalled();
    expect(session.open).toHaveBeenCalledWith('c9', 'CUSTOMER', 'Ana');
    expect(element.querySelector('lib-message-list')).not.toBeNull();
  });

  it('forgets a remembered conversation that no longer exists', async () => {
    storage.saved = { id: 'gone', customerName: 'Ana' };
    api.get.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

    const { element } = await render();

    expect(storage.saved).toBeNull();
    expect(element.querySelector('#customer-name')).not.toBeNull();
  });

  it('starts over with "Nueva conversación"', async () => {
    storage.saved = { id: 'c9', customerName: 'Ana' };
    api.get.mockReturnValue(of(conversation('c9')));
    const { fixture, element } = await render();

    const button = Array.from(element.querySelectorAll('button')).find((b) => b.textContent?.includes('Nueva'))!;
    button.click();
    await fixture.whenStable();

    expect(session.close).toHaveBeenCalled();
    expect(storage.saved).toBeNull();
    expect(element.querySelector('#customer-name')).not.toBeNull();
  });

  it('tells the customer when a person attends the conversation', async () => {
    storage.saved = { id: 'c9', customerName: 'Ana' };
    api.get.mockReturnValue(of(conversation('c9')));
    const { fixture, element } = await render();
    expect(element.querySelector('.agent-banner')).toBeNull();

    session.store.setMode('HUMAN', 'Luis');
    await fixture.whenStable();

    expect(element.querySelector('.agent-banner')!.textContent).toContain('Estás hablando con Luis (agente de soporte)');
  });

  it('sends with Enter and clears the draft, limited to 2000 characters', async () => {
    storage.saved = { id: 'c9', customerName: 'Ana' };
    api.get.mockReturnValue(of(conversation('c9')));
    const { fixture, element } = await render();

    const textarea = element.querySelector('textarea')!;
    expect(textarea.maxLength).toBe(2000);
    textarea.value = '¿Qué talla necesito?';
    textarea.dispatchEvent(new Event('input'));
    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await fixture.whenStable();

    expect(session.send).toHaveBeenCalledWith('¿Qué talla necesito?');
    expect(textarea.value).toBe('');
    expect(element.textContent).toContain('pendiente');
  });
});
