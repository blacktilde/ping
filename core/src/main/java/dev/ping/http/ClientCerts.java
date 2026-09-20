package dev.ping.http;

import dev.ping.rpc.RpcException;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads client certificates and offers each one only to the hosts it was configured for.
 *
 * <p>One TLS context serves a whole send, redirects included. A redirect can land on a different
 * host, and a server that asks for a certificate would otherwise receive an identity meant for
 * another, so the key manager picks by the host being connected to and offers nothing when no
 * pattern matches.
 *
 * <p>The JDK reads PKCS#12 and PKCS#8 (plain, or encrypted with PBES2) but has no reader for the
 * traditional {@code BEGIN RSA/EC PRIVATE KEY} form, and writing one here would be hand-rolled DER
 * for the sake of a file {@code openssl pkcs8 -topk8} converts in one line, so those are refused
 * with that command.
 */
public final class ClientCerts {

    private ClientCerts() {
    }

    /** One loaded certificate: its key, its chain and the hosts it is for. */
    private record Loaded(List<String> hosts, String alias, PrivateKey key, X509Certificate[] chain) {
    }

    /** Identifies a file's contents well enough to reuse a load: what was read, and when it changed. */
    private record CacheKey(ClientCert cert, long certModified, long keyModified) {
    }

    /**
     * Opening an encrypted bundle costs a few hundred milliseconds of key derivation, which every
     * send to an mTLS host would otherwise pay again.
     */
    private static final Map<CacheKey, Loaded> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 16;

    /**
     * @return a key manager offering each certificate to its hosts, or null when there are none
     * @throws RpcException {@code -32602} naming the entry and the reason, without the passphrase
     */
    public static X509ExtendedKeyManager keyManager(List<ClientCert> certs) {
        if (certs == null || certs.isEmpty()) {
            return null;
        }
        List<Loaded> loaded = new ArrayList<>();
        for (int i = 0; i < certs.size(); i++) {
            loaded.add(load(certs.get(i), "ping-" + i));
        }
        return new RoutingKeyManager(loaded);
    }

    private static Loaded load(ClientCert cert, String alias) {
        String label = "The client certificate for " + hostOf(cert);
        if (cert.cert() == null || cert.cert().isBlank()) {
            throw RpcException.invalidParams(label + " has no file");
        }
        Path certPath = Path.of(cert.cert());
        Path keyPath = cert.key() == null || cert.key().isBlank() ? null : Path.of(cert.key());
        CacheKey key = new CacheKey(cert, modified(certPath), keyPath == null ? 0 : modified(keyPath));
        Loaded cached = CACHE.get(key);
        if (cached != null) {
            return new Loaded(cached.hosts(), alias, cached.key(), cached.chain());
        }

        String type = cert.type() == null ? "" : cert.type().toLowerCase(Locale.ROOT);
        Loaded result = switch (type) {
            case "pkcs12" -> pkcs12(cert, certPath, alias, label);
            case "pem" -> pem(cert, certPath, keyPath, alias, label);
            default -> throw RpcException.invalidParams(
                    label + " has an unknown type: " + cert.type() + " (use pkcs12 or pem)");
        };
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(key, result);
        return result;
    }

