declare const __OPSWEAVE_CURRENT_SKILL_DIGEST__: string

// Build-time pin from the shipped package bytes. The server independently enforces the pin.
export const CURRENT_DIAGNOSIS_SKILL = Object.freeze({
  id: 'incident.diagnose', version: '2.0.0', digest: __OPSWEAVE_CURRENT_SKILL_DIGEST__,
})
