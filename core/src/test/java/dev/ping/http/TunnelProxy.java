package dev.ping.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A minimal CONNECT proxy for tests: reads the request head, checks the credentials, then pipes bytes. */
final class TunnelProxy implements AutoCloseable {

    final List<String> connects = new CopyOnWriteArrayList<>();
    final List<String> proxyAuth = new CopyOnWriteArrayList<>();
    /** {@code user:password} the proxy demands, or empty for none. */
    volatile String requiredAuth = "";

    private final ServerSocket socket;

    TunnelProxy() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    Thread.ofVirtual().start(() -> serve(client));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    int port() {
        return socket.getLocalPort();
    }

    String address() {
        return "127.0.0.1:" + port();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int next = in.read();
                if (next < 0) {
                    return;
                }
                head.append((char) next);
            }
            String[] lines = head.toString().split("\r\n");
            connects.add(lines[0]);
            String auth = "-";
            for (String line : lines) {
                if (line.toLowerCase().startsWith("proxy-authorization:")) {
                    auth = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            proxyAuth.add(auth);
            OutputStream out = client.getOutputStream();
            String expected = requiredAuth.isEmpty() ? null
                    : "Basic " + Base64.getEncoder().encodeToString(requiredAuth.getBytes(StandardCharsets.UTF_8));
            if (expected != null && !expected.equals(auth)) {
                out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                        + "Proxy-Authenticate: Basic realm=\"corp\"\r\nContent-Length: 0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            String[] target = lines[0].split(" ")[1].split(":");
            try (Socket upstream = new Socket(target[0], Integer.parseInt(target[1]))) {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread pump = Thread.ofVirtual().start(() -> copy(upstream, client));
                copy(client, upstream);
                pump.join();
            }
        } catch (IOException | InterruptedException e) {
            // A closed tunnel ends the exchange; the assertions report what mattered.
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.shutdownOutput();
        } catch (IOException e) {
            // The other side hung up.
        }
    }
}
