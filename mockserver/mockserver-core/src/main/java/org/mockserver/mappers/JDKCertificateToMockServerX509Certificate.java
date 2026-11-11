package org.mockserver.mappers;

import io.netty.handler.ssl.util.LazyX509Certificate;
import org.mockserver.authentication.ProxyAuthenticationValidator;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.X509Certificate;

import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

import static org.slf4j.event.Level.INFO;

public class JDKCertificateToMockServerX509Certificate {

    // the class the default X.509 CertificateFactory produces, learnt from the first re-parse
    private static volatile Class<?> defaultFactoryCertificateClass;

    private final MockServerLogger mockServerLogger;
    // The same Certificate[] is handed over for every request on a connection, so its fields are
    // extracted once per chain; each request still gets its own X509Certificate model objects.
    private volatile ExtractedChain lastExtractedChain;

    public JDKCertificateToMockServerX509Certificate(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public HttpRequest setClientCertificates(HttpRequest httpRequest, Certificate[] clientCertificates) {
        if (clientCertificates != null) {
            ExtractedChain extractedChain = extractedChain(clientCertificates);
            List<X509Certificate> x509Certificates = new ArrayList<>(extractedChain.fields.length);
            for (ExtractedFields fields : extractedChain.fields) {
                try {
                    if (fields.failure != null) {
                        throw fields.failure;
                    }
                    x509Certificates.add(
                        new X509Certificate()
                            .withSerialNumber(fields.serialNumber)
                            .withIssuerDistinguishedName(fields.issuerDistinguishedName)
                            .withSubjectDistinguishedName(fields.subjectDistinguishedName)
                            .withSignatureAlgorithmName(fields.signatureAlgorithmName)
                            .withCertificate(fields.certificate)
                    );
                } catch (Throwable throwable) {
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(INFO)
                                .setHttpRequest(ProxyAuthenticationValidator.withoutProxyCredential(httpRequest, mockServerLogger.getConfiguration()))
                                .setMessageFormat("exception decoding client certificate " + throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                    // the chain's position is its meaning (index 0 is the certificate the handshake proved), so stop
                    break;
                }
            }
            if (!x509Certificates.isEmpty()) {
                httpRequest.withClientCertificateChain(x509Certificates);
            }
        }
        return httpRequest;
    }

    private ExtractedChain extractedChain(Certificate[] clientCertificates) {
        ExtractedChain extractedChain = lastExtractedChain;
        if (extractedChain == null || !extractedChain.isFor(clientCertificates)) {
            extractedChain = new ExtractedChain(clientCertificates);
            lastExtractedChain = extractedChain;
        }
        return extractedChain;
    }

    /**
     * The TLS engines hand over certificates already parsed by the default {@code X.509}
     * {@link CertificateFactory} (the JDK engine directly, Netty's OpenSSL engine lazily), so reading them
     * is equivalent to re-parsing their encoding. Any other implementation is re-parsed as before, because
     * providers disagree on some fields (BouncyCastle reports {@code SHA256WITHRSA}, the JDK
     * {@code SHA256withRSA}).
     */
    private static java.security.cert.X509Certificate x509(Certificate certificate) throws Exception {
        if (certificate instanceof LazyX509Certificate
            || (certificate instanceof java.security.cert.X509Certificate && certificate.getClass() == defaultFactoryCertificateClass)) {
            return (java.security.cert.X509Certificate) certificate;
        }
        java.security.cert.X509Certificate parsed = (java.security.cert.X509Certificate) CertificateFactory
            .getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(certificate.getEncoded()));
        defaultFactoryCertificateClass = parsed.getClass();
        return parsed;
    }

    /**
     * Keyed by the identity of the array AND of each element, so an array reused with a replaced element
     * is re-extracted. Immutable, so publishing it through a volatile field is safe across threads.
     */
    private static final class ExtractedChain {
        private final Certificate[] source;
        private final Certificate[] elements;
        private final ExtractedFields[] fields;

        private ExtractedChain(Certificate[] source) {
            this.source = source;
            this.elements = source.clone();
            this.fields = new ExtractedFields[elements.length];
            for (int i = 0; i < elements.length; i++) {
                fields[i] = ExtractedFields.of(elements[i]);
            }
        }

        private boolean isFor(Certificate[] certificates) {
            if (certificates != source || certificates.length != elements.length) {
                return false;
            }
            for (int i = 0; i < elements.length; i++) {
                if (certificates[i] != elements[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ExtractedFields {
        private final Certificate certificate;
        private final String serialNumber;
        private final String issuerDistinguishedName;
        private final String subjectDistinguishedName;
        private final String signatureAlgorithmName;
        private final Throwable failure;

        private ExtractedFields(Certificate certificate, String serialNumber, String issuerDistinguishedName, String subjectDistinguishedName, String signatureAlgorithmName, Throwable failure) {
            this.certificate = certificate;
            this.serialNumber = serialNumber;
            this.issuerDistinguishedName = issuerDistinguishedName;
            this.subjectDistinguishedName = subjectDistinguishedName;
            this.signatureAlgorithmName = signatureAlgorithmName;
            this.failure = failure;
        }

        private static ExtractedFields of(Certificate certificate) {
            try {
                java.security.cert.X509Certificate x509Certificate = x509(certificate);
                return new ExtractedFields(
                    certificate,
                    x509Certificate.getSerialNumber().toString(),
                    x509Certificate.getIssuerX500Principal().getName(),
                    x509Certificate.getSubjectX500Principal().getName(),
                    x509Certificate.getSigAlgName(),
                    null
                );
            } catch (Throwable throwable) {
                return new ExtractedFields(certificate, null, null, null, null, throwable);
            }
        }
    }

}
