package dev.ping.http;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Base64;

/**
 * Throwaway TLS material for the client-certificate tests, generated once with {@code openssl}
 * (valid for 100 years) and embedded, so no test needs a tool, a network or a checked-in binary.
 *
 * <p>A test CA signed a server certificate for {@code localhost} and {@code 127.0.0.1} and one client
 * certificate, {@code CN=ping-client}, which comes in every shape Ping reads: PKCS#12, and PEM with
 * the key plain, encrypted, or in the traditional PKCS#1 form Ping refuses. None of it is used for
 * anything but these tests.
 */
public final class TlsFixtures {

    public static final String CLIENT_PASSPHRASE = "clientpw";
    private static final String SERVER_PASSPHRASE = "changeit";

    public static final String CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDITCCAgmgAwIBAgIUfyyUQ5Ys74Q2rUup7tyui3eXUlAwDQYJKoZIhvcNAQEL
            BQAwFzEVMBMGA1UEAwwMUGluZyBUZXN0IENBMCAXDTI2MDkyMDE2NTkwNVoYDzIx
            MjYwODI3MTY1OTA1WjAXMRUwEwYDVQQDDAxQaW5nIFRlc3QgQ0EwggEiMA0GCSqG
            SIb3DQEBAQUAA4IBDwAwggEKAoIBAQCZMnTsR0hGsTBDD6BSxb352A0LVbp7KIbL
            RToebHSmEnAwucoMb1xBbx0ZFT8RUi5qRAIahZr05AReDivJIsWi4Nf6y5SU0lfP
            jLJFsrNFKarrh2MVKDUhfDgNjDEwf8i2oIHnJBYWGponrOa8V5SAAhmtEIA9a3Di
            jf5LLe+wn6glBQCPM8S1mM9NyPNFZ6/H77ncs/vJqT1FmN1nQ6eAg0e6Pj5n7Yfu
            vzL/fUwbZoHY2NFqyfSNmZPZYwwv1RRW2Do7TQF++YqNUgIU8TBl6/KtEvil5ZPm
            Vh8z8EAvScfK4T+5u2y/JHkm611+7MLJbkJ04tYWL3dgdsusHNZXAgMBAAGjYzBh
            MB0GA1UdDgQWBBQKFZZ0JqXT3j2S89UyEQSMUaImbzAfBgNVHSMEGDAWgBQKFZZ0
            JqXT3j2S89UyEQSMUaImbzAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIB
            BjANBgkqhkiG9w0BAQsFAAOCAQEADG6MuPhCZ1YuqMsrxoB6zWnMV4Uhyugj2J2+
            g/Va56ofawi64nXD1Xo5VnSOtkx0LFErD0MirJFNDAA9ADwWquydSq/pz2tKTNmx
            3snzqrVMf+mLmWEdqc/KJBotmYBC4f6c0W+MdwXzNvZEVwKMRBEOH8DdrereFRbm
            Id1eLQktLq9J/42NtffrohC1zv9Dnl3IohaohjjMnYPJFX2RcYqe0mmvSluOD5SS
            OAQ24afK5ff+B4+FscvamsoXSu8N3HkB9jQQSJNpp8/1DsLp21Jvqm34uk//0DXM
            yXIOCuvkJAXqhxhusmq3hjSTR4vZEXt5w1VMfr7ZWvj+iVJAmA==
            -----END CERTIFICATE-----
            """;

    public static final String CLIENT_CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDHzCCAgegAwIBAgIUbNfN1fUsQyGnm90+pjkbGsAS+yswDQYJKoZIhvcNAQEL
            BQAwFzEVMBMGA1UEAwwMUGluZyBUZXN0IENBMCAXDTI2MDkyMDE2NTkwNVoYDzIx
            MjYwODI3MTY1OTA1WjAWMRQwEgYDVQQDDAtwaW5nLWNsaWVudDCCASIwDQYJKoZI
            hvcNAQEBBQADggEPADCCAQoCggEBAKJ7dn6PUOni50xvEROZYm+2fC7cW/arwQ6J
            Sh/YFM7sz1erJrQ8L4m3mAEKu/AzzfVnGWHDBFx/OtuVg17EHsve98m3pnOUebRZ
            H9s2x2467Ueo/zXl98UveEydKidsSIOSNNZh+xMMr8UuqSFpMhP4KL51SezzKMq5
            XHA51C0Yw+9A0dvJ9b08KaSDZ/CQSA7U6jy0pykrsQCxZ+dvF8EV0p078jyIu3M+
            976CPEGEmFAGn4OJ4qehrs1WmDAWCY0bsiOMeHSwjT1xckMuDXybjTTGeaNW89iL
            HNrqNKO7b3vvil8QNdAteQlq3QbalDiLqUhFZew2wcnk7ZFzxDECAwEAAaNiMGAw
            EwYDVR0lBAwwCgYIKwYBBQUHAwIwCQYDVR0TBAIwADAdBgNVHQ4EFgQU3DUblkBT
            ymB062T+j7BoRo7+WngwHwYDVR0jBBgwFoAUChWWdCal0949kvPVMhEEjFGiJm8w
            DQYJKoZIhvcNAQELBQADggEBAB+1I25a34WXBV9xJCvGghTULYn1hpgTKsPf6sqp
            ngVsoIeZfpnFjO7uMhu9u9OjaREdc3VOjNjxTajYC0Dz/0g4aqKFrmLkTFGK2nDk
            Ar9WfCuG9f+4d9oJdA5Nkz+hGOcAbu6pklXsLReQOVJFssOab6eJMS/yGwL6NIT4
            o2NGc8L6LgkQAyxKW6itViEKvElvCFlrq+NfqQkGgNpCaIWu9zvQfGVToMC8RE9G
            Q8E98zKgT4d29WS/1rDz6QK2A+kzTy0QQTufDVDrZGrn8/E6TUUbGtWTc5hFMfr/
            OCquD37pyDyaz9zUdQfRMlwx2jUdSaPHXeozFaW6qYWbVuQ=
            -----END CERTIFICATE-----
            """;

