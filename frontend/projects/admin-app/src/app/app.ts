import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  template: `
    <main class="page">
      <header class="page-header">
        <div class="page-header__titles">
          <span class="eyebrow">LeanCore · Soporte · Admin</span>
          <h1 class="title">Panel de soporte</h1>
        </div>
        <nav class="tabs" aria-label="Secciones">
          <a routerLink="/conversations" routerLinkActive="active">Conversaciones</a>
          <a routerLink="/bot-scope" routerLinkActive="active">Alcance del bot</a>
        </nav>
      </header>
      <router-outlet />
    </main>
  `,
})
export class App {}
