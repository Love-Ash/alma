package ai.minash.gyeot.vehicle;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class PinnedPeer implements X509TrustManager {
    final SSLSocketFactory socketFactory;
    private final byte[] fingerprint;

    PinnedPeer(byte[] fingerprint) throws GeneralSecurityException {
        if (fingerprint.length != 32) throw new CertificateException("Invalid companion certificate fingerprint");
        this.fingerprint = fingerprint.clone();
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{this}, null);
        socketFactory = new PeerSocketFactory(context.getSocketFactory());
    }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("Missing companion certificate");
        checkFingerprint(chain[0]);
    }

    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Client certificates are not accepted");
    }

    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

    boolean matches(SSLSession session) {
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) return false;
            checkFingerprint(leaf);
            return true;
        } catch (SSLPeerUnverifiedException | CertificateException rejected) {
            return false;
        }
    }

    private void checkFingerprint(X509Certificate certificate) throws CertificateException {
        certificate.checkValidity();
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            if (!MessageDigest.isEqual(fingerprint, actual)) {
                throw new CertificateException("Companion certificate does not match the pairing code");
            }
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new CertificateException("SHA-256 unavailable", unavailable);
        }
    }

    private static final class PeerSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        PeerSocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return configure(delegate.createSocket()); }

        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return configure(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return configure(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(String host, int port, InetAddress source, int sourcePort) throws IOException {
            return configure(delegate.createSocket(host, port, source, sourcePort));
        }

        @Override public Socket createSocket(InetAddress address, int port) throws IOException {
            return configure(delegate.createSocket(address, port));
        }

        @Override public Socket createSocket(InetAddress address, int port, InetAddress source, int sourcePort) throws IOException {
            return configure(delegate.createSocket(address, port, source, sourcePort));
        }

        private Socket configure(Socket socket) {
            SSLSocket tls = (SSLSocket) socket;
            tls.setEnabledProtocols(Arrays.asList(tls.getSupportedProtocols()).contains("TLSv1.3")
                    ? new String[]{"TLSv1.3", "TLSv1.2"} : new String[]{"TLSv1.2"});
            return tls;
        }
    }
}
