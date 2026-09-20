package dev.ping.run;

import java.util.Map;

/**
 * @param environment a name, an environment file's basename or its relative path; null or
 *                    blank for none
 * @param variables   values that outrank every other scope, the way the shell's secrets do
 */
public record RunOptions(String environment, Map<String, String> variables) {

    public static RunOptions none() {
        return new RunOptions(null, Map.of());
    }
}
