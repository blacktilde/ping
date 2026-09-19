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
  variables: Param[]
): Promise<void> {
  await call<Record<string, never>>('vars.saveCollection', { collection, name, variables })
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
    variables
  })
  return result.path
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
