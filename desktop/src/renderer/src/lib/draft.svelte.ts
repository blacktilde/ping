/**
 * The one request the editor is working on.
 *
 * Module-level `$state` rather than a prop threaded through every component: the editors
 * all mutate the same draft, and state created outside a component has no owner, so no
 * component trips Svelte's prop-ownership checks. A single draft is all the UI supports
 * today; phase 6's collections will replace this with a per-tab store.
 */

import type { RequestDraft } from './http'
import { newDraft } from './request'

export const draft = $state<RequestDraft>(newDraft())

/**
 * Replaces the draft's fields in place. Reassigning an exported `$state` binding does not
 * propagate to importers, so the fields are copied one by one.
 */
export function loadDraft(next: RequestDraft): void {
  draft.name = next.name
  draft.method = next.method
  draft.url = next.url
  draft.query = next.query
  draft.headers = next.headers
  draft.body = next.body
  draft.auth = next.auth
  draft.timeoutMs = next.timeoutMs
  draft.redirects = next.redirects
  draft.verifyTls = next.verifyTls
  draft.maxBodyBytes = next.maxBodyBytes
}
