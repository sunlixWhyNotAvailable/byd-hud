package com.bydhud.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class NavigatorSigningKey {
    private static final String ALIAS = "byd_hud_navigator_patcher_v1";

    private NavigatorSigningKey() {
    }

    static com.android.apksig.ApkSigner.SignerConfig signerConfig() throws Exception {
        KeyStore store = store();
        ensure(store);
        PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
        X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
        return new com.android.apksig.ApkSigner.SignerConfig.Builder(
                "BYD HUD navigator patcher", key, Collections.singletonList(certificate)).build();
    }

    static String localCertificateSha256() throws Exception {
        KeyStore store = store();
        ensure(store);
        X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
        return digest(certificate.getEncoded());
    }

    static String installedCertificateSha256(Context context, String packageName)
            throws Exception {
        if (Build.VERSION.SDK_INT < 28) throw new IOException("signing info unavailable");
        PackageInfo info = context.getPackageManager().getPackageInfo(
                packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        if (info.signingInfo == null || info.signingInfo.hasMultipleSigners()) {
            throw new IOException("unsupported installed signing state");
        }
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) {
            throw new IOException("installed signer count=" + (signers == null ? 0 : signers.length));
        }
        return digest(signers[0].toByteArray());
    }

    static String archiveCertificateSha256(List<X509Certificate> certificates) throws Exception {
        if (certificates == null || certificates.size() != 1) {
            throw new IOException("APK signer count="
                    + (certificates == null ? 0 : certificates.size()));
        }
        return digest(certificates.get(0).getEncoded());
    }

    static boolean installedUsesLocalKey(Context context, String packageName) {
        try {
            return localCertificateSha256().equals(installedCertificateSha256(context, packageName));
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean certificateMatchesLocalIfPresent(String certificateSha256) {
        try {
            KeyStore store = store();
            if (!store.containsAlias(ALIAS)) return false;
            X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
            return digest(certificate.getEncoded()).equals(certificateSha256);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static void ensure(KeyStore store) throws Exception {
        if (store.containsAlias(ALIAS)) return;
        long now = System.currentTimeMillis();
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new javax.security.auth.x500.X500Principal(
                        "CN=BYD HUD Navigator Patcher"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(new Date(now - 86400000L))
                .setCertificateNotAfter(new Date(now + 315360000000L))
                .build());
        generator.generateKeyPair();
    }

    private static String digest(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder output = new StringBuilder();
        for (byte value : digest.digest(bytes)) {
            output.append(String.format(Locale.ROOT, "%02X", value));
        }
        return output.toString();
    }
}
