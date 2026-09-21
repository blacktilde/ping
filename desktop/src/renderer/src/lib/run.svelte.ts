/**
 * Running a whole collection, as the panel sees it.
 *
 * The core has done this since the CLI landed: `run.collection` sends every request in a
 * collection folder in sidebar order and answers with the whole `RunResult`, while a
 * `run.progress` notification arrives after each request. Types mirror
 * `contract/run.schema.json`.
 *
 * The state lives in this module rather than in the panel so closing the panel does not
 * abandon a run that is still going: the run keeps filling in, and reopening shows where it
 * got to. One run at a time: `running` is what the panel's Run button is disabled on.
 */

import { call } from './core'
import type { AssertionResult, CaptureOutcome } from './http'

/** One request's outcome. `url` is as written, so a `{{placeholder}}` is never resolved here. */
export interface RunRequestResult {
  path: string
  name: string
  method?: string
  url?: string
  /** Absent when no response arrived. */
  status?: number
  durationMs?: number
  /** Why no response was obtained. Present means the request errored. */
  error?: string
  passed: boolean
  assertions: AssertionResult[]
  /** Names and hit/miss only; a captured value is never part of a result. */
  captures?: CaptureOutcome[]
}

export interface RunResult {
  collection: string
  /** The environment's display name; absent when none was used. */
  environment?: string
  durationMs: number
  total: number
  passed: number
  /** Got a response but failed an assertion. */
  failed: number
  /** Got no response. */
  errored: number
  requests: RunRequestResult[]
}

interface RunProgress {
  runId?: string
  index: number
  total: number
  request: RunRequestResult
}

export const run = $state({
  /** The panel is open. A run outlives it, so this is not what `running` is. */
  open: false,
  /** The collection folder, relative to the workspace root. */
  collection: '',
  /** Its display name, for the panel's heading. */
  collectionName: '',
  /** The chosen environment's path, or '' for none. */
  environment: '',
  running: false,
  error: '',
  /** Known once the first request finishes; 0 until then. */
  total: 0,
  /** Results as they arrive, replaced by the run's own list when it answers. */
  requests: [] as RunRequestResult[],
  result: null as RunResult | null
})

/** The run whose progress notifications are ours. Null between runs. */
let currentRunId: string | null = null

/**
 * Opens the panel for a collection, without starting anything. A run in flight keeps the panel:
 * there is one run at a time, so asking for another collection while one is going shows what is
 * going rather than quietly replacing the target under it.
 */
export function openRun(collection: string, name: string, environment: string): void {
  if (!run.running) {
    run.collection = collection
    run.collectionName = name
    run.environment = environment
    run.error = ''
    run.total = 0
    run.requests = []
    run.result = null
  }
  run.open = true
}

export function closeRun(): void {
  run.open = false
}

/**
 * Subscribes to `run.progress` for the lifetime of the app. Only the run this module started
 * is followed: a notification carries the `runId` it was given, so a late one from a run that
 * has already been replaced is dropped rather than appended to the next run's list.
 */
export function watchRunProgress(): () => void {
  return window.ping.onNotification((notification) => {
    if (notification.method !== 'run.progress') {
      return
    }
    const progress = notification.params as RunProgress
    if (!currentRunId || progress.runId !== currentRunId) {
      return
    }
    run.total = progress.total
    run.requests = [...run.requests, progress.request]
  })
}

/**
 * Runs the open collection and keeps the state above in step with it.
 *
 * The shell adds the secrets and the network settings, and forces `allowAbsoluteFiles` off, so
 * nothing here names any of them.
 */
export async function startRun(): Promise<void> {
  if (run.running || !run.collection) {
    return
  }
  const runId = crypto.randomUUID()
  currentRunId = runId
  run.running = true
  run.error = ''
  run.total = 0
  run.requests = []
  run.result = null
  try {
    const result = await call<RunResult>('run.collection', {
      runId,
      collection: run.collection,
      environment: run.environment || undefined
    })
    // The response is the whole run; progress was only what arrived on the way.
    run.result = result
    run.requests = result.requests
    run.total = result.total
  } catch (cause) {
    run.error = cause instanceof Error ? cause.message : String(cause)
  } finally {
    if (currentRunId === runId) {
      currentRunId = null
    }
    run.running = false
  }
}
