/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.certinstaller;

import static android.security.KeyStore.UID_SELF;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.Credentials;
import android.security.IKeyChainService;
import android.security.KeyChain;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.org.conscrypt.TrustedCertificateStore;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.BasicConstraints;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A helper class for accessing the raw data in the intent extra and handling
 * certificates.
 */
class CredentialHelper {
    private static final String DATA_KEY = "data";
    private static final String CERTS_KEY = "crts";
    private static final String USER_KEY_ALGORITHM = "user_key_algorithm";
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    private static final String TAG = "CredentialHelper";

    // keep raw data from intent's extra
    private HashMap<String, byte[]> mBundle = new HashMap<String, byte[]>();

    private String mName = "";
    private String mCertUsageSelected = "";
    private String mReferrer = "";
    private int mUid = Process.INVALID_UID;
    private PrivateKey mUserKey;
    private X509Certificate mUserCert;
    private List<X509Certificate> mCaCerts = new ArrayList<X509Certificate>();

    CredentialHelper() {
    }

    /**
     * @param byteMap keeps raw data from intent's extra
     * @param name
     * @param referrer
     * @param certUsageSelected used to assign mUid according to certificate usage
     * @param uid is ignored unless certUsageSelected is null
     */
    CredentialHelper(@NonNull Map<String, byte[]> byteMap, @Nullable String name,
            @Nullable String referrer, @Nullable String certUsageSelected, int uid) {
        if (name != null) {
            mName = name;
        }

        if (referrer != null) {
            mReferrer = referrer;
        }

        if (certUsageSelected != null) {
            setCertUsageSelectedAndUid(certUsageSelected);
        } else {
            mUid = uid;
        }

        for (String key : byteMap.keySet()) {
            byte[] bytes = byteMap.get(key);
            Log.d(TAG, "   " + key + ": " + ((bytes == null) ? -1 : bytes.length));
            mBundle.put(key, bytes);
        }
        parseCert(getData(KeyChain.EXTRA_CERTIFICATE));
    }

    synchronized void onSaveStates(Bundle outStates) {
        try {
            outStates.putSerializable(DATA_KEY, mBundle);
            outStates.putString(KeyChain.EXTRA_NAME, mName);
            outStates.putInt(Credentials.EXTRA_INSTALL_AS_UID, mUid);
            if (mUserKey != null) {
                Log.d(TAG, "Key algorithm: " + mUserKey.getAlgorithm());
                outStates.putString(USER_KEY_ALGORITHM, mUserKey.getAlgorithm());
                outStates.putByteArray(Credentials.USER_PRIVATE_KEY,
                        mUserKey.getEncoded());
            }
            ArrayList<byte[]> certs = new ArrayList<byte[]>(mCaCerts.size() + 1);
            if (mUserCert != null) {
                certs.add(mUserCert.getEncoded());
            }
            for (X509Certificate cert : mCaCerts) {
                certs.add(cert.getEncoded());
            }
            outStates.putByteArray(CERTS_KEY, Util.toBytes(certs));
        } catch (CertificateEncodingException e) {
            throw new AssertionError(e);
        }
    }

    void onRestoreStates(Bundle savedStates) {
        mBundle = (HashMap) savedStates.getSerializable(DATA_KEY);
        mName = savedStates.getString(KeyChain.EXTRA_NAME);
        mUid = savedStates.getInt(Credentials.EXTRA_INSTALL_AS_UID, Process.INVALID_UID);
        String userKeyAlgorithm = savedStates.getString(USER_KEY_ALGORITHM);
        byte[] userKeyBytes = savedStates.getByteArray(Credentials.USER_PRIVATE_KEY);
        Log.d(TAG, "Loaded key algorithm: " + userKeyAlgorithm);
        if (userKeyAlgorithm != null && userKeyBytes != null) {
            setPrivateKey(userKeyAlgorithm, userKeyBytes);
        }

        ArrayList<byte[]> certs = Util.fromBytes(savedStates.getByteArray(CERTS_KEY));
        for (byte[] cert : certs) {
            parseCert(cert);
        }
    }

    X509Certificate getUserCertificate() {
        return mUserCert;
    }

