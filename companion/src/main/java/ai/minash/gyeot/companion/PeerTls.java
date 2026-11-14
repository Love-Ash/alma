package ai.minash.gyeot.companion;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

final class PeerTls {
    private static final String ALIAS = "gyeot_peer_tls_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    final SSLServerSocketFactory socketFactory;
    final String[] protocols;
    private final String fingerprint;

    static synchronized PeerTls load() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setCertificateSubject(new X500Principal("CN=Gyeot Companion"))
                    // Conscrypt passes a prehashed TLS signature input to AndroidKeyStore.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .build());
            generator.generateKeyPair();
        }
        if (!(store.getEntry(ALIAS, null) instanceof KeyStore.PrivateKeyEntry entry)
                || !(entry.getCertificate() instanceof X509Certificate certificate)) {
            throw new GeneralSecurityException("Missing companion TLS key");
        }
        certificate.checkValidity();
        return new PeerTls(entry.getPrivateKey(), certificate);
    }

    private PeerTls(PrivateKey key, X509Certificate certificate) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{new CompanionKey(key, certificate)}, null, RANDOM);
        socketFactory = context.getServerSocketFactory();
        protocols = Arrays.asList(context.getSupportedSSLParameters().getProtocols()).contains("TLSv1.3")
                ? new String[]{"TLSv1.3", "TLSv1.2"} : new String[]{"TLSv1.2"};
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        fingerprint = ENCODER.encodeToString(digest);
    }

    static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    String pairingCode(String token) {
        return "g1." + fingerprint + "." + token;
    }

    private static final class CompanionKey implements X509KeyManager {
        private final PrivateKey key;
        private final X509Certificate certificate;

        CompanionKey(PrivateKey key, X509Certificate certificate) {
            this.key = key;
            this.certificate = certificate;
        }

        @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
            return "EC".equals(keyType) ? new String[]{ALIAS} : null;
        }

        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return "EC".equals(keyType) ? ALIAS : null;
        }

        @Override public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null;
        }

        @Override public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? key : null;
        }

        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }

        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return null; }
    }
}
