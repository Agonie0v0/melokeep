package io.github.agonie0v0.melokeep.shared

import kotlin.math.roundToLong

data class MeloQualityPresentation(
	val title: String,
	val bitrateText: String?,
	val sizeText: String?,
	val subtitle: String,
	val isLossless: Boolean,
	val isPreviewOnly: Boolean,
	val needsLogin: Boolean,
)

object MeloQualityFormatter {
	private const val KIB = 1024L
	private const val MIB = 1024L * 1024L
	private const val GIB = 1024L * 1024L * 1024L
	private const val DEFAULT_LOSSLESS_KBPS = 950

	private val KNOWN_CODECS = setOf(
		"FLAC",
		"WAV",
		"ALAC",
		"APE",
		"DSF",
		"DFF",
		"MP3",
		"AAC",
		"M4A",
		"OGG",
		"OPUS",
	)
	private val ALNUM_TOKEN_REGEX = Regex("[a-zA-Z0-9]+")
	private val WORD_WITH_UNDERSCORE_REGEX = Regex("[a-zA-Z0-9_]+")
	private val CLEAN_CODEC_TOKEN_REGEX = Regex("^[a-zA-Z0-9]{2,8}$")

	private val MASTER_LABEL_REGEX = Regex(
		"母带|master|spatial|空间|全景|dsd",
		RegexOption.IGNORE_CASE,
	)
	private val HIRES_LABEL_REGEX = Regex(
		"hi-?res|24\\s*-?bit|高解析",
		RegexOption.IGNORE_CASE,
	)

	private val INTERNAL_EXACT_WORDS = setOf(
		"verified",
		"candidate",
		"resolved",
		"full",
		"probed_audio",
		"declared",
		"engine",
		"probe",
		"catalog_claim",
		"unverified_extension",
		"preview_only",
		"login_required",
		"unknown",
		"none",
		"null",
	)
	private val INTERNAL_COMPOUND_PARTS = listOf(
		listOf("probed", "audio"),
		listOf("catalog", "claim"),
		listOf("unverified", "extension"),
		listOf("preview", "only"),
		listOf("login", "required"),
	)
	private val EMPTY_BRACKETS_REGEX = Regex("\\(\\s*\\)|\\[\\s*\\]")
	private val REPEATED_SEPARATORS_REGEX = Regex("(?:\\s*[·|/]\\s*){2,}")
	private val REPEATED_DASHES_REGEX = Regex("(?:\\s+-\\s+){2,}")
	private val EDGE_SEPARATORS_REGEX = Regex("^[\\s·\\-_/|,]+|[\\s·\\-_/|,]+$")
	private val MULTI_WHITESPACE_REGEX = Regex("\\s+")

	private val LOSSLESS_CODECS = setOf("FLAC", "WAV", "ALAC", "APE", "DSF", "DFF")
	private val LOSSY_CODECS = setOf("MP3", "AAC", "M4A", "OGG", "OPUS")

	fun formatBytes(bytes: Long): String {
		val safe = bytes.coerceAtLeast(0L)
		return when {
			safe >= GIB -> {
				var whole = safe / GIB
				val rem = safe % GIB
				var frac = (rem * 100L + GIB / 2L) / GIB
				if (frac >= 100L) {
					whole += 1L
					frac -= 100L
				}
				"$whole.${frac.toString().padStart(2, '0')} GB"
			}
			safe >= MIB -> {
				var whole = safe / MIB
				val rem = safe % MIB
				var tenths = (rem * 10L + MIB / 2L) / MIB
				if (tenths >= 10L) {
					whole += 1L
					tenths -= 10L
				}
				"$whole.$tenths MB"
			}
			safe >= KIB -> {
				val kb = (safe + KIB / 2L) / KIB
				"$kb KB"
			}
			else -> "$safe B"
		}
	}

	fun resolveQualitySizeText(quality: MeloQuality, track: MeloTrack): String? {
		quality.sizeBytes?.takeIf { it > 0L }?.let { return formatBytes(it) }
		quality.sizeLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

		if (track.qualities.size <= 1) {
			track.sizeBytes?.takeIf { it > 0L }?.let { return formatBytes(it) }
			track.sizeLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
		}

		if (track.duration.isFinite() && track.duration > 0.0) {
			val kbps = quality.bitrate?.takeIf { it > 0 }
				?: if (quality.lossless) DEFAULT_LOSSLESS_KBPS else 0
			if (kbps > 0) {
				val estBytes = (track.duration * kbps * 1000.0 / 8.0).roundToLong()
				if (estBytes > 0L) {
					return "约 ${formatBytes(estBytes)}"
				}
			}
		}
		return null
	}

	fun resolveTrackSizeText(track: MeloTrack): String? {
		track.sizeBytes?.takeIf { it > 0L }?.let { return formatBytes(it) }
		track.sizeLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

		val primary = track.primaryQuality
		if (primary != null) {
			primary.sizeBytes?.takeIf { it > 0L }?.let { return formatBytes(it) }
			primary.sizeLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
		}

		if (track.duration.isFinite() && track.duration > 0.0) {
			val kbps = if (primary != null) {
				primary.bitrate?.takeIf { it > 0 }
					?: if (primary.lossless) DEFAULT_LOSSLESS_KBPS else (track.bestBitrate?.takeIf { it > 0 } ?: 0)
			} else {
				track.bestBitrate?.takeIf { it > 0 }
					?: if (track.hasLossless) DEFAULT_LOSSLESS_KBPS else 0
			}
			if (kbps > 0) {
				val estBytes = (track.duration * kbps * 1000.0 / 8.0).roundToLong()
				if (estBytes > 0L) {
					return "约 ${formatBytes(estBytes)}"
				}
			}
		}
		return null
	}

