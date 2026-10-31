package com.macaupos.nativeapp.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256(RFC 2898)。
 *
 * 為何唔用 `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`:
 * 呢個 provider **API 26 先有**,我哋 minSdk 23(美團式舊機)。
 * `PBKDF2WithHmacSHA1` 就 API 10+ 都有,但為咗同 web 版 `offline-credential.ts`
 * 用同一個演算法標籤(`pbkdf2-sha256-120000`),呢度手寫 HMAC-SHA256 版本 ——
 * `javax.crypto.Mac("HmacSHA256")` API 11+ 就有,全機型安全。
 *
 * ⚠️ 同 server 側 `staff-pin.ts` 嘅 hash **唔可以互換**:native 存嘅係
 * 「本機隨機 salt 重新派生」嘅驗證器(同 web P4 同一個安全模型),
 * dump 走部機都只得到「呢部機、呢個員工」嘅 4 位 PIN。
 */
object Pbkdf2 {

    const val ALGO = "pbkdf2-sha256-120000"
    const val ITERATIONS = 120_000
    const val KEY_BITS = 256
    const val SALT_BYTES = 16

    fun derive(password: ByteArray, salt: ByteArray, iterations: Int = ITERATIONS, dkLen: Int = KEY_BITS / 8): ByteArray {
        require(iterations >= 1) { "iterations 必須 >= 1" }
        require(dkLen >= 1) { "dkLen 必須 >= 1" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (password.isEmpty()) byteArrayOf() else password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        var offset = 0
        for (block in 1..blocks) {
            // U1 = PRF(P, S || INT_MSB32(i))
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    ((block ushr 24) and 0xff).toByte(),
                    ((block ushr 16) and 0xff).toByte(),
                    ((block ushr 8) and 0xff).toByte(),
                    (block and 0xff).toByte(),
                )
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, offset, hLen)
            offset += hLen
        }
        return out.copyOf(dkLen)
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "hex 長度必須係雙數" }
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    private const val HEX = "0123456789abcdef"
}
