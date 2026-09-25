import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./support-chat/ui/support-chat.page').then((m) => m.SupportChatPage),
  },
  { path: '**', redirectTo: '' },
];