    private void parseCert(byte[] bytes) {
        if (bytes == null) {
            return;
        }

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate)
                    certFactory.generateCertificate(
                            new ByteArrayInputStream(bytes));
            if (isCa(cert)) {
                Log.d(TAG, "got a CA cert");
                mCaCerts.add(cert);
            } else {
                Log.d(TAG, "got a user cert");
                mUserCert = cert;
            }
        } catch (CertificateException e) {
            Log.w(TAG, "parseCert(): " + e);
        }
    }

    private boolean isCa(X509Certificate cert) {
        try {
            // TODO: add a test about this
            byte[] asn1EncodedBytes = cert.getExtensionValue("2.5.29.19");
            if (asn1EncodedBytes == null) {
                return false;
            }
            DEROctetString derOctetString = (DEROctetString)
                    new ASN1InputStream(asn1EncodedBytes).readObject();
            byte[] octets = derOctetString.getOctets();
            ASN1Sequence sequence = (ASN1Sequence)
                    new ASN1InputStream(octets).readObject();
            return BasicConstraints.getInstance(sequence).isCA();
        } catch (IOException e) {
            return false;
        }
    }

    boolean hasPkcs12KeyStore() {
        return mBundle.containsKey(KeyChain.EXTRA_PKCS12);
    }

    boolean hasPrivateKey() {
        return mBundle.containsKey(Credentials.EXTRA_PRIVATE_KEY);
    }

    int getUidFromCertificateUsage(String certUsage) {
        if (Credentials.CERTIFICATE_USAGE_WIFI.equals(certUsage)) {
            return Process.WIFI_UID;
        } else {
            return UID_SELF;
        }
    }

    boolean hasUserCertificate() {
        return (mUserCert != null);
    }

    boolean hasCaCerts() {
        return !mCaCerts.isEmpty();
    }

    boolean hasAnyForSystemInstall() {
        return (mUserKey != null) || hasUserCertificate() || hasCaCerts();
    }

    void setPrivateKey(String algorithm, byte[] bytes) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
            mUserKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (InvalidKeySpecException e) {
            throw new AssertionError(e);
        }
    }

    boolean containsAnyRawData() {
        return !mBundle.isEmpty();
    }

    byte[] getData(String key) {
        return mBundle.get(key);
    }

    void putPkcs12Data(byte[] data) {
        mBundle.put(KeyChain.EXTRA_PKCS12, data);
    }

    CharSequence getDescription(Context context) {
        // TODO: create more descriptive string
        StringBuilder sb = new StringBuilder();
        String newline = "<br>";
        if (mUserKey != null) {
            sb.append(context.getString(R.string.one_userkey)).append(newline);
            sb.append(context.getString(R.string.userkey_type)).append(mUserKey.getAlgorithm())
                    .append(newline);
        }
        if (mUserCert != null) {
            sb.append(context.getString(R.string.one_usercrt)).append(newline);
        }
        int n = mCaCerts.size();
        if (n > 0) {
            if (n == 1) {
                sb.append(context.getString(R.string.one_cacrt));
            } else {
                sb.append(context.getString(R.string.n_cacrts, n));
            }
        }
        return Html.fromHtml(sb.toString());
    }

    void setName(String name) {
        mName = name;
    }

    String getName() {
        return mName;
    }

    void setCertUsageSelectedAndUid(String certUsageSelected) {
        mCertUsageSelected = certUsageSelected;
        mUid = getUidFromCertificateUsage(certUsageSelected);
    }

    String getCertUsageSelected() {
        return mCertUsageSelected;
    }

    boolean calledBySettings() {
        return mReferrer != null && mReferrer.equals(SETTINGS_PACKAGE);
    }

    Intent createSystemInstallIntent(final Context context) {
        Intent intent = new Intent("com.android.credentials.INSTALL");
        // To prevent the private key from being sniffed, we explicitly spell
        // out the intent receiver class.
        intent.setComponent(ComponentName.unflattenFromString(
                context.getString(R.string.config_system_install_component)));
        intent.putExtra(Credentials.EXTRA_INSTALL_AS_UID, mUid);
        intent.putExtra(Credentials.EXTRA_USER_KEY_ALIAS, mName);
        try {
            if (mUserKey != null) {
                intent.putExtra(Credentials.EXTRA_USER_PRIVATE_KEY_DATA,
                        mUserKey.getEncoded());
            }
            if (mUserCert != null) {
                intent.putExtra(Credentials.EXTRA_USER_CERTIFICATE_DATA,
                        Credentials.convertToPem(mUserCert));
            }
            if (!mCaCerts.isEmpty()) {
                X509Certificate[] caCerts
                        = mCaCerts.toArray(new X509Certificate[mCaCerts.size()]);
                intent.putExtra(Credentials.EXTRA_CA_CERTIFICATES_DATA,
                        Credentials.convertToPem(caCerts));
            }
            return intent;
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (CertificateEncodingException e) {
            throw new AssertionError(e);
        }
    }

    boolean installVpnAndAppsTrustAnchors(Context context, IKeyChainService keyChainService) {
        final TrustedCertificateStore trustedCertificateStore = new TrustedCertificateStore();
        for (X509Certificate caCert : mCaCerts) {
            byte[] bytes = null;
            try {
                bytes = caCert.getEncoded();
            } catch (CertificateEncodingException e) {
                throw new AssertionError(e);
            }
            if (bytes != null) {
                try {
                    keyChainService.installCaCertificate(bytes);
                } catch (RemoteException e) {
                    Log.w(TAG, "installCaCertsToKeyChain(): " + e);
                    return false;
                }

                String alias = trustedCertificateStore.getCertificateAlias(caCert);
                if (alias == null) {
                    Log.e(TAG, "alias is null");
                    return false;
                }

                maybeApproveCaCert(context, alias);
            }
        }
        return true;
    }

    private void maybeApproveCaCert(Context context, String alias) {
        final KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        if (keyguardManager.isDeviceSecure(UserHandle.myUserId())) {
            // Since the cert is installed by real user, the cert is approved by the user
            final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
            dpm.approveCaCert(alias, UserHandle.myUserId(), true);
        }
    }

    boolean hasPassword() {
        if (!hasPkcs12KeyStore()) {
            return false;
        }
        try {
            return loadPkcs12Internal(new PasswordProtection(new char[] {})) == null;
        } catch (Exception e) {
            return true;
        }
    }

    boolean extractPkcs12(String password) {
        try {
            return extractPkcs12Internal(new PasswordProtection(password.toCharArray()));
        } catch (Exception e) {
            Log.w(TAG, "extractPkcs12(): " + e, e);
            return false;
        }
    }

    private boolean extractPkcs12Internal(PasswordProtection password)
            throws Exception {
        // TODO: add test about this
        java.security.KeyStore keystore = loadPkcs12Internal(password);

        Enumeration<String> aliases = keystore.aliases();
        if (!aliases.hasMoreElements()) {
            Log.e(TAG, "PKCS12 file has no elements");
            return false;
        }

        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keystore.isKeyEntry(alias)) {
                KeyStore.Entry entry = keystore.getEntry(alias, password);
                Log.d(TAG, "extracted alias = " + alias + ", entry=" + entry.getClass());

                if (entry instanceof PrivateKeyEntry) {
                    if (TextUtils.isEmpty(mName)) {
                        mName = alias;
                    }
                    return installFrom((PrivateKeyEntry) entry);
                }
            } else {
                // KeyStore.getEntry with non-null ProtectionParameter can only be invoked on
                // PrivateKeyEntry or SecretKeyEntry.
                // See https://docs.oracle.com/javase/8/docs/api/java/security/KeyStore.html
                Log.d(TAG, "Skip non-key entry, alias = " + alias);
            }
        }
        return true;
    }

    private java.security.KeyStore loadPkcs12Internal(PasswordProtection password)
            throws Exception {
        java.security.KeyStore keystore = java.security.KeyStore.getInstance("PKCS12");
        keystore.load(new ByteArrayInputStream(getData(KeyChain.EXTRA_PKCS12)),
                      password.getPassword());
        return keystore;
    }

    private synchronized boolean installFrom(PrivateKeyEntry entry) {
        mUserKey = entry.getPrivateKey();
        mUserCert = (X509Certificate) entry.getCertificate();

        Certificate[] certs = entry.getCertificateChain();
        Log.d(TAG, "# certs extracted = " + certs.length);
        mCaCerts = new ArrayList<X509Certificate>(certs.length);
        for (Certificate c : certs) {
            X509Certificate cert = (X509Certificate) c;
            if (isCa(cert)) {
                mCaCerts.add(cert);
            }
        }
        Log.d(TAG, "# ca certs extracted = " + mCaCerts.size());

        return true;
    }

    /**
     * Returns true if this credential contains _only_ CA certificates to be used as trust anchors
     * for VPN and apps.
     */
    public boolean hasOnlyVpnAndAppsTrustAnchors() {
        if (!hasCaCerts()) {
            return false;
        }
        if (mUid != UID_SELF) {
            // VPN and Apps trust anchors can only be installed under UID_SELF
            return false;
        }

        if (mUserKey != null) {
            // We are installing a key pair for client authentication, its CA
            // should have nothing to do with VPN and apps trust anchors.
            return false;
        } else {
            return true;
        }
    }

    public String getReferrer() {
        return mReferrer;
    }

    @VisibleForTesting
    public int getUid() {
        return mUid;
    }
}