    public static final String CLIENT_KEY_PLAIN = """
            -----BEGIN PRIVATE KEY-----
            MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCie3Z+j1Dp4udM
            bxETmWJvtnwu3Fv2q8EOiUof2BTO7M9Xqya0PC+Jt5gBCrvwM831ZxlhwwRcfzrb
            lYNexB7L3vfJt6ZzlHm0WR/bNsduOu1HqP815ffFL3hMnSonbEiDkjTWYfsTDK/F
            LqkhaTIT+Ci+dUns8yjKuVxwOdQtGMPvQNHbyfW9PCmkg2fwkEgO1Oo8tKcpK7EA
            sWfnbxfBFdKdO/I8iLtzPve+gjxBhJhQBp+DieKnoa7NVpgwFgmNG7IjjHh0sI09
            cXJDLg18m400xnmjVvPYixza6jSju29774pfEDXQLXkJat0G2pQ4i6lIRWXsNsHJ
            5O2Rc8QxAgMBAAECggEAHZ+N3Igrk+DLE8viHKUn5b8rB+4kFCZ/RbOxIHooXQue
            x9iL72tTrXOcEoCPAD2prLa10XWZO3X02Kj7MRFnnfrSSWQixErLH57qKHDlzkqP
            DTQaB40cbcZ4U9uJnnNnCxniaT2m5XO4LiaSLmW7/BkP8J14s2snwkImQM3CSdBC
            Ng2LKKukueZGUQm50k4SkGl8VkLA/eLDuNq+NE+aIxvwnTFp2fQ+/D9lzZSWbB2a
            9nYDKHxbahYXf873aXbiiVdGrDXv+29O9YYyWYdSeOFsqZ1xNR+fYPU0WocMmL6A
            1hzZ9zknSp+KYSSVRUbNtuoKE6Nky/4hZOKwMCZgqwKBgQDNaFXVFB65MqW7sPKH
            G9rC8ect+nbxtoSZiRsjCbI1LweASXdGuAAkuEJkhxaP7X5RGYk2EoGqcV/U+oTE
            8rBBOYGJBmJq9rOJbFIslSk3VC2CNILUQMrIFDejtGbHRPlHcSclyHiFGyzm6fBv
            fhQ2CTXZ50zq1chU9hxUALBUuwKBgQDKgInXNMVKtphaZ6Wvaa5rmIexL/PfMSks
            R3zr8Hp3wBCpsll9MvclAVFPnAZ+AJ2Ef5qtH8FFmpz1xmILc1cQNtp9Xn+RTaAQ
            R35WwDoJF5o+Dn/E6S1MvRqdVTDBTuFRWPsl0j+qBvJjH2dvq0oXOvwwNODGbpvR
            Jpyqbj7yAwKBgBlww7i5XjjH2hkOCGvNemHMvjpGnDbByViOz6qeR9EoewBbmQdM
            QNBLdgWnaZb37j4zMHhfAGpQ0nK5eHpAKK5bZfzHqqbe2Uu6+86DxvAQX/aRdGa/
            g54WSvHepSAnHczIafXXB35M6FnNPbdBYVVz/JR5fmBTjBVhyg5pa22DAoGAXilK
            7yEApG8nhNIxPtG7Yxi37/JGSgBIDujm86cMq2ZJ2T6vUgJC3ZgxQP1iH3EVzaiw
            OdRMQJtWZQpsSLrHPyjii+1HED+yMB+uZZRY6CVreXOwgdWTVN2R5JraYujU6Mih
            b8LGC7/TMbhGlqxldUvePGQ6KMsB9U0pCC7fYhUCgYASZtpnpOXKwyey800TqCV5
            L43d0wgC9e5iLpJp7e1ytLt6MTC8wKnGeeUoGD9AAOcQUbbE9tyWbj0OhrxoMmYQ
            6sz4JyOHJtReqqGgA6faeFsyMp4PvC9AOH+mJ3SHRAnplQ8+iT642r5EHB5S3AQ8
            7I4GQWBmAVhDl7oQNmRfkA==
            -----END PRIVATE KEY-----
            """;

