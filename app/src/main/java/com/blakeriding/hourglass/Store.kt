package com.blakeriding.hourglass

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** All app data in one JSON file in the app's private storage. */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowStructuredMapKeys = true }
    private var file: AtomicFile? = null
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val dir = context.applicationContext.filesDir
        val f = AtomicFile(File(dir, "state.json"))
        file = f
        _state.value = try {
            json.decodeFromString(AppState.serializer(), String(f.readFully()))
        } catch (_: java.io.FileNotFoundException) {
            AppState()
        } catch (e: Exception) {
            // Keep the unreadable file for inspection rather than overwriting it silently.
            Log.e("Hourglass", "State file unreadable, starting fresh", e)
            f.baseFile.copyTo(File(dir, "state.broken-${System.currentTimeMillis()}.json"), overwrite = true)
            AppState()
        }
    }

    @Synchronized
    fun update(change: (AppState) -> AppState): AppState {
        val old = _state.value
        val new = change(old)
        if (new != old) {
            save(new)
            _state.value = new
        }
        return new
    }

    private fun save(state: AppState) {
        val f = checkNotNull(file) { "Store.init not called" }
        val out = f.startWrite()
        try {
            out.write(json.encodeToString(AppState.serializer(), state).toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }
}

fun AppState.logTamper(kind: TamperKind, at: Long, detail: String = ""): AppState =
    copy(tamper = (tamper + TamperEvent(at, kind, detail)).takeLast(TAMPER_KEEP))

// ---------- Passcode ----------

object Passcode {
    private const val ITERATIONS = 120_000
    private val rng = SecureRandom()

    fun hash(code: String, iterations: Int = ITERATIONS): Secret {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        return Secret(b64(salt), b64(derive(code, salt, iterations)), iterations)
    }

    fun matches(code: String, secret: Secret?): Boolean {
        if (secret == null) return false
        val expected = Base64.getDecoder().decode(secret.hash)
        val actual = derive(code, Base64.getDecoder().decode(secret.salt), secret.iterations)
        return java.security.MessageDigest.isEqual(expected, actual)
    }

    /** 12 characters, grouped for writing down: "K7QM-2XWD-9PRT". No 0/O or 1/I. */
    fun newRecoveryCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val raw = (1..12).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        return raw.chunked(4).joinToString("-")
    }

    fun normalizeRecovery(text: String) = text.uppercase().filter { it.isLetterOrDigit() }.chunked(4).joinToString("-")

    private fun derive(code: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

sealed interface CheckResult {
    data object Ok : CheckResult
    data object Wrong : CheckResult
    data class LockedOut(val until: Long) : CheckResult
}

/** Checks a passcode, counting failures and applying the lockout. Slow (PBKDF2): call off the main thread. */
fun checkPasscode(code: String, nowMs: Long): CheckResult {
    val s = Store.state.value
    if (s.lockedUntil > nowMs) return CheckResult.LockedOut(s.lockedUntil)
    return if (Passcode.matches(code, s.passcode)) {
        Store.update { it.copy(failedAttempts = 0, lockedUntil = 0) }
        CheckResult.Ok
    } else {
        val after = Store.update {
            val n = it.failedAttempts + 1
            it.copy(failedAttempts = n, lockedUntil = nowMs + lockoutMs(n)).logTamper(TamperKind.WRONG_PASSCODE, nowMs)
        }
        if (after.lockedUntil > nowMs) CheckResult.LockedOut(after.lockedUntil) else CheckResult.Wrong
    }
}
