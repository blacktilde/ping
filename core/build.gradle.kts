plugins {
    java
    application
    id("org.graalvm.buildtools.native") version "1.1.14"
}

group = "dev.ping"
version = "0.0.5"

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
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")

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

if (project.hasProperty("agent")) {
    tasks.test {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.GRAAL_VM
        }
    }
}

graalvmNative {
    metadataRepository {
        enabled = true
    }

    binaries {
        named("main") {
            imageName = "ping-core"
            mainClass = "dev.ping.Main"

            buildArgs.add("--no-fallback")
            buildArgs.add("-O2")

            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
                vendor = JvmVendorSpec.GRAAL_VM
            }
        }
    }
}
