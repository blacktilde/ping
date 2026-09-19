# Ping — Java core + Electron/Svelte shell.
# `just` is not required; everything runs through make.

.PHONY: help setup core test dev build clean

help:
	@echo "setup  Install desktop dependencies and build the core"
	@echo "core   Rebuild the core launcher (run after changing Java sources)"
	@echo "test   Run the core test suite"
	@echo "dev    Build the core, then start Electron with hot reload"
	@echo "build  Production build of core and desktop"
	@echo "clean  Remove all build output"

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

clean:
	./gradlew clean
	rm -rf desktop/out
