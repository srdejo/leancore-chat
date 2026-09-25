import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { BotScope, ConversationApi } from '@leancore/chat-core';
import { BotScopePage } from './bot-scope.page';

const bikes: BotScope = {
  version: 1,
  topic: 'bicicletas',
  description: 'Soporte de bicicletas',
  subtopics: ['mecánica', 'rutas'],
  refusalMessage: 'Solo bicicletas.',
  createdAt: '2026-09-24T10:00:00Z',
};

describe('BotScopePage', () => {
  let api: { activeScope: ReturnType<typeof vi.fn>; saveScope: ReturnType<typeof vi.fn>; scopeVersions: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = {
      activeScope: vi.fn().mockReturnValue(of(bikes)),
      saveScope: vi.fn(),
      scopeVersions: vi.fn().mockReturnValue(of([bikes])),
    };
    TestBed.configureTestingModule({ providers: [{ provide: ConversationApi, useValue: api }] });
  });

  async function render() {
    const fixture = TestBed.createComponent(BotScopePage);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const type = async (selector: string, value: string) => {
      const field = element.querySelector<HTMLInputElement>(selector)!;
      field.value = value;
      field.dispatchEvent(new Event('input'));
      await fixture.whenStable();
    };
    const submit = async () => {
      element.querySelector('form')!.dispatchEvent(new Event('submit'));
      await fixture.whenStable();
    };
    return { fixture, element, type, submit };
  }

  it('loads the active scope into the form and lists the versions', async () => {
    const { element } = await render();

    expect(element.querySelector<HTMLInputElement>('input[name=topic]')!.value).toBe('bicicletas');
    expect(element.querySelectorAll('.subtopic input')).toHaveLength(2);
    expect(element.textContent).toContain('Activa: versión 1');
    expect(element.querySelector('.versions')!.textContent).toContain('v1 · bicicletas');
  });

  it('saves a new version with cleaned values and reports it', async () => {
    const saved: BotScope = { ...bikes, version: 2, topic: 'cafeteras', subtopics: ['espresso'], refusalMessage: 'Solo cafeteras.' };
    api.saveScope.mockReturnValue(of(saved));
    api.scopeVersions.mockReturnValue(of([saved, bikes]));
    const { element, type, submit } = await render();

    await type('input[name=topic]', '  cafeteras ');
    await type('.subtopic input', 'espresso');
    await type('.subtopic:nth-of-type(2) input', ' Espresso ');
    await type('textarea[name=refusal]', 'Solo cafeteras.');
    await submit();

    expect(api.saveScope).toHaveBeenCalledWith({
      topic: 'cafeteras',
      description: 'Soporte de bicicletas',
      subtopics: ['espresso'],
      refusalMessage: 'Solo cafeteras.',
    });
    expect(element.textContent).toContain('Versión 2 guardada');
    expect(element.textContent).toContain('Activa: versión 2');
  });

  it('does not save an invalid scope and explains why', async () => {
    const { element, type, submit } = await render();

    await type('input[name=topic]', '   ');
    await submit();

    expect(api.saveScope).not.toHaveBeenCalled();
    expect(element.querySelector('[role=alert]')!.textContent).toContain('El tema es obligatorio.');
  });

  it('shows the server validation message', async () => {
    api.saveScope.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'El tema contiene caracteres no permitidos.' } })),
    );
    const { element, submit } = await render();

    await submit();

    expect(element.textContent).toContain('El tema contiene caracteres no permitidos.');
  });

  it('loads an older version into the form without saving it', async () => {
    const coffee: BotScope = { ...bikes, version: 2, topic: 'cafeteras', subtopics: ['espresso'] };
    api.activeScope.mockReturnValue(of(coffee));
    api.scopeVersions.mockReturnValue(of([coffee, bikes]));
    const { element, fixture } = await render();

    const buttons = Array.from(element.querySelectorAll<HTMLButtonElement>('.version button'));
    buttons[1].click();
    await fixture.whenStable();

    expect(element.querySelector<HTMLInputElement>('input[name=topic]')!.value).toBe('bicicletas');
    expect(api.saveScope).not.toHaveBeenCalled();
  });

  it('adds and removes subtopics', async () => {
    const { element, fixture } = await render();
    const addButton = Array.from(element.querySelectorAll('button')).find((b) => b.textContent?.includes('Agregar'))!;

    addButton.click();
    await fixture.whenStable();
    expect(element.querySelectorAll('.subtopic input')).toHaveLength(3);

    element.querySelector<HTMLButtonElement>('.subtopic button')!.click();
    await fixture.whenStable();
    expect(element.querySelectorAll('.subtopic input')).toHaveLength(2);
  });
});
