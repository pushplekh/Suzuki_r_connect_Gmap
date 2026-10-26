package dev.mrwick.redline.nav

import android.graphics.Bitmap
import dev.mrwick.redline.util.AppLog

/**
 * Glues [BitmapHasher] + [ManeuverMap] + the text-only fallback into a single
 * decision for [GoogleMapsParser].
 *
 * Strategy:
 *   1. Recognized instruction text wins over perceptual hashes, which can collide.
 *      Hash lookup is a fallback only when text cannot classify the instruction.
 *   2. Always compute the text-fallback id too (cheap), so we can compare.
 *   3. If both bitmap-hash AND text returned non-default ids AND they DISAGREE,
 *      emit a WARN to AppLog — the bitmap-hash table has likely-wrong entries.
 *   4. If we used the text fallback AND a bitmap was present AND the text guess
 *      isn't the generic fallback AND [selfTrainEnabled] is true: register the
 *      hash → text-derived id in [ManeuverMap]. Self-training is OFF by default
 *      (see DISCOVERIES.md 2026-05-25 nav-arrow diagnosis) because a single
 *      wrong text guess can poison the bitmap table indefinitely.
 *
 * Every classification logs to AppLog so future rides have a per-notification
 * trace of (title, bitmap-present, decision-path, id-returned).
 */
object ManeuverClassifier {
    private const val TAG = "ManeuverClassifier"

    /**
     * Hamming-distance cap for considering an aHash a match. 5 of 64 bits ≈ 8 %
     * — empirically generous enough to absorb anti-aliasing and color-variant
     * rendering differences without colliding across distinct turn icons.
     *
     * ASSUMED: 5 bits tolerance is correct for Maps' 64dp turn icons; revisit
     * once we have real captures to measure inter/intra-class distance.
     */
    private const val HAMMING_TOLERANCE = 5

    /** Provenance of the chosen maneuver id, for diagnostics. */
    enum class Source { HashMatch, TextFallback, SelfTrainedFromText, Unclassified }

    /** Full classification result; [id] is null when neither source can classify it. */
    data class Result(val id: Int?, val source: Source, val textId: Int?, val hashId: Int?, val hash: Long?)

    /**
     * Classify the maneuver based on the captured icon [bitmap] and/or the raw
     * [instruction] text. Returns a Mappls maneuver ID (0..75), or null when
     * the instruction is unrecognized and the icon has no known hash.
     *
     * @param selfTrainEnabled if true, register fresh hash→text mappings on
     *   text-fallback decisions. Default false — self-training without verified
     *   cluster-side id semantics has a runaway-pollution failure mode.
     */
    fun classify(bitmap: Bitmap?, instruction: String?, selfTrainEnabled: Boolean = false): Int {
        return classifyDetailed(bitmap, instruction, selfTrainEnabled).id
            ?: MapplsIdGuesser.DEFAULT_MAPPLS_ID
    }

    /** Classify and log the full diagnostic record. */
    fun classifyDetailed(bitmap: Bitmap?, instruction: String?, selfTrainEnabled: Boolean): Result {
        val textId = MapplsIdGuesser.fromTextOrNull(instruction)
        val result = if (bitmap == null) {
            Result(
                id = textId,
                source = if (textId == null) Source.Unclassified else Source.TextFallback,
                textId = textId,
                hashId = null,
                hash = null,
            )
        } else {
            val hash = BitmapHasher.aHash64(bitmap)
            val hashId = ManeuverMap.fromBitmapHashNearest(hash, HAMMING_TOLERANCE)
            when {
                hashId != null -> resolveCandidates(textId, hashId, hash)
                // U-turn fallback direction must not teach an unverified icon side.
                selfTrainEnabled && textId != null && textId !in setOf(6, 41) &&
                    textId != MapplsIdGuesser.DEFAULT_MAPPLS_ID -> {
                    ManeuverMap.registerBitmapHash(hash, textId)
                    AppLog.i(
                        TAG,
                        "self-trained hash=0x${java.lang.Long.toHexString(hash)} -> id=$textId (from text)",
                    )
                    Result(
                        id = textId,
                        source = Source.SelfTrainedFromText,
                        textId = textId,
                        hashId = null,
                        hash = hash,
                    )
                }
                else -> Result(
                    id = textId,
                    source = if (textId == null) Source.Unclassified else Source.TextFallback,
                    textId = textId,
                    hashId = null,
                    hash = hash,
                )
            }
        }
        AppLog.i(
            TAG,
            "id=${result.id ?: "unclassified"} src=${result.source} text=${result.textId ?: "-"} hash=${result.hashId ?: "-"} instr=\"${instruction?.take(60)}\"",
        )
        if (result.hashId != null &&
            result.hashId != MapplsIdGuesser.DEFAULT_MAPPLS_ID &&
            result.textId != null &&
            result.textId != MapplsIdGuesser.DEFAULT_MAPPLS_ID &&
            result.hashId != result.textId
        ) {
            AppLog.w(
                TAG,
                "DISAGREE hash=${result.hashId} text=${result.textId} chosen=${result.id} — bitmap table may have a wrong entry for hash=0x${java.lang.Long.toHexString(result.hash ?: 0L)}",
            )
        }
        return result
    }

    internal fun resolveCandidates(textId: Int?, hashId: Int?, hash: Long? = null): Result =
        Result(
            id = textId ?: hashId,
            source = when {
                textId != null -> Source.TextFallback
                hashId != null -> Source.HashMatch
                else -> Source.Unclassified
            },
            textId = textId,
            hashId = hashId,
            hash = hash,
        )
}
