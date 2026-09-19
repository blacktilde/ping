package dev.ping;

import dev.ping.methods.CoreMethods;
import dev.ping.rpc.RpcServer;

/**
 * Entry point for the headless ping core.
 *
 * <p>Speaks newline-delimited JSON-RPC 2.0 on stdin/stdout and exits when stdin closes,
 * so the process dies with its parent rather than leaking. Diagnostics go to stderr;
 * stdout is reserved for the protocol.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        RpcServer server = new RpcServer(System.in, System.out);
        CoreMethods.registerOn(server);
        server.serve();
    }
}
