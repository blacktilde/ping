/**
 * Removes test-only entries from the native-image reachability metadata.
 *
 * The tracing agent observes the test suite, so it records JUnit, Gradle's worker and the
 * JDK test server alongside the reflection the core genuinely performs. Those types are not
 * on the main classpath, and shipping them in `src/main/resources` would ask native-image to
 * register classes the production image does not contain.
 */
import fs from 'node:fs'

const path = 'core/src/main/resources/META-INF/native-image/reachability-metadata.json'
const testOnly = /Test(\$|$)|junit|opentest4j|com\.sun\.net\.httpserver|org\.gradle|worker\./i

const metadata = JSON.parse(fs.readFileSync(path, 'utf8'))
const before = metadata.reflection.length

metadata.reflection = metadata.reflection.filter((entry) => !testOnly.test(entry.type ?? ''))
metadata.resources = (metadata.resources ?? []).filter(
  (entry) => !/junit|gradle|test/i.test(JSON.stringify(entry))
)

fs.writeFileSync(path, `${JSON.stringify(metadata, null, 2)}\n`)
console.log(`reflection ${before} -> ${metadata.reflection.length}`)
