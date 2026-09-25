import { BotScopeDraft } from '@leancore/chat-core';

/** Same rules as the backend (design D14), checked before saving to give immediate feedback. */
export const SCOPE_LIMITS = {
  topic: 60,
  description: 500,
  subtopics: 20,
  subtopic: 60,
  refusal: 300,
} as const;

export function cleanDraft(draft: BotScopeDraft): BotScopeDraft {
  const seen = new Set<string>();
  const subtopics = draft.subtopics
    .map((subtopic) => subtopic.trim())
    .filter((subtopic) => subtopic && !seen.has(subtopic.toLowerCase()) && seen.add(subtopic.toLowerCase()));
  return {
    topic: draft.topic.trim(),
    description: draft.description.trim(),
    subtopics,
    refusalMessage: draft.refusalMessage.trim(),
  };
}

export function validateDraft(draft: BotScopeDraft): string[] {
  const clean = cleanDraft(draft);
  const errors: string[] = [];
  if (!clean.topic) errors.push('El tema es obligatorio.');
  if (clean.topic.length > SCOPE_LIMITS.topic) errors.push(`El tema admite máximo ${SCOPE_LIMITS.topic} caracteres.`);
  if (clean.description.length > SCOPE_LIMITS.description) {
    errors.push(`La descripción admite máximo ${SCOPE_LIMITS.description} caracteres.`);
  }
  if (clean.subtopics.length === 0) errors.push('Indica al menos un subtema.');
  if (clean.subtopics.length > SCOPE_LIMITS.subtopics) errors.push(`Se admiten máximo ${SCOPE_LIMITS.subtopics} subtemas.`);
  if (clean.subtopics.some((subtopic) => subtopic.length > SCOPE_LIMITS.subtopic)) {
    errors.push(`Cada subtema admite máximo ${SCOPE_LIMITS.subtopic} caracteres.`);
  }
  if (!clean.refusalMessage) errors.push('La negativa es obligatoria.');
  if (clean.refusalMessage.length > SCOPE_LIMITS.refusal) {
    errors.push(`La negativa admite máximo ${SCOPE_LIMITS.refusal} caracteres.`);
  }
  return errors;
}
