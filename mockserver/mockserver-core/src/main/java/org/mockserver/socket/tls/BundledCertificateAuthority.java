package org.mockserver.socket.tls;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.mockserver.configuration.ConfigurationProperties;

import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.util.Arrays;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Recognises the CA bundled with MockServer, whose private key is published in the MockServer jar, so anyone can sign
 * certificates with it. Recognition is by key material, not by certificate or key encoding: RSA by modulus and public
 * exponent whatever the algorithm identifier (rsaEncryption or RSASSA-PSS), EC by curve and point. So a certificate
 * re-issued or re-encoded for the bundled CA's key is recognised too.
 */
public final class BundledCertificateAuthority {

    private static volatile X509Certificate bundledCertificateAuthority;

    private BundledCertificateAuthority() {
    }

    /**
     * @return {@code true} if any of {@code certificates} is for the bundled CA's key
     */
    public static boolean containsBundledCertificateAuthority(X509Certificate[] certificates) {
        if (certificates == null) {
            return false;
        }
        PublicKey bundledPublicKey = certificate().getPublicKey();
        for (X509Certificate certificate : certificates) {
            if (certificate != null && sameKeyMaterial(bundledPublicKey, certificate.getPublicKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param pemFileLocation a file system path or classpath location of a PEM certificate chain
     * @return {@code true} if the chain contains a certificate for the bundled CA's key; {@code false} if it does not,
     * or cannot be read (an unreadable chain fails where it is used, with its own message)
     */
    public static boolean chainContainsBundledCertificateAuthority(String pemFileLocation) {
        if (isBlank(pemFileLocation)) {
            return false;
        }
        try {
            return containsBundledCertificateAuthority(PEMToFile.x509ChainFromPEMFile(pemFileLocation).toArray(new X509Certificate[0]));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * @param certificateAuthorityCertificate the configured {@code certificateAuthorityCertificate}
     * @return {@code true} if it is the bundled CA, by location or by key
     */
    public static boolean isBundledCertificateAuthority(String certificateAuthorityCertificate) {
        if (ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE.equals(certificateAuthorityCertificate)) {
            return true;
        }
        if (isBlank(certificateAuthorityCertificate)) {
            return false;
        }
        try {
            return containsBundledCertificateAuthority(new X509Certificate[]{PEMToFile.x509FromPEMFile(certificateAuthorityCertificate)});
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    public static boolean sameKeyMaterial(PublicKey first, PublicKey second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.equals(second)) {
            return true;
        }
        try {
            SubjectPublicKeyInfo firstInfo = SubjectPublicKeyInfo.getInstance(first.getEncoded());
            SubjectPublicKeyInfo secondInfo = SubjectPublicKeyInfo.getInstance(second.getEncoded());
            if (isRsa(firstInfo) && isRsa(secondInfo)) {
                RSAPublicKey firstRsa = RSAPublicKey.getInstance(firstInfo.parsePublicKey());
                RSAPublicKey secondRsa = RSAPublicKey.getInstance(secondInfo.parsePublicKey());
                return firstRsa.getModulus().equals(secondRsa.getModulus()) && firstRsa.getPublicExponent().equals(secondRsa.getPublicExponent());
            }
            if (first instanceof ECPublicKey && second instanceof ECPublicKey) {
                ECPublicKey firstEc = (ECPublicKey) first;
                ECPublicKey secondEc = (ECPublicKey) second;
                return firstEc.getW().equals(secondEc.getW()) && sameCurve(firstEc.getParams(), secondEc.getParams());
            }
            return Arrays.equals(firstInfo.getPublicKeyData().getBytes(), secondInfo.getPublicKeyData().getBytes());
        } catch (Exception unparseable) {
            return false;
        }
    }

    private static boolean isRsa(SubjectPublicKeyInfo info) {
        ASN1ObjectIdentifier algorithm = info.getAlgorithm().getAlgorithm();
        return PKCSObjectIdentifiers.rsaEncryption.equals(algorithm) || PKCSObjectIdentifiers.id_RSASSA_PSS.equals(algorithm);
    }

    private static boolean sameCurve(ECParameterSpec first, ECParameterSpec second) {
        return first.getCurve().equals(second.getCurve())
            && first.getGenerator().equals(second.getGenerator())
            && first.getOrder().equals(second.getOrder())
            && first.getCofactor() == second.getCofactor();
    }

    static X509Certificate certificate() {
        X509Certificate certificate = bundledCertificateAuthority;
        if (certificate == null) {
            certificate = PEMToFile.x509FromPEMFile(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE);
            bundledCertificateAuthority = certificate;
        }
        return certificate;
    }
}
