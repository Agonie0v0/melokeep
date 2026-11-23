package io.github.agonie0v0.melokeep.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MeloGitHubAsset(
	val name: String = "",
	@SerialName("browser_download_url")
	val downloadUrl: String = "",
	val size: Long = 0L,
	@SerialName("content_type")
	val contentType: String = "",
)

@Serializable
data class MeloGitHubRelease(
	@SerialName("tag_name")
	val tagName: String = "",
	val name: String = "",
	@SerialName("html_url")
	val htmlUrl: String = "",
	val body: String = "",
	@SerialName("published_at")
	val publishedAt: String = "",
	val assets: List<MeloGitHubAsset> = emptyList(),
	val draft: Boolean = false,
	val prerelease: Boolean = false,
)

fun MeloGitHubRelease.findAndroidApkAsset(): MeloGitHubAsset? {
	val apkAssets = assets.filter {
		it.downloadUrl.isNotBlank() && it.name.trim().endsWith(".apk", ignoreCase = true)
	}
	if (apkAssets.isEmpty()) return null
	return apkAssets.firstOrNull {
		val lower = it.name.lowercase()
		("android" in lower || "release" in lower) && "debug" !in lower
	} ?: apkAssets.firstOrNull {
		"debug" !in it.name.lowercase()
	} ?: apkAssets.firstOrNull {
		"android" in it.name.lowercase()
	} ?: apkAssets.first()
}

fun MeloGitHubRelease.findIosIpaAsset(): MeloGitHubAsset? {
	val ipaAssets = assets.filter {
		it.downloadUrl.isNotBlank() && it.name.trim().endsWith(".ipa", ignoreCase = true)
	}
	if (ipaAssets.isEmpty()) return null
	return ipaAssets.firstOrNull {
		"debug" !in it.name.lowercase()
	} ?: ipaAssets.first()
}

fun MeloGitHubRelease.hasAndroidUpdateFor(currentVersion: String): Boolean =
	!draft && !prerelease && findAndroidApkAsset() != null && MeloSemVer.isNewer(tagName, currentVersion)

fun MeloGitHubRelease.hasIosUpdateFor(currentVersion: String): Boolean =
	!draft && !prerelease && findIosIpaAsset() != null && MeloSemVer.isNewer(tagName, currentVersion)

object MeloSemVer {
	private data class ParsedVersion(
		val core: List<Int>,
		val preRelease: List<String>,
	)

	fun compare(remoteTag: String, currentVersion: String): Int {
		val remote = parse(remoteTag) ?: return 0
		val local = parse(currentVersion) ?: return 0
		val maxCoreLen = maxOf(remote.core.size, local.core.size, 3)
		for (i in 0 until maxCoreLen) {
			val r = remote.core.getOrElse(i) { 0 }
			val l = local.core.getOrElse(i) { 0 }
			if (r != l) return r.compareTo(l)
		}
		// Same major.minor.patch: official release > pre-release per SemVer 2.0.0
		if (remote.preRelease.isEmpty() && local.preRelease.isEmpty()) return 0
		if (remote.preRelease.isEmpty()) return 1
		if (local.preRelease.isEmpty()) return -1

		val maxPreLen = maxOf(remote.preRelease.size, local.preRelease.size)
		for (i in 0 until maxPreLen) {
			val rPre = remote.preRelease.getOrNull(i) ?: return -1
			val lPre = local.preRelease.getOrNull(i) ?: return 1
			val cmp = comparePreReleaseIdentifier(rPre, lPre)
			if (cmp != 0) return cmp
		}
		return 0
	}

	fun isNewer(remoteTag: String, currentVersion: String): Boolean =
		compare(remoteTag, currentVersion) > 0

	private fun parse(raw: String): ParsedVersion? {
		val cleaned = raw.trim()
			.removePrefix("refs/tags/")
			.removePrefix("release-")
			.removePrefix("v")
			.removePrefix("V")
			.substringBefore('+')
			.trim()
		if (cleaned.isEmpty()) return null
		val dashIdx = cleaned.indexOf('-')
		val corePart = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
		val prePart = if (dashIdx >= 0) cleaned.substring(dashIdx + 1) else ""
		val coreSegments = corePart.split('.').map { seg ->
			seg.toIntOrNull()
				?: seg.takeWhile { it.isDigit() }.toIntOrNull()
				?: return null
		}
		if (coreSegments.isEmpty()) return null
		val preSegments = if (prePart.isBlank()) {
			emptyList()
		} else {
			prePart.split('.').map { it.trim() }.filter { it.isNotEmpty() }
		}
		return ParsedVersion(core = coreSegments, preRelease = preSegments)
	}

