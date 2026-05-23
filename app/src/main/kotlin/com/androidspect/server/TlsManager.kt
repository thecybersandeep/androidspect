package com.androidspect.server

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * Self-signed TLS for the embedded server.
 *
 * Persists a PKCS12 keystore in the app's private dir:
 *   files/tls/keystore.p12
 *
 * The cert is signed by its own EC P-256 private key (no CA) and contains
 * SANs for `localhost`, `127.0.0.1`, and the device's current primary
 * IPv4. When the phone moves to a different Wi-Fi and that IP changes,
 * [getOrCreate] detects it and regenerates the cert so the browser still
 * accepts the connection.
 *
 * The keystore password is fixed (`androidspect`) - the file lives in the
 * app's private dir which is sandboxed from other apps. A user who has
 * root on this device (the only way to read it) could also just read the
 * password from SharedPreferences anyway, so hiding the keystore password
 * adds nothing.
 *
 * The SHA-256 fingerprint of the cert is surfaced via [TlsState.fingerprint]
 * so the Compose UI can display it. The user then compares against what
 * their browser shows when accepting the self-signed cert - pinning by eye.
 */
object TlsManager {

    const val ALIAS = "androidspect"
    const val KEYSTORE_PW = "androidspect"
    private const val TAG = "AndroTls"
    private const val VALID_DAYS = 365L * 5  // 5 years - pentester tool, no auto-renew machinery

    data class TlsState(
        val keyStore: KeyStore,
        val cert: X509Certificate,
        /** Colon-separated uppercase SHA-256 hex of the DER cert. */
        val fingerprint: String,
        /** DNS names + textual IPs declared in subjectAlternativeName. */
        val sans: List<String>
    )

    /**
     * Returns a usable [TlsState]. If no keystore exists OR the existing
     * cert's SANs don't include [currentIp], regenerates from scratch.
     */
    fun getOrCreate(context: Context, currentIp: String?): TlsState {
        val file = keystoreFile(context)
        if (file.exists()) {
            val existing = runCatching { load(file) }.getOrNull()
            if (existing != null && (currentIp == null || currentIp in existing.sans)) {
                Log.i(TAG, "using existing cert (fp=${existing.fingerprint.take(23)}…)")
                return existing
            }
            Log.i(TAG, "regenerating cert: stored cert unreadable or IP changed")
        }
        return generate(context, currentIp)
    }

    fun regenerate(context: Context, currentIp: String?): TlsState = generate(context, currentIp)

    fun fingerprintShort(state: TlsState): String =
        state.fingerprint.split(":").take(8).joinToString(":")

    // ---------- internals ----------

    private fun keystoreFile(context: Context): File =
        File(context.filesDir, "tls/keystore.p12").also { it.parentFile?.mkdirs() }

    private fun load(file: File): TlsState {
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, KEYSTORE_PW.toCharArray()) }
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return TlsState(ks, cert, sha256Fingerprint(cert), extractSans(cert))
    }

    private fun generate(context: Context, currentIp: String?): TlsState {
        Log.i(TAG, "generating EC P-256 keypair + self-signed cert (ip=$currentIp)")
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }
        val kp = kpg.generateKeyPair()
        val cert = buildCert(kp, currentIp)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, KEYSTORE_PW.toCharArray(), arrayOf(cert))
        keystoreFile(context).outputStream().use { ks.store(it, KEYSTORE_PW.toCharArray()) }
        return TlsState(ks, cert, sha256Fingerprint(cert), extractSans(cert))
    }

    private fun buildCert(kp: KeyPair, currentIp: String?): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)  // 1 min back for clock skew
        val notAfter = Date(now + VALID_DAYS * 86_400_000L)
        val serial = BigInteger.valueOf(now)
        val subject = X500Name("CN=AndroidSpect, O=AndroidSpect")

        val sans = mutableListOf(
            GeneralName(GeneralName.dNSName, "localhost"),
            GeneralName(GeneralName.iPAddress, "127.0.0.1")
        )
        if (currentIp != null && currentIp.isNotBlank() && currentIp != "127.0.0.1") {
            sans.add(GeneralName(GeneralName.iPAddress, currentIp))
        }

        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, kp.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        builder.addExtension(
            Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )
        builder.addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(sans.toTypedArray())
        )

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun sha256Fingerprint(cert: X509Certificate): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }

    /**
     * Extract subjectAlternativeName entries as plain strings. Returns DNS
     * names directly and converts IPAddress entries to their textual form.
     */
    private fun extractSans(cert: X509Certificate): List<String> {
        val raw = cert.subjectAlternativeNames ?: return emptyList()
        return raw.mapNotNull { entry ->
            // Each entry: List<*> where [0] is type tag, [1] is value (typically String).
            val value = entry.getOrNull(1) as? String ?: return@mapNotNull null
            // For IPAddress entries Java/BC returns the textual form already (e.g. "127.0.0.1").
            // No conversion needed.
            value
        }
    }
}
