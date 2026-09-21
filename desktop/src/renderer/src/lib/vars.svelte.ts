/**
 * The variables the editor is working on.
 *
 * Module-level `$state`, like the draft: the panel's tables mutate these rows directly, and
 * state created outside a component has no owner, so no component trips Svelte's
 * prop-ownership checks. One collection and one environment are active at a time.
 */

import type { Param } from './http'
import {
  normalizeVariables,
  readEnvironment,
  resolveVariables,
  saveCollection,
  saveEnvironment,
  varsCatalog,
  type EnvironmentRef
} from './vars'

export const variables = $state({
  collection: '',
  name: '',
  docs: '',
  environment: '',
  environments: [] as EnvironmentRef[],
  collectionVariables: [] as Param[],
  environmentVariables: [] as Param[],
  resolved: {} as Record<string, string>
})

/**
 * The load in flight, if any. Opening a request in another collection resolves that
 * collection's scope over several round trips, so a send started in between would
 * interpolate against the collection that was open before — or, on the first open, against
 * nothing at all, putting `{{placeholders}}` on the wire. A send waits for this.
 */
let pending: Promise<unknown> = Promise.resolve()

/** Resolves once the variables on screen are the active collection's. Never rejects. */
export function variablesReady(): Promise<unknown> {
  return pending
}

function track<T>(work: Promise<T>): Promise<T> {
  // The tracked copy swallows the failure; the caller still sees it and reports it.
  pending = work.catch(() => {})
  return work
}

export function clearVariables(): void {
  variables.collection = ''
  variables.name = ''
  variables.docs = ''
  variables.environment = ''
  variables.environments = []
  variables.collectionVariables = []
  variables.environmentVariables = []
  variables.resolved = {}
}

export function loadCollection(collection: string): Promise<void> {
  return track(applyCollection(collection))
}

async function applyCollection(collection: string): Promise<void> {
  const catalog = await varsCatalog(collection)

  // Keep the selected environment when it still exists, otherwise default to the first.
  const environment = catalog.environments.some((entry) => entry.path === variables.environment)
    ? variables.environment
    : (catalog.environments[0]?.path ?? '')

  variables.collection = collection
  variables.name = catalog.name
  variables.docs = catalog.docs ?? ''
  variables.environments = catalog.environments
  variables.collectionVariables = normalizeVariables(catalog.variables)

  await applyEnvironment(environment)
}

export function loadEnvironment(path: string): Promise<void> {
  return track(applyEnvironment(path))
}

async function applyEnvironment(path: string): Promise<void> {
  variables.environment = path
  variables.environmentVariables = path
    ? normalizeVariables((await readEnvironment(path)).variables)
    : []
  await resolve()
}

export async function resolve(): Promise<void> {
  variables.resolved = variables.collection
    ? await resolveVariables(variables.collection, variables.environment)
    : {}
}

/** Writes both scopes, then reloads so the stored form is what the UI shows. */
export async function persistVariables(): Promise<void> {
  if (!variables.collection) {
    return
  }
  await saveCollection(
    variables.collection,
    variables.name,
    variables.collectionVariables,
    variables.docs
  )
  if (variables.environment) {
    const doc = await readEnvironment(variables.environment)
    await saveEnvironment(
      variables.collection,
      doc.name,
      variables.environment,
      variables.environmentVariables
    )
  }
  await loadCollection(variables.collection)
}

/** Creates an empty environment and makes it the active one. */
export async function addEnvironment(name: string): Promise<string> {
  const path = await saveEnvironment(variables.collection, name, '', [])
  await loadCollection(variables.collection)
  await loadEnvironment(path)
  return path
}