    public static final String CLIENT_KEY_ENCRYPTED = """
            -----BEGIN ENCRYPTED PRIVATE KEY-----
            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
            -----END ENCRYPTED PRIVATE KEY-----
            """;

    public static final String CLIENT_KEY_PKCS1 = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEogIBAAKCAQEAont2fo9Q6eLnTG8RE5lib7Z8Ltxb9qvBDolKH9gUzuzPV6sm
            tDwvibeYAQq78DPN9WcZYcMEXH8625WDXsQey973ybemc5R5tFkf2zbHbjrtR6j/
            NeX3xS94TJ0qJ2xIg5I01mH7EwyvxS6pIWkyE/govnVJ7PMoyrlccDnULRjD70DR
            28n1vTwppINn8JBIDtTqPLSnKSuxALFn528XwRXSnTvyPIi7cz73voI8QYSYUAaf
            g4nip6GuzVaYMBYJjRuyI4x4dLCNPXFyQy4NfJuNNMZ5o1bz2Isc2uo0o7tve++K
            XxA10C15CWrdBtqUOIupSEVl7DbByeTtkXPEMQIDAQABAoIBAB2fjdyIK5PgyxPL
            4hylJ+W/KwfuJBQmf0WzsSB6KF0LnsfYi+9rU61znBKAjwA9qay2tdF1mTt19Nio
            +zERZ5360klkIsRKyx+e6ihw5c5Kjw00GgeNHG3GeFPbiZ5zZwsZ4mk9puVzuC4m
            ki5lu/wZD/CdeLNrJ8JCJkDNwknQQjYNiyirpLnmRlEJudJOEpBpfFZCwP3iw7ja
            vjRPmiMb8J0xadn0Pvw/Zc2UlmwdmvZ2Ayh8W2oWF3/O92l24olXRqw17/tvTvWG
            MlmHUnjhbKmdcTUfn2D1NFqHDJi+gNYc2fc5J0qfimEklUVGzbbqChOjZMv+IWTi
            sDAmYKsCgYEAzWhV1RQeuTKlu7DyhxvawvHnLfp28baEmYkbIwmyNS8HgEl3RrgA
            JLhCZIcWj+1+URmJNhKBqnFf1PqExPKwQTmBiQZiavaziWxSLJUpN1QtgjSC1EDK
            yBQ3o7Rmx0T5R3EnJch4hRss5unwb34UNgk12edM6tXIVPYcVACwVLsCgYEAyoCJ
            1zTFSraYWmelr2mua5iHsS/z3zEpLEd86/B6d8AQqbJZfTL3JQFRT5wGfgCdhH+a
            rR/BRZqc9cZiC3NXEDbafV5/kU2gEEd+VsA6CReaPg5/xOktTL0anVUwwU7hUVj7
            JdI/qgbyYx9nb6tKFzr8MDTgxm6b0Sacqm4+8gMCgYAZcMO4uV44x9oZDghrzXph
            zL46Rpw2wclYjs+qnkfRKHsAW5kHTEDQS3YFp2mW9+4+MzB4XwBqUNJyuXh6QCiu
            W2X8x6qm3tlLuvvOg8bwEF/2kXRmv4OeFkrx3qUgJx3MyGn11wd+TOhZzT23QWFV
            c/yUeX5gU4wVYcoOaWttgwKBgF4pSu8hAKRvJ4TSMT7Ru2MYt+/yRkoASA7o5vOn
            DKtmSdk+r1ICQt2YMUD9Yh9xFc2osDnUTECbVmUKbEi6xz8o4ovtRxA/sjAfrmWU
            WOgla3lzsIHVk1TdkeSa2mLo1OjIoW/Cxgu/0zG4RpasZXVL3jxkOijLAfVNKQgu
            32IVAoGAEmbaZ6TlysMnsvNNE6gleS+N3dMIAvXuYi6Sae3tcrS7ejEwvMCpxnnl
            KBg/QADnEFG2xPbclm49Doa8aDJmEOrM+CcjhybUXqqhoAOn2nhbMjKeD7wvQDh/
            pid0h0QJ6ZUPPok+uNq+RBweUtwEPOyOBkFgZgFYQ5e6EDZkX5A=
            -----END RSA PRIVATE KEY-----
            """;

    /** A valid key that belongs to no certificate here. */
    public static final String OTHER_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCZMnTsR0hGsTBD
            D6BSxb352A0LVbp7KIbLRToebHSmEnAwucoMb1xBbx0ZFT8RUi5qRAIahZr05ARe
            DivJIsWi4Nf6y5SU0lfPjLJFsrNFKarrh2MVKDUhfDgNjDEwf8i2oIHnJBYWGpon
            rOa8V5SAAhmtEIA9a3Dijf5LLe+wn6glBQCPM8S1mM9NyPNFZ6/H77ncs/vJqT1F
            mN1nQ6eAg0e6Pj5n7YfuvzL/fUwbZoHY2NFqyfSNmZPZYwwv1RRW2Do7TQF++YqN
            UgIU8TBl6/KtEvil5ZPmVh8z8EAvScfK4T+5u2y/JHkm611+7MLJbkJ04tYWL3dg
            dsusHNZXAgMBAAECggEAB54n1bEclT4tx57CKG3QPSKJvvFnOsUi1+wzC6X1jdNi
            mxLPuK1upPkuovJhzZtDaU+gbXAND2POGqYRv1GNClc+L01F+QLMowYFvjmBwbqt
            1tT+WOQfhMx1o/I/i1uPUlxcUCYXOexwuDl8yy+kRZuL/HMb5DseVwpAhb2l4ZVN
            YYYMMR0WlALRxph5PC0m2HQHv6jueRRzjNnBCiElVpT9FFv7qRyZ26bk8DKfV8rR
            4ncyQtRWRHhVXUDnAVff0EPbX1YYnu8o6GnKns6Ms44p7k6Xu8RP7XEtH69wYpGh
            WXGGC/wnHO0egYH/fcun3u+bwj0iWL2L+0tvF0r1wQKBgQDTnfWa/5cLTXs0fZrA
            k7ADhoBQINJ3iiuAYkZNBRVRfjFKZauRTYtl85ICN2U/3D0SavCHUNPFcDBl14w+
            Rxe90z45S+y3W/QNvHSUhlMQsMelTnfY0HNCVb/lZ/rKkob7PSyu55bGaAdPhGW9
            DLWxGfSJDD+U4g+XvGwMbmLTyQKBgQC5U9eZm6Gvq1a+B4fxmXg7dYEsTkEYt2Ir
            7hVG9G+lWUQwwbQd3fzPaTqzWCGZrkVb5D0d6BDZ/MwD3eJ//E8uGnQOa461NIAX
            RDJA74e32XTCt2ZDyKZS3QkHaKpx6I3huVX6/6rCFiRMtgJLOARCRpySE2nuSBu7
            ZPl1AWopHwKBgQCbh6HmnGk/5l9h8drWJPWdcbJGsgjd0NAuGIyAuPa1IWFKw7S4
            I10LvluWg25H5C+CpNRJL6+lkdIQV758WzXozyQRamr9THjvy42HR74vKy5goW5W
            DMZZf7p8+dIKJm1Mo+Z+WjmncvfEipNXwcqb8m6Wd0kXvJonNFXZQRwjeQKBgEL6
            4GMt8mPRIwqPIjzZYLDsqQDgT3qlXJ/P7nS0h11VQ+XzPCvHPUWhHIwRACPQ7lRl
            ywjyBJUkXn/PQ7tJ7zwUZ3mGug8XqGvARAFgEMcLmr19F0LMVACCzm6VD3UXvZ+l
            IkQS/x4iboAjD/Uri66AYroQtipeeVdjCR2Wo021AoGAVfObpueFow8RDy88MxUy
            vPnOPUMRAe946z71cNIYzzNgn7HAscB2AXu8FZY+jALVGdT+J6oYoiQFmnz8QU9q
            txc8EeXC3WP40UfoD5vLbPe5HQ9p8P6HMfYJSkuOPAGZyLMMt9nWQ5nYqAja8HQZ
            p/IwPgnnnmIcm875RDAEEYs=
            -----END PRIVATE KEY-----
            """;

