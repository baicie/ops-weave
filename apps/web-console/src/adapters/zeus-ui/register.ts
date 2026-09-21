/**
 * Native Zeus UI registration.
 * `@zeus-web/ui/{button,input}` JS entries are not package sideEffects (only CSS is),
 * so production would drop `customElements.define`. Import the primitive `wc/auto`
 * entries, which are marked as sideEffects, plus the styled CSS.
 */
import '@zeus-web/button/wc/auto'
import '@zeus-web/input/wc/auto'
import '@zeus-web/ui/button.css'
import '@zeus-web/ui/input.css'