    private static String hostOf(ClientCert cert) {
        return cert.host() == null || cert.host().isBlank() ? "every host" : cert.host().trim();
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<String> hostsOf(ClientCert cert) {
        List<String> hosts = HostPattern.tokens(cert.host() == null ? List.of() : List.of(cert.host().split("[,\\s]+")));
        return hosts.isEmpty() ? List.of("*") : hosts;
    }

    // --- PKCS#12 ------------------------------------------------------------------------------

    private static Loaded pkcs12(ClientCert cert, Path path, String alias, String label) {
        char[] passphrase = cert.passphrase() == null ? new char[0] : cert.passphrase().toCharArray();
        KeyStore store;
        try (InputStream in = Files.newInputStream(path)) {
            store = KeyStore.getInstance("PKCS12");
            store.load(in, passphrase);
        } catch (java.nio.file.NoSuchFileException e) {
            throw RpcException.invalidParams(label + " was not found: " + path);
        } catch (IOException e) {
            // A wrong passphrase and a file that is not PKCS#12 are indistinguishable to the JDK.
            throw RpcException.invalidParams(label + " could not be opened: the passphrase is wrong, "
                    + "or " + path.getFileName() + " is not a PKCS#12 file");
        } catch (GeneralSecurityException e) {
            throw RpcException.invalidParams(label + " could not be opened: " + e.getMessage());
        }
        try {
            for (String name : java.util.Collections.list(store.aliases())) {
                if (!store.isKeyEntry(name)) {
                    continue;
                }
                PrivateKey key = (PrivateKey) store.getKey(name, passphrase);
                Certificate[] chain = store.getCertificateChain(name);
                if (key == null || chain == null || chain.length == 0) {
                    continue;
                }
                return checked(cert, alias, key, chain, label);
            }
        } catch (GeneralSecurityException e) {
            throw RpcException.invalidParams(label + " could not be opened: " + e.getMessage());
        }
        throw RpcException.invalidParams(label + " holds no private key and certificate");
    }

    // --- PEM ----------------------------------------------------------------------------------

    private static Loaded pem(ClientCert cert, Path certPath, Path keyPath, String alias, String label) {
        if (keyPath == null) {
            throw RpcException.invalidParams(label + " needs a private key file");
        }
        Certificate[] chain;
        try (InputStream in = Files.newInputStream(certPath)) {
            chain = CertificateFactory.getInstance("X.509").generateCertificates(in).toArray(new Certificate[0]);
        } catch (java.nio.file.NoSuchFileException e) {
            throw RpcException.invalidParams(label + " was not found: " + certPath);
        } catch (IOException | GeneralSecurityException e) {
            throw RpcException.invalidParams(label + " is not a PEM certificate: " + certPath.getFileName());
        }
        if (chain.length == 0) {
            throw RpcException.invalidParams(label + " is not a PEM certificate: " + certPath.getFileName());
        }
        PrivateKey key = privateKey(keyPath, cert.passphrase(), label);
        return checked(cert, alias, key, chain, label);
    }

    private static PrivateKey privateKey(Path path, String passphrase, String label) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.US_ASCII);
        } catch (java.nio.file.NoSuchFileException e) {
            throw RpcException.invalidParams(label + " key was not found: " + path);
        } catch (IOException e) {
            throw RpcException.invalidParams(label + " key could not be read: " + path.getFileName());
        }

        if (text.contains("-----BEGIN RSA PRIVATE KEY-----") || text.contains("-----BEGIN EC PRIVATE KEY-----")) {
            throw RpcException.invalidParams(label + " key is in the traditional format, which is not "
                    + "supported. Convert it: openssl pkcs8 -topk8 -in " + path.getFileName()
                    + " -out key.pkcs8.pem");
        }
        boolean encrypted = text.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----");
        byte[] der = block(text, encrypted ? "ENCRYPTED PRIVATE KEY" : "PRIVATE KEY");
        if (der == null) {
            throw RpcException.invalidParams(label + " key file has no PKCS#8 private key: " + path.getFileName());
        }

        try {
            PKCS8EncodedKeySpec spec;
            if (encrypted) {
                if (passphrase == null || passphrase.isEmpty()) {
                    throw RpcException.invalidParams(label + " key is encrypted; a passphrase is needed");
                }
                spec = decrypt(der, passphrase);
            } else {
                spec = new PKCS8EncodedKeySpec(der);
            }
            for (String algorithm : List.of("RSA", "EC", "EdDSA", "DSA")) {
                try {
                    return KeyFactory.getInstance(algorithm).generatePrivate(spec);
                } catch (GeneralSecurityException e) {
                    // Not this algorithm; the next may read it.
                }
            }
        } catch (GeneralSecurityException | IOException e) {
            throw RpcException.invalidParams(label + " key could not be opened: the passphrase is wrong, "
                    + "or the key is damaged");
        }
        throw RpcException.invalidParams(label + " key uses an algorithm that is not supported");
    }

    private static PKCS8EncodedKeySpec decrypt(byte[] der, String passphrase)
            throws GeneralSecurityException, IOException {
        EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(der);
        SecretKey secret = SecretKeyFactory.getInstance(info.getAlgName())
                .generateSecret(new PBEKeySpec(passphrase.toCharArray()));
        Cipher cipher = Cipher.getInstance(info.getAlgName());
        cipher.init(Cipher.DECRYPT_MODE, secret, info.getAlgParameters());
        return info.getKeySpec(cipher);
    }

    /** The base64 body of the first {@code -----BEGIN <label>-----} block, or null. */
    private static byte[] block(String text, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int from = text.indexOf(begin);
        int to = text.indexOf(end);
        if (from < 0 || to < from) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(text.substring(from + begin.length(), to).trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- shared -------------------------------------------------------------------------------

    /** Refuses a key that does not belong to the certificate: it would fail later, obscurely, in the handshake. */
    private static Loaded checked(ClientCert cert, String alias, PrivateKey key, Certificate[] chain, String label) {
        X509Certificate[] x509 = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            if (!(chain[i] instanceof X509Certificate certificate)) {
                throw RpcException.invalidParams(label + " holds a certificate that is not X.509");
            }
            x509[i] = certificate;
        }
        if (!matches(key, x509[0].getPublicKey())) {
            throw RpcException.invalidParams(label + ": the private key does not belong to the certificate");
        }
        return new Loaded(hostsOf(cert), alias, key, x509);
    }

    private static boolean matches(PrivateKey key, PublicKey publicKey) {
        String algorithm = switch (key.getAlgorithm().toUpperCase(Locale.ROOT)) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "EDDSA", "ED25519" -> "Ed25519";
            case "DSA" -> "SHA256withDSA";
            default -> null;
        };
        if (algorithm == null) {
            return true; // Cannot prove a mismatch; the handshake will say.
        }
        try {
            byte[] data = {1, 2, 3, 4};
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(data);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** Picks the certificate by the host being connected to; the JDK asks with the engine. */
    private static final class RoutingKeyManager extends X509ExtendedKeyManager {

        private final List<Loaded> entries;

        RoutingKeyManager(List<Loaded> entries) {
            this.entries = entries;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
            if (engine == null || engine.getPeerHost() == null) {
                return null;
            }
            for (Loaded entry : entries) {
                if (HostPattern.matchesAny(entry.hosts(), engine.getPeerHost(), engine.getPeerPort())
                        && supports(entry, keyTypes) && issuedBy(entry, issuers)) {
                    return entry.alias();
                }
            }
            return null;
        }

        private static boolean supports(Loaded entry, String[] keyTypes) {
            if (keyTypes == null || keyTypes.length == 0) {
                return true;
            }
            String algorithm = entry.key().getAlgorithm();
            for (String type : keyTypes) {
                if (type != null && (type.equalsIgnoreCase(algorithm) || type.toUpperCase(Locale.ROOT)
                        .startsWith(algorithm.toUpperCase(Locale.ROOT)))) {
                    return true;
                }
            }
            return false;
        }

        /** A server that names the CAs it accepts is not offered a certificate none of them issued. */
        private static boolean issuedBy(Loaded entry, Principal[] issuers) {
            if (issuers == null || issuers.length == 0) {
                return true;
            }
            for (X509Certificate certificate : entry.chain()) {
                for (Principal issuer : issuers) {
                    if (certificate.getIssuerX500Principal().equals(issuer)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private Loaded byAlias(String alias) {
            for (Loaded entry : entries) {
                if (entry.alias().equals(alias)) {
                    return entry;
                }
            }
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            Loaded entry = byAlias(alias);
            return entry == null ? null : entry.chain().clone();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            Loaded entry = byAlias(alias);
            return entry == null ? null : entry.key();
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return entries.stream().map(Loaded::alias).toArray(String[]::new);
        }

        /** Socket-based TLS (the connection probe): the peer is named by the handshake in progress. */
        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            if (!(socket instanceof SSLSocket tls) || tls.getHandshakeSession() == null) {
                return null;
            }
            String host = tls.getHandshakeSession().getPeerHost();
            int port = tls.getHandshakeSession().getPeerPort();
            for (Loaded entry : entries) {
                if (host != null && HostPattern.matchesAny(entry.hosts(), host, port)
                        && supports(entry, keyTypes) && issuedBy(entry, issuers)) {
                    return entry.alias();
                }
            }
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }
}
