package dev.ping.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One RPC method. Receives the raw {@code params} node (null when the caller sent none)
 * and returns the value to place in {@code result}.
 */
@FunctionalInterface
public interface MethodHandler {
    Object handle(JsonNode params) throws Exception;
}
