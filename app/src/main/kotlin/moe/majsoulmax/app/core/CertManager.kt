package moe.majsoulmax.app.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.security.KeyChain
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Locale

/**
 * Everything about the hudsucker root certificate: reading it, telling whether
 * the system already trusts it, and getting the user to the install UI in as few
 * taps as the ROM allows.
 *
 * Android has narrowed CA installation repeatedly (an app-launched installer
 * worked through Android 10 and is unreliable after), so [installIntents]
 * deliberately returns an ordered list of fallbacks instead of one intent, and
 * [exportForManualInstall] exists for the case where none of them resolve.
 */
object CertManager {

    private const val TAG = "CertManager"
    private const val CERT_NAME = "hudsucker"
    private const val CERT_MIME = "application/x-x509-ca-cert"

    data class CertInfo(
        val subject: String,
        val issuer: String,
        val notBefore: String,
        val notAfter: String,
        val sha256: String,
        val trusted: Boolean,
        val expired: Boolean,
    )

    fun certificateFile(context: Context): File = Paths.certFile(context)

    suspend fun load(context: Context): CertInfo? = withContext(Dispatchers.IO) {
        val cert = readCertificate(context) ?: return@withContext null
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        val now = System.currentTimeMillis()
        CertInfo(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notBefore = df.format(cert.notBefore),
            notAfter = df.format(cert.notAfter),
            sha256 = fingerprint(cert),
            trusted = isTrusted(cert),
            expired = cert.notAfter.time < now || cert.notBefore.time > now,
        )
    }

    suspend fun isTrusted(context: Context): Boolean = withContext(Dispatchers.IO) {
        readCertificate(context)?.let { isTrusted(it) } ?: false
    }

    fun readCertificate(context: Context): X509Certificate? {
        val file = certificateFile(context)
        val stream = when {
            file.exists() -> file.inputStream()
            // Before the first asset unpack we can still read it out of the APK.
            else -> runCatching { context.assets.open("ca/hudsucker.cer") }.getOrNull()
        } ?: return null

        return try {
            stream.use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot parse hudsucker.cer", e)
            null
        }
    }

    /**
     * Walks the merged system + user trust store. `AndroidCAStore` exposes user
     * CAs under `user:*` aliases, which is exactly what we install into, so a
     * plain scan answers the question for both stores at once.
     */
    private fun isTrusted(cert: X509Certificate): Boolean = try {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val target = cert.encoded
        store.aliases().asSequence().any { alias ->
            val candidate = runCatching { store.getCertificate(alias) }.getOrNull()
            candidate is X509Certificate && candidate.encoded.contentEquals(target)
        }
    } catch (e: Exception) {
        Log.e(TAG, "cannot read AndroidCAStore", e)
        false
    }

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /**
     * Ordered install candidates, best first. Callers try each until one starts.
     *
     * These are intentionally *not* pre-filtered through `resolveActivity`: under
     * Android 11 package visibility the certificate installer is invisible to that
     * query unless it is declared in `<queries>`, and even then some ROMs answer
     * inconsistently. Attempting the launch and catching the failure is the only
     * reliable test, so the caller's try-each loop is the filter.
     */
    fun installIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val cert = readCertificate(context)

        if (cert != null) {
            // 1. The direct installer. Silently unavailable on newer releases,
            //    hence everything below it.
            runCatching {
                intents += KeyChain.createInstallIntent().apply {
                    putExtra(KeyChain.EXTRA_CERTIFICATE, cert.encoded)
                    putExtra(KeyChain.EXTRA_NAME, CERT_NAME)
                }
            }
        }

        // 2. Hand the file to whatever claims the CA-cert mime type. On most ROMs
        //    this lands straight in com.android.certinstaller.
        shareUri(context)?.let { uri ->
            intents += Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, CERT_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // 3./4. Settings entry points, so the user is at most a few taps away.
        intents += Intent(Settings.ACTION_SECURITY_SETTINGS)
        intents += Intent(Settings.ACTION_SETTINGS)

        return intents
    }

    private fun shareUri(context: Context): Uri? {
        val file = certificateFile(context)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull()
    }

    /**
     * Drops a copy where the system file picker can reach it, for the manual
     * Settings → Install a certificate path.
     *
     * @return the user-visible location, or null on failure.
     */
    suspend fun exportForManualInstall(context: Context): String? = withContext(Dispatchers.IO) {
        val source = certificateFile(context)
        if (!source.exists()) return@withContext null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "hudsucker.cer")
                    put(MediaStore.Downloads.MIME_TYPE, CERT_MIME)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/hudsucker.cer"
            } else {
                @Suppress("DEPRECATION")
                val downloads =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val target = File(downloads, "hudsucker.cer")
                source.copyTo(target, overwrite = true)
                target.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "cert export failed", e)
            null
        }
    }
}
