package dev.ping.run;

import dev.ping.http.NetworkConfig;

import java.util.Map;

/**
 * @param environment a name, an environment file's basename or its relative path; null or
 *                    blank for none
 * @param variables   values that outrank every other scope, the way the shell's secrets do
 * @param network     the proxy every request goes through; set by whoever launched the run, never
 *                    read from a collection
 * @param allowAbsoluteFiles whether a request may read a file by absolute path. The command line
 *                    is the user's own shell and allows it; the desktop app does not, so a shared
 *                    collection cannot make it upload an arbitrary file
 */
public record RunOptions(
        String environment, Map<String, String> variables, boolean allowAbsoluteFiles, NetworkConfig network) {

    public RunOptions(String environment, Map<String, String> variables, boolean allowAbsoluteFiles) {
        this(environment, variables, allowAbsoluteFiles, NetworkConfig.NONE);
    }

    public RunOptions(String environment, Map<String, String> variables) {
        this(environment, variables, false);
    }

    public static RunOptions none() {
        return new RunOptions(null, Map.of());
    }
}
