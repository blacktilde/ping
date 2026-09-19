package dev.ping;

import dev.ping.auth.TokenCache;
import dev.ping.methods.AuthMethods;
import dev.ping.methods.CoreMethods;
import dev.ping.methods.HttpMethods;
import dev.ping.methods.StoreMethods;
import dev.ping.methods.VarsMethods;
import dev.ping.rpc.RpcServer;

public final class Main {

    static void main(String[] args) throws Exception {
        RpcServer server = new RpcServer(System.in, System.out);
        CoreMethods.registerOn(server);

        // One token cache shared by the engine and the interactive flow, so a token obtained
        // by authorizing is the same token the next send picks up.
        TokenCache tokens = new TokenCache();
        HttpMethods.registerOn(server, tokens);
        AuthMethods.registerOn(server, tokens);

        StoreMethods.registerOn(server);
        VarsMethods.registerOn(server);
        server.serve();
    }
}
