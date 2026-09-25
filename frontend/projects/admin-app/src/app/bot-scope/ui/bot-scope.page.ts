import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { BotScope, ConversationApi } from '@leancore/chat-core';
import { SCOPE_LIMITS, cleanDraft, validateDraft } from '../domain/scope-validation';

/** Edit the topic the bot is restricted to. Every save is a new version, active from the next reply. */
@Component({
  selector: 'app-bot-scope-page',
  imports: [DatePipe],
  template: `
    <div class="scope">
      <form class="scope__form" (submit)="save($event)">
        <div class="toolbar">
          <h2 class="subtitle">Alcance del bot</h2>
          @if (active(); as scope) {
            <span class="pill">Activa: versión {{ scope.version }}</span>
          }
        </div>
        <p class="lead">
          El bot solo responde sobre este tema; cualquier otra pregunta recibe la negativa. Las reglas de seguridad
          del prompt son fijas: aquí solo se configuran estos campos.
        </p>

        <label class="field">
          Tema
          <input class="input" name="topic" [maxLength]="limits.topic" [value]="topic()" (input)="topic.set(value($event))" />
        </label>
        <label class="field">
          Descripción
          <textarea class="input" name="description" rows="2" [maxLength]="limits.description"
                    [value]="description()" (input)="description.set(value($event))"></textarea>
        </label>

        <fieldset class="field subtopics">
          <legend>Subtemas permitidos ({{ subtopics().length }}/{{ limits.subtopics }})</legend>
          @for (subtopic of subtopics(); track $index) {
            <div class="subtopic">
              <input class="input" [attr.aria-label]="'Subtema ' + ($index + 1)" [maxLength]="limits.subtopic"
                     [value]="subtopic" (input)="setSubtopic($index, value($event))" />
              <button type="button" class="link-btn" (click)="removeSubtopic($index)">Quitar</button>
            </div>
          }
          <button type="button" class="link-btn" [disabled]="subtopics().length >= limits.subtopics" (click)="addSubtopic()">
            + Agregar subtema
          </button>
        </fieldset>

        <label class="field">
          Negativa (respuesta fija fuera del tema)
          <textarea class="input" name="refusal" rows="2" [maxLength]="limits.refusal"
                    [value]="refusal()" (input)="refusal.set(value($event))"></textarea>
        </label>

        @if (showErrors() && errors().length) {
          <ul class="alert" role="alert">
            @for (error of errors(); track error) {
              <li>{{ error }}</li>
            }
          </ul>
        }
        @if (serverError()) {
          <p class="alert" role="alert">{{ serverError() }}</p>
        }
        @if (savedVersion()) {
          <p class="note" role="status">Versión {{ savedVersion() }} guardada: aplica desde la siguiente respuesta del bot.</p>
        }
        <button class="btn" type="submit" [disabled]="saving()">Guardar nueva versión</button>
      </form>

      <section class="scope__history" aria-label="Historial de versiones">
        <span class="section-label">Historial de versiones</span>
        <ol class="versions">
          @for (version of versions(); track version.version) {
            <li class="version">
              <div class="toolbar">
                <strong>v{{ version.version }} · {{ version.topic }}</strong>
                <span class="mono muted">{{ version.createdAt | date: 'dd/MM/yy HH:mm' }}</span>
              </div>
              <p class="muted">{{ version.subtopics.join(', ') }}</p>
              <button type="button" class="link-btn" (click)="load(version)">Cargar en el formulario</button>
            </li>
          }
        </ol>
      </section>
    </div>
  `,
  styles: `
    .scope { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr); gap: 40px; align-items: start; }
    .scope__form { display: flex; flex-direction: column; gap: 16px; }
    .subtopics { border: none; padding: 0; margin: 0; gap: 8px; }
    .subtopics legend { padding: 0; margin-bottom: 6px; }
    .subtopics > .link-btn { align-self: flex-start; }
    .subtopic { display: flex; gap: 12px; align-items: center; }
    .subtopic .input { flex: 1; }
    .alert { margin: 0; padding-left: 28px; }
    .versions { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
    .version { border-top: 1px solid var(--line); padding-top: 10px; font-size: 14px; }
    .version p { margin: 4px 0; }
    @media (max-width: 900px) { .scope { grid-template-columns: 1fr; } }
  `,
})
export class BotScopePage implements OnInit {
  private readonly api = inject(ConversationApi);

  protected readonly limits = SCOPE_LIMITS;
  protected readonly topic = signal('');
  protected readonly description = signal('');
  protected readonly subtopics = signal<string[]>(['']);
  protected readonly refusal = signal('');

  protected readonly active = signal<BotScope | null>(null);
  protected readonly versions = signal<BotScope[]>([]);
  protected readonly saving = signal(false);
  protected readonly showErrors = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly savedVersion = signal<number | null>(null);

  private readonly draft = computed(() => ({
    topic: this.topic(),
    description: this.description(),
    subtopics: this.subtopics(),
    refusalMessage: this.refusal(),
  }));
  protected readonly errors = computed(() => validateDraft(this.draft()));

  ngOnInit(): void {
    this.api.activeScope().subscribe((scope) => {
      this.active.set(scope);
      this.load(scope);
    });
    this.loadVersions();
  }

  protected save(event: Event): void {
    event.preventDefault();
    this.showErrors.set(true);
    this.serverError.set(null);
    this.savedVersion.set(null);
    if (this.errors().length) return;

    this.saving.set(true);
    this.api.saveScope(cleanDraft(this.draft())).subscribe({
      next: (scope) => {
        this.saving.set(false);
        this.showErrors.set(false);
        this.active.set(scope);
        this.savedVersion.set(scope.version);
        this.load(scope);
        this.loadVersions();
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        this.serverError.set(error.error?.message ?? 'No se pudo guardar la configuración.');
      },
    });
  }

  /** Fills the form with a version; it is only saved (as a new version) when the admin clicks save. */
  protected load(scope: BotScope): void {
    this.topic.set(scope.topic);
    this.description.set(scope.description);
    this.subtopics.set(scope.subtopics.length ? [...scope.subtopics] : ['']);
    this.refusal.set(scope.refusalMessage);
  }

  protected setSubtopic(index: number, value: string): void {
    this.subtopics.update((list) => list.map((current, i) => (i === index ? value : current)));
  }

  protected addSubtopic(): void {
    this.subtopics.update((list) => [...list, '']);
  }

  protected removeSubtopic(index: number): void {
    this.subtopics.update((list) => (list.length > 1 ? list.filter((_, i) => i !== index) : ['']));
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement).value;
  }

  private loadVersions(): void {
    this.api.scopeVersions().subscribe((versions) => this.versions.set(versions));
  }
}
