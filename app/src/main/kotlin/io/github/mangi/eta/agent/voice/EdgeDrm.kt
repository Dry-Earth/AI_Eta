package io.github.mangi.eta.agent.voice

/**
 * Edge「大声朗读」的 v2 `Sec-MS-GEC` 令牌。
 *
 * 微软在 2025 年把令牌从「300 秒窗口时间戳 + 客户端令牌的 SHA-256」换成了
 * 「CS64 摘要 + 加盐 SHA-256」的复合格式（`<16 位十六进制>-<64 位十六进制>`），
 * 旧格式握手会被直接拒绝（HTTP 403）。这里的实现与 Edge 网页端一致，
 * 输入只依赖当前时间，不涉及账号或用户数据。
 *
 * 该文件是纯 Kotlin 实现，便于用固定测试向量验证；任何常量写错都会导致鉴权失败。
 */
internal object EdgeDrm {
    /** 与令牌配套的协议版本头，两者必须成对使用。 */
    const val VERSION = "2-143.0.3650.75"

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L
    private const val WINDOW_SECONDS = 300L

    fun secMsGecV2(epochMillis: Long): String {
        var ticks = epochMillis / 1_000L + WINDOWS_EPOCH_SECONDS
        ticks -= ticks % WINDOW_SECONDS
        val data = (ticks * 10_000_000L).toString() + TRUSTED_CLIENT_TOKEN
        val bytes = data.toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(bytes)
        val cs64 = cs64Digest(bytes, digest)
        return hexLittleEndian(cs64[0], cs64[1]) + "-" + hexBigEndian(digest)
    }

    private fun ints(vararg values: Long): IntArray = IntArray(values.size) { values[it].toInt() }

    private val INITIAL = ints(
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    )

    /** 第一条消息块（56 个零字节 + 令牌前缀 + 填充位）预计算后的中间状态。 */
    private val SEED_STATE = ints(
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    )

    private val SEED_SCHEDULE = ints(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491,
        0xC0DD25A0, 0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060, 0x72766564,
    )

    private val ROUND_CONSTANTS = ints(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    )

    private val ADJUSTMENT = 0xC9DC06A7L.toInt()
    private val SIGMA0_ADJUSTMENT = 0x059C0B80L.toInt()
    private val SIGMA1_ADJUSTMENT = 0x000B92C0L.toInt()

    /** 仅第一条预计算块使用：第 14 轮常量被替换。 */
    private val SEED_CONSTANTS: IntArray = ROUND_CONSTANTS.copyOf().also { it[14] = ADJUSTMENT }

    private val CS64_COEFFICIENTS = ints(
        0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933, 0x8D1EC211, 0x314EF5D5, 0xE92491B1,
        0xF0A5B9D5, 0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8, 0x35FA38C6, 0xCB49E3C6,
    )

    private val CS64_OFFSETS = ints(
        0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF, 0xD405B4D5, 0x26E55F3C, 0x607801C6,
        0x82B2C1FB, 0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61, 0xBB04A2C1, 0xC5626167,
    )

    private val CS64_FACTOR = 0x82893933L.toInt()
    private val CS64_ADJUSTMENT0 = 0xF2F90B1AL.toInt()
    private val CS64_ADJUSTMENT1 = 0x0B1A0000L.toInt()

    private fun rotr(value: Int, bits: Int): Int = (value ushr bits) or (value shl (32 - bits))
    private fun rol16(value: Int): Int = (value shl 16) or (value ushr 16)
    private fun smallSigma0(value: Int): Int = rotr(value, 7) xor rotr(value, 18) xor (value ushr 3)
    private fun smallSigma1(value: Int): Int = rotr(value, 17) xor rotr(value, 19) xor (value ushr 10)
    private fun bigSigma0(value: Int): Int = rotr(value, 2) xor rotr(value, 13) xor rotr(value, 22)
    private fun bigSigma1(value: Int): Int = rotr(value, 6) xor rotr(value, 11) xor rotr(value, 25)

    private fun choose(x: Int, y: Int, z: Int): Int = (x and y) xor (x.inv() and z)
    private fun majority(x: Int, y: Int, z: Int): Int = (x and y) xor (x and z) xor (y and z)

    private fun expandSchedule(words: IntArray, firstWord: Int) {
        for (index in firstWord until 64) {
            words[index] = smallSigma1(words[index - 2]) + words[index - 7] +
                smallSigma0(words[index - 15]) + words[index - 16]
        }
    }

