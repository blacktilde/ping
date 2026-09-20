package dev.ping;

import dev.ping.auth.TokenCache;
import dev.ping.cookies.CookieJar;
import dev.ping.http.HttpEngine;
import dev.ping.methods.CookieMethods;
import dev.ping.methods.AuthMethods;
import dev.ping.methods.CoreMethods;
import dev.ping.methods.HttpMethods;
import dev.ping.methods.NetMethods;
import dev.ping.methods.ImportMethods;
import dev.ping.methods.RunMethods;
import dev.ping.methods.StoreMethods;
import dev.ping.methods.VarsMethods;
import dev.ping.rpc.RpcServer;
import dev.ping.run.Cli;

public final class Main {

    /**
     * No arguments means the stdio RPC loop: that is how the desktop shell spawns the core, so
     * it must never change. Any argument selects the command line ({@link Cli}), where stdout
     * is a report rather than protocol traffic.
     */
    public static void main(String[] args) throws Exception {
        HttpEngine.allowBasicProxyCredentialsForTunnels();
        if (args.length > 0) {
            System.exit(Cli.run(args, System.getenv(), System.out, System.err));
        }

        RpcServer server = new RpcServer(System.in, System.out);
        CoreMethods.registerOn(server);

        TokenCache tokens = new TokenCache();
        CookieJar cookies = new CookieJar();
        HttpMethods.registerOn(server, tokens, cookies);
        CookieMethods.registerOn(server, cookies);
        AuthMethods.registerOn(server, tokens);
        RunMethods.registerOn(server, tokens);
        ImportMethods.registerOn(server);
        NetMethods.registerOn(server);

        StoreMethods.registerOn(server);
        VarsMethods.registerOn(server);
        server.serve();
    }
}
