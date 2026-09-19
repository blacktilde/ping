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