    private fun compress(state: IntArray, words: IntArray, constants: IntArray, firstRound: Int): IntArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (round in firstRound until 64) {
            val t1 = h + bigSigma1(e) + choose(e, f, g) + constants[round] + words[round]
            val t2 = bigSigma0(a) + majority(a, b, c)
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** 令牌前 7 字节构成的盐已折叠进第一条消息块的预计算状态。 */
    private fun seedState(data: ByteArray): IntArray {
        val words = IntArray(64)
        words[14] = ((data[0].toInt() and 0xFF) shl 16) or
            ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        words[15] = bigEndianInt(data, 3)
        for (index in 16..29) {
            var value = SEED_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    smallSigma1(words[index - 2]) xor SIGMA1_ADJUSTMENT
                } else {
                    smallSigma1(words[index - 2])
                }
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    smallSigma0(words[index - 15]) xor SIGMA0_ADJUSTMENT
                } else {
                    smallSigma0(words[index - 15])
                }
            }
            words[index] = value
        }
        words[30] = smallSigma1(words[28]) + words[23] + smallSigma0(words[15]) + words[14] +
            ADJUSTMENT - ROUND_CONSTANTS[14]
        expandSchedule(words, 31)
        val working = compress(SEED_STATE, words, SEED_CONSTANTS, 14)
        return IntArray(8) { INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): IntArray {
        require(data.size >= 7) { "令牌输入过短" }
        val messageLength = 57 + data.size
        val paddingLength = ((55 - messageLength) % 64 + 64) % 64
        val tail = ByteArray(data.size - 7 + 1 + paddingLength + 8)
        System.arraycopy(data, 7, tail, 0, data.size - 7)
        tail[data.size - 7] = 0x80.toByte()
        val bitLength = messageLength.toLong() * 8L
        for (index in 0 until 8) {
            tail[tail.size - 1 - index] = (bitLength ushr (8 * index)).toByte()
        }
        var state = seedState(data)
        val words = IntArray(64)
        var offset = 0
        while (offset < tail.size) {
            for (index in 0 until 16) words[index] = bigEndianInt(tail, offset + index * 4)
            expandSchedule(words, 16)
            val working = compress(state, words, ROUND_CONSTANTS, 0)
            state = IntArray(8) { state[it] + working[it] }
            offset += 64
        }
        return state
    }

    private fun littleEndianWords(bytes: ByteArray): IntArray {
        val count = (bytes.size + 3) / 4
        val words = IntArray(if (count % 2 == 0) count else count + 1)
        for (index in 0 until count) {
            var value = 0
            for (byteIndex in 0 until 4) {
                val position = index * 4 + byteIndex
                if (position < bytes.size) {
                    value = value or ((bytes[position].toInt() and 0xFF) shl (8 * byteIndex))
                }
            }
            words[index] = value
        }
        return words
    }

    private fun cs64Digest(data: ByteArray, digest: IntArray): IntArray {
        val keyA = Integer.reverseBytes(digest[0]) or 1
        val keyB = Integer.reverseBytes(digest[1]) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
        val words = littleEndianWords(byteArrayOf(0) + data)
        val terms = IntArray(CS64_COEFFICIENTS.size + words.size)
        val offsets = IntArray(CS64_COEFFICIENTS.size + words.size)
        for (index in CS64_COEFFICIENTS.indices) {
            terms[index] = CS64_COEFFICIENTS[index] * factors[index % 2]
            offsets[index] = CS64_OFFSETS[index]
        }
        for (index in words.indices) {
            val position = CS64_COEFFICIENTS.size + index
            var term = words[index] * if (index % 2 == 0) keyA else keyB
            var offset = rol16(words[index]) * if (index % 2 == 0) 0xEF0569FBL.toInt() else 0xC31713DBL.toInt()
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms[position] = term
            offsets[position] = offset
        }
        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var value = rol16(offsets[index] + x)
            var mixed = value * 0x76F50F34L.toInt()
            swapAcc += rol16(mixed) * 0xC3EFEA97L.toInt() + mixed
            value = rol16(offsets[index + 1] + y)
            mixed = value * 0x1881CE3CL.toInt()
            swapLast = rol16(mixed) * 0x35BD1EC9L.toInt() + mixed
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111L.toInt()
            state = rol16(state) * 0x87085B9FL.toInt()
            state = rol16(state) * 0x12CEB96DL.toInt()
            state = rol16(state) * 0x257E1D83L.toInt()
            acc += state
            value = state * keyB + y
            value = rol16(value) * 0xA27416F5L.toInt()
            value = rol16(value) * 0xD38396FFL.toInt()
            state = rol16(rol16(value) * 0x7C932B89L.toInt()) * 0xBFA49F69L.toInt()
            acc += state
            index += 2
        }
        return intArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun hexWord(value: Int): String = String.format(java.util.Locale.ROOT, "%08X", value)

    /** CS64 按小端字节序拼接，与浏览器端生成的字符串一致。 */
    private fun hexLittleEndian(first: Int, second: Int): String =
        hexWord(Integer.reverseBytes(first)) + hexWord(Integer.reverseBytes(second))

    private fun hexBigEndian(values: IntArray): String = buildString(values.size * 8) {
        for (value in values) append(hexWord(value))
    }
}