	fun formatQuality(quality: MeloQuality, track: MeloTrack): MeloQualityPresentation {
		val codecUpper = extractCodecUpper(quality)
		val bitrate = quality.bitrate ?: 0
		val label = quality.label.trim()

		val tier = when {
			MASTER_LABEL_REGEX.containsMatchIn(label) -> "超清母带"
			HIRES_LABEL_REGEX.containsMatchIn(label) || bitrate >= 1412 -> "高解析无损"
			quality.lossless || codecUpper in LOSSLESS_CODECS -> "无损音质"
			bitrate >= 300 -> "极高音质"
			bitrate >= 192 -> "较高音质"
			bitrate > 0 -> "标准音质"
			else -> cleanReadableLabel(label, codecUpper) ?: "标准音质"
		}

		val title = if (codecUpper != null) "$tier · $codecUpper" else tier
		val bitrateText = quality.bitrate?.takeIf { it > 0 }?.let { "$it kbps" }
		val sizeText = resolveQualitySizeText(quality, track)
		val subtitle = listOfNotNull(bitrateText, sizeText).joinToString(" · ")

		val isLossless = quality.lossless ||
			codecUpper in LOSSLESS_CODECS ||
			tier == "高解析无损" ||
			(tier == "超清母带" && codecUpper !in LOSSY_CODECS)

		val isPreviewOnly = quality.availability.equals("preview_only", ignoreCase = true) ||
			quality.availability.equals("preview", ignoreCase = true) ||
			label.contains("试听")
		val needsLogin = quality.availability.equals("login_required", ignoreCase = true)

		return MeloQualityPresentation(
			title = title,
			bitrateText = bitrateText,
			sizeText = sizeText,
			subtitle = subtitle,
			isLossless = isLossless,
			isPreviewOnly = isPreviewOnly,
			needsLogin = needsLogin,
		)
	}

	private fun extractCodecUpper(quality: MeloQuality): String? {
		val rawCodec = quality.codec.trim()
		if (rawCodec.isNotEmpty()) {
			findKnownCodec(rawCodec)?.let { return it }
			val lowerCodec = rawCodec.lowercase()
			if (lowerCodec !in INTERNAL_EXACT_WORDS && CLEAN_CODEC_TOKEN_REGEX.matches(rawCodec)) {
				return rawCodec.uppercase()
			}
		}

		val rawLabel = quality.label.trim()
		if (rawLabel.isNotEmpty()) {
			findKnownCodec(rawLabel)?.let { return it }
		}
		return null
	}

	private fun findKnownCodec(text: String): String? {
		for (match in ALNUM_TOKEN_REGEX.findAll(text)) {
			val upper = match.value.uppercase()
			if (upper in KNOWN_CODECS) {
				return upper
			}
			val trimmedDigits = upper.trimEnd { it.isDigit() }
			if (trimmedDigits.length < upper.length && trimmedDigits in KNOWN_CODECS) {
				return trimmedDigits
			}
		}
		return null
	}

	private fun cleanReadableLabel(raw: String, codecUpper: String?): String? {
		if (raw.isBlank()) return null
		var cleaned = WORD_WITH_UNDERSCORE_REGEX.replace(raw) { match ->
			stripInternalTokensFromWord(match.value)
		}
		cleaned = EMPTY_BRACKETS_REGEX.replace(cleaned, " ")
		cleaned = REPEATED_SEPARATORS_REGEX.replace(cleaned, " · ")
		cleaned = REPEATED_DASHES_REGEX.replace(cleaned, " - ")
		cleaned = MULTI_WHITESPACE_REGEX.replace(cleaned, " ")
		cleaned = EDGE_SEPARATORS_REGEX.replace(cleaned, "").trim()
		if (cleaned.isEmpty()) return null
		if (cleaned.lowercase() in INTERNAL_EXACT_WORDS) return null
		if (codecUpper != null && cleaned.equals(codecUpper, ignoreCase = true)) return null
		return cleaned
	}

	private fun stripInternalTokensFromWord(word: String): String {
		val lower = word.lowercase()
		if (lower in INTERNAL_EXACT_WORDS) return " "
		if (!word.contains('_')) return word

		val parts = word.split('_')
		val kept = mutableListOf<String>()
		var idx = 0
		while (idx < parts.size) {
			val partLower = parts[idx].lowercase()
			if (partLower.isEmpty()) {
				idx++
				continue
			}
			val matchedCompound = INTERNAL_COMPOUND_PARTS.firstOrNull { pattern ->
				idx + pattern.size <= parts.size &&
					pattern.indices.all { offset -> parts[idx + offset].lowercase() == pattern[offset] }
			}
			if (matchedCompound != null) {
				idx += matchedCompound.size
				continue
			}
			if (partLower in INTERNAL_EXACT_WORDS) {
				idx++
				continue
			}
			kept.add(parts[idx])
			idx++
		}
		return if (kept.isEmpty()) " " else kept.joinToString("_")
	}
}