	private fun tokenizeAlphaNum(value: String): List<String> {
		val result = mutableListOf<String>()
		var idx = 0
		while (idx < value.length) {
			val isDigit = value[idx].isDigit()
			val start = idx
			while (idx < value.length && value[idx].isDigit() == isDigit) {
				idx++
			}
			result.add(value.substring(start, idx))
		}
		return result
	}

	private fun comparePreReleaseIdentifier(a: String, b: String): Int {
		val aInt = a.toIntOrNull()
		val bInt = b.toIntOrNull()
		if (aInt != null && bInt != null) return aInt.compareTo(bInt)
		if (aInt != null) return -1 // numeric < non-numeric per SemVer 2.0.0
		if (bInt != null) return 1

		val tokensA = tokenizeAlphaNum(a)
		val tokensB = tokenizeAlphaNum(b)
		val count = maxOf(tokensA.size, tokensB.size)
		for (i in 0 until count) {
			val tA = tokensA.getOrNull(i) ?: return -1
			val tB = tokensB.getOrNull(i) ?: return 1
			val nA = tA.toIntOrNull()
			val nB = tB.toIntOrNull()
			val cmp = if (nA != null && nB != null) {
				nA.compareTo(nB)
			} else {
				tA.compareTo(tB, ignoreCase = true)
			}
			if (cmp != 0) return cmp
		}
		return 0
	}
}

object MeloAppUpdateChecker {
	const val DEFAULT_REPO = "Agonie0v0/melokeep"

	private fun normalizeRepo(repo: String): String = repo.trim().trim('/').ifBlank { DEFAULT_REPO }