    private static final String CLIENT_P12 = """
            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
            """;

    private static final String SERVER_P12 = """
            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
            """;

    private TlsFixtures() {
    }

    /** Writes a fixture into a directory and returns its path. */
    public static Path write(Path directory, String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, content.endsWith("\n") ? content : content + "\n", StandardCharsets.US_ASCII);
        return file;
    }

    public static Path writeClientP12(Path directory, String name) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, Base64.getMimeDecoder().decode(CLIENT_P12));
        return file;
    }

    /**
     * Server-side TLS that trusts only the test CA. With {@code requireClientCert} a connection
     * without a certificate the CA signed is refused; without it a certificate is requested and
     * optional, so a handler can report whether one arrived.
     */
    public static HttpsConfigurator serverConfigurator(boolean requireClientCert) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(new ByteArrayInputStream(Base64.getMimeDecoder().decode(SERVER_P12)),
                SERVER_PASSPHRASE.toCharArray());
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, SERVER_PASSPHRASE.toCharArray());

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(CA_PEM.getBytes(StandardCharsets.US_ASCII))));
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters parameters) {
                var ssl = getSSLContext().getDefaultSSLParameters();
                if (requireClientCert) {
                    ssl.setNeedClientAuth(true);
                } else {
                    ssl.setWantClientAuth(true);
                }
                parameters.setSSLParameters(ssl);
            }
        };
    }
}
