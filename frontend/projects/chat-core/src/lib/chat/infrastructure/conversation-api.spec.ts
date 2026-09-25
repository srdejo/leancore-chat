import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ConversationApi } from './conversation-api';
import { CHAT_CONFIG } from './chat-config';

describe('ConversationApi', () => {
  let api: ConversationApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: CHAT_CONFIG, useValue: { apiBaseUrl: 'http://api', wsBaseUrl: 'ws://api' } },
      ],
    });
    api = TestBed.inject(ConversationApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates a conversation with the customer name', () => {
    api.create('Ana').subscribe();
    const request = http.expectOne('http://api/api/v1/conversations');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ customerName: 'Ana' });
    request.flush({});
  });

  it('reads messages after a seq with a limit', () => {
    let seqs: number[] = [];
    api.messages('c1', 4, 500).subscribe((messages) => (seqs = messages.map((m) => m.seq)));
    const request = http.expectOne((r) => r.url === 'http://api/api/v1/conversations/c1/messages');
    expect(request.request.params.get('afterSeq')).toBe('4');
    expect(request.request.params.get('limit')).toBe('500');
    request.flush([{ seq: 5 }, { seq: 6 }]);
    expect(seqs).toEqual([5, 6]);
  });

  it('lists conversations and gets one', () => {
    api.list().subscribe();
    api.get('c1').subscribe();
    http.expectOne('http://api/api/v1/conversations').flush([]);
    http.expectOne('http://api/api/v1/conversations/c1').flush({});
  });

  it('reads, saves and lists bot scope versions', () => {
    const draft = { topic: 'cafeteras', description: '', subtopics: ['espresso'], refusalMessage: 'No.' };
    api.activeScope().subscribe();
    api.saveScope(draft).subscribe();
    api.scopeVersions().subscribe();
    http.expectOne({ method: 'GET', url: 'http://api/api/v1/bot-scope' }).flush({});
    const save = http.expectOne({ method: 'PUT', url: 'http://api/api/v1/bot-scope' });
    expect(save.request.body).toEqual(draft);
    save.flush({});
    http.expectOne('http://api/api/v1/bot-scope/versions').flush([]);
  });
});
