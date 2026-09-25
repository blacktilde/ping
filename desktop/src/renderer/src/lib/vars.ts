/** Variables and environments, as the core presents them. Mirrors contract/vars.schema.json. */

import { call } from './core'
import type { Param } from './http'

export interface EnvironmentRef {
  name: string
  path: string
}

export interface VarsCatalog {
  name: string
  variables: Param[]
  /** Markdown notes about the collection; absent when there are none. */
  docs?: string
  environments: EnvironmentRef[]
}

export interface EnvironmentDoc {
  name: string
  variables: Param[]
}

export async function varsCatalog(collection: string): Promise<VarsCatalog> {
  return call<VarsCatalog>('vars.catalog', { collection })
}

export async function readEnvironment(path: string): Promise<EnvironmentDoc> {
  return call<EnvironmentDoc>('vars.environment', { path })
}

export async function saveCollection(
  collection: string,
  name: string,
  variables: Param[],
  docs: string
): Promise<void> {
  await call<Record<string, never>>('vars.saveCollection', {
    collection,
    name,
    variables: plain(variables),
    // Always sent, so clearing the notes (an empty string) is saved as well as editing them.
    docs
  })
}

export async function saveEnvironment(
  collection: string,
  name: string,
  path: string,
  variables: Param[]
): Promise<string> {
  const result = await call<{ path: string }>('vars.saveEnvironment', {
    collection,
    path: path || undefined,
    name,
    variables: plain(variables)
  })
  return result.path
}

/** Renames an environment and returns its path, which follows the new name. */
export async function renameEnvironment(path: string, name: string): Promise<string> {
  const result = await call<{ path: string }>('vars.renameEnvironment', { path, name })
  return result.path
}

export async function deleteEnvironment(path: string): Promise<void> {
  await call<Record<string, never>>('vars.deleteEnvironment', { path })
}

/** Flattened map; precedence is resolved in the core. */
export async function resolveVariables(
  collection: string,
  environment: string
): Promise<Record<string, string>> {
  const result = await call<{ variables: Record<string, string> }>('vars.resolve', {
    collection,
    environment: environment || undefined
  })
  return result.variables
}

/** The core may omit `enabled`, so give the editor a real boolean to bind. */
export function normalizeVariables(variables: Param[] | undefined): Param[] {
  return (variables ?? []).map((variable) => ({
    name: variable.name ?? '',
    value: variable.value ?? '',
    enabled: variable.enabled ?? true
  }))
}

/**
 * Copies rows into plain objects. The editor's rows are Svelte `$state` proxies, and a proxy
 * cannot be structured-cloned across the context bridge.
 */
function plain(variables: Param[]): Param[] {
  return normalizeVariables(variables)
}
