package dev.ping.http;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A client certificate for mutual TLS, offered only to the hosts it names.
 *
 * <p>Like the proxy this is the user's setting, injected by the shell; a collection cannot
 * choose one. The core reads the files itself, so a key never crosses the pipe.
 *
 * @param host       who may be sent this certificate, in {@code NO_PROXY} form ({@code api.example.com},
 *                   {@code .example.com}, {@code host:port}, {@code *}); blank means every host
 * @param type       {@code pkcs12} or {@code pem}
 * @param cert       absolute path: the PKCS#12 bundle, or the PEM certificate (with any chain)
 * @param key        pem: absolute path of the private key, PKCS#8 plain or encrypted
 * @param passphrase the resolved passphrase of the bundle or the encrypted key; never a reference
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientCert(String host, String type, String cert, String key, String passphrase) {

    /** A passphrase in a log line or an exception message is a leaked passphrase. */
    @Override
    public String toString() {
        return "ClientCert[host=" + host + ", type=" + type + "]";
    }
}
