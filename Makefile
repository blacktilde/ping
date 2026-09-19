# Ping — Java core + Electron/Svelte shell.
# `just` is not required; everything runs through make.

# GraalVM is only needed for native builds. Picked up from sdkman unless already set.
GRAALVM_HOME ?= $(lastword $(sort $(wildcard $(HOME)/.sdkman/candidates/java/*-graalce)))
export GRAALVM_HOME

.PHONY: help setup core test dev build clean native native-test agent

help:
	@echo "setup  Install desktop dependencies and build the core"
	@echo "core   Rebuild the core launcher (run after changing Java sources)"
	@echo "test   Run the core test suite on the JVM"
	@echo "dev    Build the core, then start Electron with hot reload"
	@echo "build  Production build of core and desktop"
	@echo "clean  Remove all build output"
	@echo ""
	@echo "native       Compile the core to a GraalVM native image (minutes, needs GraalVM)"
	@echo "native-test  Run the same test suite compiled as a native image"
	@echo "agent        Regenerate native-image reachability metadata (see CLAUDE.md)"

setup: core
	cd desktop && npm install

# installDist produces a launch script on the JVM. The GraalVM native image lands in
# phase 2; both speak the same protocol, so the shell does not care which one it spawns.
core:
	./gradlew :core:installDist

test:
	./gradlew :core:test

dev: core
	cd desktop && npm run dev

build: core
	cd desktop && npm run build

native: require-graalvm
	./gradlew :core:nativeCompile

# The real gate on the shipped artifact: the whole suite, compiled the way it ships.
native-test: require-graalvm
	./gradlew :core:nativeTest

# Traces the suite to record what is reached reflectively, then strips test-only entries.
# Re-run whenever a new type crosses the Jackson boundary.
agent: require-graalvm
	rm -rf core/build/native/agent-output
	./gradlew -Pagent :core:test
	./gradlew :core:metadataCopy --task test --dir src/main/resources/META-INF/native-image
	node tools/strip-test-metadata.mjs

.PHONY: require-graalvm
require-graalvm:
	@test -x "$(GRAALVM_HOME)/bin/native-image" || { \
		echo "GraalVM not found. Install it with:"; \
		echo "  sdk install java 25.3.4+1.r25-graalce"; \
		echo "or set GRAALVM_HOME to an installation containing bin/native-image."; \
		exit 1; }

clean:
	./gradlew clean
	rm -rf desktop/out
