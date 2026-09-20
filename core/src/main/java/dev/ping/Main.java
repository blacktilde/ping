package dev.ping;

import dev.ping.auth.TokenCache;
import dev.ping.methods.AuthMethods;
import dev.ping.methods.CoreMethods;
import dev.ping.methods.HttpMethods;
import dev.ping.methods.StoreMethods;
import dev.ping.methods.VarsMethods;
import dev.ping.rpc.RpcServer;

public final class Main {

    static void main() throws Exception {
        RpcServer server = new RpcServer(System.in, System.out);
        CoreMethods.registerOn(server);

        TokenCache tokens = new TokenCache();
        HttpMethods.registerOn(server, tokens);
        AuthMethods.registerOn(server, tokens);

        StoreMethods.registerOn(server);
        VarsMethods.registerOn(server);
        server.serve();
    }
}
