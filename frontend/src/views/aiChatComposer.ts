/**
 * Vue suppresses v-model updates while an IME composition is active. The textarea's DOM value is
 * still current, so click-to-send must prefer it over the last reactive draft snapshot.
 */
export function composerSubmissionText(draft: string, domValue?: string): string {
  return domValue ?? draft
}
