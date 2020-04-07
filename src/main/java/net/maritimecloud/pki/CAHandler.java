/*
 * Copyright 2017 Danish Maritime Authority.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package net.maritimecloud.pki;


import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.maritimecloud.pki.exception.PKIRuntimeException;
import net.maritimecloud.pki.pkcs11.P11PKIConfiguration;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.operator.OperatorCreationException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CRLReason;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static net.maritimecloud.pki.PKIConstants.KEYSTORE_TYPE;

@Slf4j
@AllArgsConstructor
public class CAHandler {

    private CertificateBuilder certificateBuilder;
    private PKIConfiguration pkiConfiguration;

    /**
     * Creates a sub Certificate Authority for the MC PKI. The certificate and keypair is placed in a "SubCaKeystore"
     * defined in PKIConfiguration and in the truststore, also defined in PKIConfiguration. The SubCaKeystore will be
     * created if it does not exist already, but the truststore is expected to exists already. It is also expected that
     * a RootCaKeystore is defined in PKIConfiguration and exists.
     *
     * @param subCaCertDN The DN of the new sub CA certificate.
     */
    public void createSubCa(String subCaCertDN, String rootCAAlias) {

        // Open the various keystores
        KeyStore rootKeystore;
        KeyStore subCaKeystore;
        KeyStore truststore;
        try (InputStream rootKeystoreIS = new FileInputStream(pkiConfiguration.getRootCaKeystorePath());
             FileInputStream subCaFis = new FileInputStream(pkiConfiguration.getSubCaKeystorePath());
             FileInputStream trustFis = new FileInputStream(pkiConfiguration.getTruststorePath());
        ) {
            // Open the root keystore
            rootKeystore = KeyStore.getInstance(KEYSTORE_TYPE);
            rootKeystore.load(rootKeystoreIS, pkiConfiguration.getRootCaKeystorePassword().toCharArray());

            // Open or create the sub CA keystore
            subCaKeystore = KeyStore.getInstance(KEYSTORE_TYPE);
            if (new File(pkiConfiguration.getSubCaKeystorePath()).exists()) {
                subCaKeystore.load(subCaFis, pkiConfiguration.getSubCaKeystorePassword().toCharArray());
            } else {
                subCaKeystore.load(null, pkiConfiguration.getSubCaKeystorePassword().toCharArray());
            }

            // Open the truststore
            truststore = KeyStore.getInstance(KeyStore.getDefaultType());
            truststore.load(trustFis, pkiConfiguration.getTruststorePassword().toCharArray());

        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            throw new PKIRuntimeException(e);
        }

        // Extract the root certificate
        KeyStore.ProtectionParameter protParam = new KeyStore.PasswordProtection(pkiConfiguration.getRootCaKeystorePassword().toCharArray());
        KeyStore.PrivateKeyEntry rootCertEntry;
        X500Name rootCertX500Name;
        String crlUrl;
        try {
            rootCertEntry = (KeyStore.PrivateKeyEntry) rootKeystore.getEntry(rootCAAlias, protParam);
            rootCertX500Name = new JcaX509CertificateHolder((X509Certificate) rootCertEntry.getCertificate()).getSubject();
        } catch (NoSuchAlgorithmException | UnrecoverableEntryException | KeyStoreException | CertificateEncodingException e) {
            throw new PKIRuntimeException(e);
        }
        try {
            List<String> crlPoints = CRLVerifier.getCrlDistributionPoints((X509Certificate) rootCertEntry.getCertificate());
            crlUrl = crlPoints.get(0);
        } catch (IOException e) {
            throw new PKIRuntimeException(e);
        }

        // Create the sub CA certificate
        KeyPair subCaKeyPair = CertificateBuilder.generateKeyPair();
        X509Certificate subCaCert;
        X500Name subCaCertX500Name = new X500Name(subCaCertDN);
        String alias = CertificateHandler.getElement(subCaCertX500Name, BCStyle.UID);
        if (alias == null || alias.trim().isEmpty()) {
            throw new PKIRuntimeException("UID must be defined for sub CA! It will be used as the sub CA alias.");
        }
        try {
            subCaCert = certificateBuilder.buildAndSignCert(certificateBuilder.generateSerialNumber(null), rootCertEntry.getPrivateKey(), rootCertEntry.getCertificate().getPublicKey(),
                    subCaKeyPair.getPublic(), rootCertX500Name, subCaCertX500Name, null, "INTERMEDIATE", null, crlUrl, null);
        } catch (Exception e) {
            throw new PKIRuntimeException("Could not create sub CA certificate!", e);
        }

        // Store the sub CA certificate in the Sub CA keystore and the MC truststore
        try (FileOutputStream trustFos = new FileOutputStream(pkiConfiguration.getTruststorePath());
             FileOutputStream subCaFos = new FileOutputStream(pkiConfiguration.getSubCaKeystorePath())
        ) {
            Certificate[] certChain = new Certificate[2];
            certChain[0] = subCaCert;
            certChain[1] = rootCertEntry.getCertificate();
            subCaKeystore.setKeyEntry(alias, subCaKeyPair.getPrivate(), pkiConfiguration.getSubCaKeyPassword().toCharArray(), certChain);
            subCaKeystore.store(subCaFos, pkiConfiguration.getSubCaKeystorePassword().toCharArray());

            truststore.setCertificateEntry(alias, subCaCert);
            truststore.store(trustFos, pkiConfiguration.getTruststorePassword().toCharArray());

        } catch (NoSuchAlgorithmException | KeyStoreException | CertificateException | IOException e) {
            throw new PKIRuntimeException(e);
        }
    }

    /**
     * Generates a self-signed certificate and saves it in the keystore and truststore.
     * Should only be used to init the root CA. It is expected that info about the root keystore and the truststore
     * is available in PKIConfiguration. If they already exists they will be overwritten!
     *
     * @param rootCertX500Name The DN of the new root CA Certificate
     * @param crlUrl CRL endpoint
     * @param rootCAAlias The alias of the root CA
     */
    public void initRootCA(String rootCertX500Name, String crlUrl, String rootCAAlias) {
        KeyPair cakp = CertificateBuilder.generateKeyPair();
        KeyStore rootks;
        KeyStore ts;
        try (FileOutputStream rootfos = new FileOutputStream(pkiConfiguration.getRootCaKeystorePath());
             FileOutputStream tsfos = new FileOutputStream(pkiConfiguration.getTruststorePath())
        ) {
            rootks = KeyStore.getInstance(KEYSTORE_TYPE);
            rootks.load(null, pkiConfiguration.getRootCaKeystorePassword().toCharArray());
            // Store away the keystore.
            X509Certificate cacert = certificateBuilder.buildAndSignCert(certificateBuilder.generateSerialNumber(null), cakp.getPrivate(), cakp.getPublic(), cakp.getPublic(),
                        new X500Name(rootCertX500Name), new X500Name(rootCertX500Name), null, "ROOTCA", null, crlUrl, null);

            Certificate[] certChain = new Certificate[1];
            certChain[0] = cacert;
            rootks.setKeyEntry(rootCAAlias, cakp.getPrivate(), pkiConfiguration.getRootCaKeyPassword().toCharArray(), certChain);
            rootks.store(rootfos, pkiConfiguration.getRootCaKeystorePassword().toCharArray());
            rootks = KeyStore.getInstance(KeyStore.getDefaultType());
            rootks.load(null, pkiConfiguration.getRootCaKeystorePassword().toCharArray());

            // Store away the truststore.
            ts = KeyStore.getInstance(KeyStore.getDefaultType());
            ts.load(null, pkiConfiguration.getTruststorePassword().toCharArray());
            ts.setCertificateEntry(rootCAAlias, cacert);
            ts.store(tsfos, pkiConfiguration.getTruststorePassword().toCharArray());
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException | OperatorCreationException e) {
            throw new PKIRuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Generates a self-signed certificate and saves it and the private key in a HSM using PKCS#11 and the certificate only in a truststore.
     *
     * @param rootCertX500Name The DN of the new root CA Certificate
     * @param crlUrl CRL endpoint
     * @param rootCAAlias The alias of the root CA
     */
    public void initRootCAPKCS11(String rootCertX500Name, String crlUrl, String rootCAAlias) {
        if (!(pkiConfiguration instanceof P11PKIConfiguration)) {
            throw new PKIRuntimeException("This function can only be called when used with an HSM");
        }
        P11PKIConfiguration p11PKIConfiguration = (P11PKIConfiguration) pkiConfiguration;
        p11PKIConfiguration.providerLogin();
        KeyPair caKeyPair = CertificateBuilder.generateKeyPairPKCS11(p11PKIConfiguration.getProvider());
        KeyStore rootKeyStore;
        KeyStore trustStore;
        try (FileOutputStream tsFos = new FileOutputStream(pkiConfiguration.getTruststorePath())) {
            rootKeyStore = KeyStore.getInstance("PKCS11", p11PKIConfiguration.getProvider());
            rootKeyStore.load(null, p11PKIConfiguration.getPkcs11Pin());
            X509Certificate caCert = certificateBuilder.buildAndSignCert(certificateBuilder.generateSerialNumber(p11PKIConfiguration.getProvider()), caKeyPair.getPrivate(), caKeyPair.getPublic(), caKeyPair.getPublic(),
                    new X500Name(rootCertX500Name), new X500Name(rootCertX500Name), null, "ROOTCA", null, crlUrl, p11PKIConfiguration.getProvider());
            Certificate[] certChain = new Certificate[1];
            certChain[0] = caCert;
            rootKeyStore.setKeyEntry(rootCAAlias, caKeyPair.getPrivate(), null, certChain);

            // Store away the truststore
            trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, pkiConfiguration.getTruststorePassword().toCharArray());
            trustStore.setCertificateEntry(rootCAAlias, caCert);
            trustStore.store(tsFos, pkiConfiguration.getTruststorePassword().toCharArray());
            p11PKIConfiguration.providerLogout();
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException | OperatorCreationException e) {
            p11PKIConfiguration.providerLogout();
            throw new PKIRuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Loads a CSV file with information about revoked certificates into a RevocationInfo list.
     * The CSV file must use semi-colon for separation and in the format:
     * serial-number;revocation-reason;date
     * An example:
     * 345678954765889809876543;cacompromise;2017-04-31
     *
     * @param revocationFile Path to the file that should be loaded.
     * @return List of certificates that has been/should be revoked.
     */
    public List<RevocationInfo> loadRevocationFile(String revocationFile) {
        String csvLine;
        String cvsSplitBy = ";";
        List<RevocationInfo> revocationInfos = new ArrayList<>();
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        try (BufferedReader br = new BufferedReader(new FileReader(revocationFile))) {
            while ((csvLine = br.readLine()) != null) {
                if (csvLine.trim().isEmpty()) {
                    continue;
                }
                String[] revocationInfoSplit = csvLine.split(cvsSplitBy);
                if (revocationInfoSplit.length != 3) {
                    throw new PKIRuntimeException("Missing info from line: " + csvLine);
                }
                RevocationInfo info = new RevocationInfo();
                info.setSerialNumber(new BigInteger(revocationInfoSplit[0].trim()));
                info.setRevokeReason(CRLReason.values()[Revocation.getCRLReasonFromString(revocationInfoSplit[1].trim().toLowerCase())]);
                Date revokedAt = format.parse(revocationInfoSplit[2].trim());
                if (revokedAt == null) {
                    throw new PKIRuntimeException("Invalid date format!");
                }
                info.setRevokedAt(revokedAt);
                revocationInfos.add(info);
            }
        } catch (FileNotFoundException e) {
            throw new PKIRuntimeException("Could not find the revocation info file!", e);
        } catch (IOException e) {
            throw new PKIRuntimeException(e);
        } catch (ParseException e) {
            throw new PKIRuntimeException("Invalid date format!", e);
        }

        return revocationInfos;
    }

    /**
     * Generates a CRL for the root CA. It is expected that
     * a RootCaKeystore is defined in PKIConfiguration and exists.
     *
     * @param outputCaCrlPath Output path where to place the CRL.
     * @param revocationFile Path to the CSV file which contains revocation info.
     * @param rootCAAlias The alias of the root CA.
     */
    public void generateRootCRL(String outputCaCrlPath, String revocationFile, String rootCAAlias) {
        List<RevocationInfo> revocationInfos = loadRevocationFile(revocationFile);

        try (InputStream readStream = new FileInputStream(pkiConfiguration.getRootCaKeystorePath())) {
            KeyStore rootks = KeyStore.getInstance(KEYSTORE_TYPE);
            rootks.load(readStream, pkiConfiguration.getRootCaKeystorePassword().toCharArray());
            KeyStore.ProtectionParameter protParam = new KeyStore.PasswordProtection(pkiConfiguration.getRootCaKeystorePassword().toCharArray());
            KeyStore.PrivateKeyEntry rootCertEntry;
            rootCertEntry = (KeyStore.PrivateKeyEntry) rootks.getEntry(rootCAAlias, protParam);
            String rootCertX500Name = new JcaX509CertificateHolder((X509Certificate) rootCertEntry.getCertificate()).getSubject().toString();
            Revocation.generateRootCACRL(rootCertX500Name, revocationInfos, rootCertEntry, outputCaCrlPath, null);
        } catch (NoSuchAlgorithmException | UnrecoverableEntryException | KeyStoreException e) {
            throw new PKIRuntimeException("Unable to generate RootCACRL", e);
        } catch (CertificateException e) {
            throw new PKIRuntimeException("Could not load root certificate!", e);
        } catch (FileNotFoundException e) {
            throw new PKIRuntimeException("Could not find root keystore!", e);
        } catch (IOException e) {
            throw new PKIRuntimeException("Could not load root keystore!", e);
        }
    }

    /**
     * Generates a root CA CRL using a private key stored in an HSM.
     *
     * @param outputCaCrlPath Output path where to place the CRL.
     * @param revocationFile Path to the CSV file which contains revocation info.
     * @param rootCAAlias The alias of the root CA.
     */
    public void generateRootCRLP11(String outputCaCrlPath, String revocationFile, String rootCAAlias) {
        List<RevocationInfo> revocationInfos = loadRevocationFile(revocationFile);
        P11PKIConfiguration p11PKIConfiguration = (P11PKIConfiguration) pkiConfiguration;

        p11PKIConfiguration.providerLogin();
        KeyStore rootKeyStore;
        try {
            rootKeyStore = KeyStore.getInstance("PKCS11", p11PKIConfiguration.getProvider());
            rootKeyStore.load(null, p11PKIConfiguration.getPkcs11Pin());
            KeyStore.PrivateKeyEntry rootCertEntry = (KeyStore.PrivateKeyEntry) rootKeyStore.getEntry(rootCAAlias, null);
            String rootCertX500Name = new JcaX509CertificateHolder((X509Certificate) rootCertEntry.getCertificate()).getSubject().toString();
            Revocation.generateRootCACRL(rootCertX500Name, revocationInfos, rootCertEntry, outputCaCrlPath, p11PKIConfiguration.getProvider());
            p11PKIConfiguration.providerLogout();
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException | UnrecoverableEntryException e) {
            p11PKIConfiguration.providerLogout();
            throw new PKIRuntimeException("Could not generate CRL", e);
        }
    }
}
