package org.mockserver.model;

import org.junit.Test;
import org.mockserver.socket.tls.PEMToFile;

import java.security.cert.Certificate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.X509Certificate.x509Certificate;

public class X509CertificateTest {

    @Test
    public void shouldCloneMetadataOnlyCertificate() {
        // given
        X509Certificate certificate = x509Certificate()
            .withIssuerDistinguishedName("someIssuer")
            .withSubjectDistinguishedName("someSubject")
            .withSerialNumber("someSerialNumber")
            .withSignatureAlgorithmName("someSignatureAlgorithm");

        // when
        X509Certificate clonedCertificate = certificate.clone();

        // then
        assertThat(clonedCertificate, is(certificate));
        assertThat(clonedCertificate, not(sameInstance(certificate)));
        assertThat(clonedCertificate.getCertificate(), is(nullValue()));
        assertThat(clonedCertificate.getCertificateBytes(), is(nullValue()));
    }

    @Test
    public void shouldCloneCertificateWithUnderlyingCertificate() {
        // given
        Certificate underlyingCertificate = PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem").get(0);
        X509Certificate certificate = x509Certificate()
            .withCertificate(underlyingCertificate)
            .withIssuerDistinguishedName("someIssuer")
            .withSubjectDistinguishedName("someSubject")
            .withSerialNumber("someSerialNumber")
            .withSignatureAlgorithmName("someSignatureAlgorithm");

        // when
        X509Certificate clonedCertificate = certificate.clone();

        // then
        assertThat(clonedCertificate, is(certificate));
        assertThat(clonedCertificate, not(sameInstance(certificate)));
        assertThat(clonedCertificate.getCertificate(), is(sameInstance(underlyingCertificate)));
        assertThat(clonedCertificate.getCertificateBytes(), is(certificate.getCertificateBytes()));
    }
}
