package dev.ping;

import dev.ping.methods.CoreMethods;
import dev.ping.methods.HttpMethods;
import dev.ping.rpc.RpcServer;

public final class Main {

    static void main(String[] args) throws Exception {
        RpcServer server = new RpcServer(System.in, System.out);
        CoreMethods.registerOn(server);
        HttpMethods.registerOn(server);
        server.serve();
    }
}
