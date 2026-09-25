import { Routes } from '@angular/router';

const monitor = () => import('./monitor/ui/monitor.page').then((m) => m.MonitorPage);

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'conversations' },
  { path: 'conversations', loadComponent: monitor },
  { path: 'conversations/:id', loadComponent: monitor },
  { path: 'bot-scope', loadComponent: () => import('./bot-scope/ui/bot-scope.page').then((m) => m.BotScopePage) },
  { path: '**', redirectTo: 'conversations' },
];