	private suspend fun <T> fetchGitHubJson(
		httpClient: HttpClient,
		url: String,
		decode: (String) -> T,
	): T {
		val response = try {
			httpClient.get(url) {
				header(HttpHeaders.Accept, "application/vnd.github+json")
				header(HttpHeaders.UserAgent, "MeloKeep-Mobile")
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: MeloApiException) {
			throw e
		} catch (e: Throwable) {
			throw MeloApiException(0, "检查更新失败：${e.message ?: "无法连接 GitHub 服务"}")
		}
		val text = try {
			response.bodyAsText()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			throw MeloApiException(response.status.value, "读取 GitHub 版本响应失败：${e.message ?: "网络异常"}")
		}
		if (!response.status.isSuccess()) {
			val detail = MeloKeepApiClient.parseApiErrorMessage(text, response.status.value)
			throw MeloApiException(
				statusCode = response.status.value,
				message = "获取 GitHub 最新版本失败 (${response.status.value})：$detail",
			)
		}
		return try {
			decode(text)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			throw MeloApiException(
				statusCode = response.status.value,
				message = "解析 GitHub 版本信息失败：${e.message ?: "格式错误"}",
			)
		}
	}

	private suspend fun <T> withTransientHttpClient(block: suspend (HttpClient) -> T): T {
		val client = HttpClient(createPlatformHttpClientEngine()) {
			expectSuccess = false
		}
		return try {
			block(client)
		} finally {
			client.close()
		}
	}

	suspend fun fetchLatestRelease(
		httpClient: HttpClient,
		repo: String = DEFAULT_REPO,
	): MeloGitHubRelease {
		val normalizedRepo = normalizeRepo(repo)
		val url = "https://api.github.com/repos/$normalizedRepo/releases/latest"
		return fetchGitHubJson(httpClient, url) { text ->
			meloJson.decodeFromString<MeloGitHubRelease>(text)
		}
	}

	suspend fun fetchLatestRelease(
		repo: String = DEFAULT_REPO,
	): MeloGitHubRelease = withTransientHttpClient { client ->
		fetchLatestRelease(client, repo)
	}

	suspend fun fetchLatestReleaseForAsset(
		httpClient: HttpClient,
		repo: String = DEFAULT_REPO,
		assetMatcher: (MeloGitHubRelease) -> MeloGitHubAsset?,
	): MeloGitHubRelease {
		val normalizedRepo = normalizeRepo(repo)
		val url = "https://api.github.com/repos/$normalizedRepo/releases?per_page=20"
		val releases = fetchGitHubJson(httpClient, url) { text ->
			meloJson.decodeFromString<List<MeloGitHubRelease>>(text)
		}
		val stableReleases = releases.filter { !it.draft && !it.prerelease }
		return stableReleases
			.filter { assetMatcher(it) != null }
			.maxWithOrNull { a, b -> MeloSemVer.compare(a.tagName, b.tagName) }
			?: stableReleases.maxWithOrNull { a, b -> MeloSemVer.compare(a.tagName, b.tagName) }
			?: fetchLatestRelease(httpClient, repo)
	}

	suspend fun fetchLatestReleaseWithAssetOrNull(
		httpClient: HttpClient,
		repo: String = DEFAULT_REPO,
		maxPages: Int = 3,
		perPage: Int = 100,
		assetMatcher: (MeloGitHubRelease) -> MeloGitHubAsset?,
	): MeloGitHubRelease? {
		val normalizedRepo = normalizeRepo(repo)
		val safeMaxPages = maxPages.coerceAtLeast(1)
		val safePerPage = perPage.coerceIn(1, 100)
		for (page in 1..safeMaxPages) {
			val url = "https://api.github.com/repos/$normalizedRepo/releases?per_page=$safePerPage&page=$page"
			val releases = fetchGitHubJson(httpClient, url) { text ->
				meloJson.decodeFromString<List<MeloGitHubRelease>>(text)
			}
			if (releases.isEmpty()) break
			val matched = releases
				.filter { !it.draft && !it.prerelease && assetMatcher(it) != null }
				.maxWithOrNull { a, b -> MeloSemVer.compare(a.tagName, b.tagName) }
			if (matched != null) return matched
			if (releases.size < safePerPage) break
		}
		return null
	}

	suspend fun fetchLatestAndroidReleaseOrNull(
		httpClient: HttpClient,
		repo: String = DEFAULT_REPO,
		maxPages: Int = 3,
		perPage: Int = 100,
	): MeloGitHubRelease? = fetchLatestReleaseWithAssetOrNull(
		httpClient = httpClient,
		repo = repo,
		maxPages = maxPages,
		perPage = perPage,
	) { it.findAndroidApkAsset() }

	suspend fun fetchLatestAndroidReleaseOrNull(
		repo: String = DEFAULT_REPO,
	): MeloGitHubRelease? = withTransientHttpClient { client ->
		fetchLatestAndroidReleaseOrNull(client, repo)
	}

	suspend fun fetchLatestIosReleaseOrNull(
		httpClient: HttpClient,
		repo: String = DEFAULT_REPO,
		maxPages: Int = 3,
		perPage: Int = 100,
	): MeloGitHubRelease? = fetchLatestReleaseWithAssetOrNull(
		httpClient = httpClient,
		repo = repo,
		maxPages = maxPages,
		perPage = perPage,
	) { it.findIosIpaAsset() }

	suspend fun fetchLatestIosReleaseOrNull(
		repo: String = DEFAULT_REPO,
	): MeloGitHubRelease? = withTransientHttpClient { client ->
		fetchLatestIosReleaseOrNull(client, repo)
	}

	suspend fun fetchLatestAndroidRelease(
		httpClient: HttpClient,
		repo: String = DEFAULT_REPO,
	): MeloGitHubRelease = fetchLatestReleaseForAsset(httpClient, repo) { it.findAndroidApkAsset() }

	suspend fun fetchLatestAndroidRelease(
		repo: String = DEFAULT_REPO,
	): MeloGitHubRelease = withTransientHttpClient { client ->
		fetchLatestAndroidRelease(client, repo)
	}

	suspend fun fetchLatestIosRelease(
		httpClient: HttpClient,
		repo: String = DEFAULT_REPO,
	): MeloGitHubRelease = fetchLatestReleaseForAsset(httpClient, repo) { it.findIosIpaAsset() }

	suspend fun fetchLatestIosRelease(
		repo: String = DEFAULT_REPO,
	): MeloGitHubRelease = withTransientHttpClient { client ->
		fetchLatestIosRelease(client, repo)
	}
}
