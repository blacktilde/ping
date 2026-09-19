plugins {
    java
    application
    id("org.graalvm.buildtools.native") version "1.1.14"
}

group = "dev.ping"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.ping.Main"
    applicationName = "ping-core"
}

tasks.test {
    useJUnitPlatform()
}

/**
 * `./gradlew -Pagent test` runs the suite under the native-image tracing agent to record
 * what is reached reflectively — chiefly Jackson binding into the request records. The
 * agent ships only inside a GraalVM JDK, so ordinary test runs stay on the default
 * toolchain and nobody needs GraalVM to work on the core.
 */
if (project.hasProperty("agent")) {
    tasks.test {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.GRAAL_VM
        }
    }
}

/**
 * The shipped core is a GraalVM native image: no JRE to bundle and startup in tens of
 * milliseconds instead of a second.
 *
 * <p>Development still runs on the JVM via `installDist`, because a native build takes
 * minutes. Both produce a binary named `ping-core` speaking the same protocol, so the
 * Electron shell does not care which one it spawns.
 */
graalvmNative {
    // Pulls published reachability metadata for Jackson and friends, so only the parts
    // this project reaches reflectively need generating locally.
    metadataRepository {
        enabled = true
    }

    binaries {
        named("main") {
            imageName = "ping-core"
            mainClass = "dev.ping.Main"

            // Fail the build on anything unreachable rather than silently falling back to
            // a JVM-dependent image that would not run on a machine without a JDK.
            buildArgs.add("--no-fallback")
            buildArgs.add("-O2")

            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
                vendor = JvmVendorSpec.GRAAL_VM
            }
        }
    }
}
