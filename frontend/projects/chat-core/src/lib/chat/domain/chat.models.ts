/** Who wrote a persisted message. */
export type SenderRole = 'CUSTOMER' | 'BOT' | 'AGENT' | 'SYSTEM';

/** Who is on the socket: the customer writes; an admin is an AGENT (writes only while assigned) or an OBSERVER. */
export type ParticipantRole = 'CUSTOMER' | 'AGENT' | 'OBSERVER';

/** Who attends the conversation: the assistant, or a human agent. */
export type ConversationMode = 'BOT' | 'HUMAN';

export type ConnectionState = 'connected' | 'reconnecting' | 'offline';

export const CONTENT_MAX_LENGTH = 2000;
export const NAME_MAX_LENGTH = 60;

/** A persisted message; `seq` is the only ordering criterion inside a conversation. */
export interface ChatMessage {
  readonly seq: number;
  readonly clientMessageId: string;
  readonly senderRole: SenderRole;
  readonly senderName: string;
  readonly content: string;
  readonly replyToSeq: number | null;
  readonly botScopeVersion: number | null;
  /** Which LLM wrote a BOT reply (e.g. "openai") and with which model; null otherwise. */
  readonly botProvider?: string | null;
  readonly botModel?: string | null;
  readonly createdAt: string;
}

/** A message the customer sent that has no ACK yet (or was rejected). */
export interface PendingMessage {
  readonly clientMessageId: string;
  /** CUSTOMER from the user-app, AGENT from the admin; used when the ACK confirms it before its MESSAGE arrives. */
  readonly senderRole?: SenderRole;
  readonly senderName: string;
  readonly content: string;
  readonly status: 'pending' | 'failed';
  readonly error?: string;
}

export interface Conversation {
  readonly id: string;
  readonly customerName: string;
  readonly lastSeq: number;
  readonly lastSenderRole: SenderRole | null;
  readonly lastPreview: string | null;
  readonly createdAt: string;
  readonly lastActivityAt: string;
  readonly mode: ConversationMode;
  readonly agentName: string | null;
}

export interface BotScope {
  readonly version: number;
  readonly topic: string;
  readonly description: string;
  readonly subtopics: readonly string[];
  readonly refusalMessage: string;
  readonly createdAt: string;
}

export interface BotScopeDraft {
  readonly topic: string;
  readonly description: string;
  readonly subtopics: readonly string[];
  readonly refusalMessage: string;
}

/** Frames of the WebSocket protocol (see design D1). */
export type ServerFrame =
  | { readonly type: 'MESSAGE'; readonly message: ChatMessage }
  | { readonly type: 'ACK'; readonly clientMessageId: string; readonly seq: number; readonly createdAt: string }
  | { readonly type: 'SYNCED'; readonly lastSeq: number; readonly mode?: ConversationMode; readonly agentName?: string | null }
  | { readonly type: 'MODE'; readonly mode: ConversationMode; readonly agentName: string | null }
  | { readonly type: 'TYPING'; readonly active: boolean }
  | {
      readonly type: 'ERROR';
      readonly clientMessageId: string | null;
      readonly code: 'VALIDATION' | 'NOT_FOUND' | 'READ_ONLY' | 'NOT_ASSIGNED' | 'INTERNAL';
      readonly message: string;
    };

export interface SendFrame {
  readonly type: 'SEND';
  readonly clientMessageId: string;
  readonly content: string;
}
