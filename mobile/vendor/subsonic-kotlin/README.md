# subsonic-kotlin

[![Maven Central](https://img.shields.io/maven-central/v/dev.zt64.subsonic/subsonic-client)](https://central.sonatype.com/artifact/dev.zt64.subsonic/subsonic-client)
[![License](https://img.shields.io/github/license/zt64/subsonic-kotlin)](LICENSE)

![badge-platform-jvm]
![badge-platform-android]
![badge-platform-linux]
![badge-platform-windows]
![badge-platform-macos]
![badge-platform-ios]
![badge-platform-tvos]
![badge-platform-watchos]
![badge-platform-js]
![badge-platform-wasm]

A Kotlin Multiplatform client library for the Subsonic API.

## Features

- Full [OpenSubsonic](https://opensubsonic.netlify.app/) API coverage (based on API version 1.16.1)
- Kotlin Multiplatform support (JVM, Android, Linux, Windows, macOS, iOS, tvOS, watchOS, JS, Wasm)
- Token-based and API key authentication
- Built on [Ktor](https://ktor.io/) with [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)

## Setup

Add the following to your `gradle/libs.versions.toml`:

```toml
[versions]
subsonicKotlin = "x.y.z"  # Replace with latest version

[libraries]
subsonicClient = { module = "dev.zt64.subsonic:subsonic-client", version.ref = "subsonicKotlin" }
```

Then add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.subsonicClient)
}
```

> [!NOTE]
> A Ktor engine implementation must be included in your dependencies for the client to function.

## Quick Start

### Creating a Client

```kotlin
// Using a username and password
val client = SubsonicClient(
    baseUrl = "https://your-server.com",
    auth = SubsonicAuth.Token(
        username = "your-username",
        password = "your-password"
    )
)

// Using an API key (not universally supported, check your servers compatible authentication)
val client = SubsonicClient(
    baseUrl = "https://your-server.com",
    auth = SubsonicAuth.Key(apiKey = "your-api-key")
)
```

### Basic Usage

```kotlin
// Test connectivity
client.ping()

// Browse library
val artists = client.getArtists()
val artist = client.getArtist(id = "123")
val album = client.getAlbum(id = "456")

// Search
val results = client.searchID3(query = "Beatles")

// Playlists
val playlists = client.getPlaylists()
val newPlaylist = client.createPlaylist(name = "My Mix", songIds = listOf("1", "2", "3"))

// Streaming
val streamUrl = client.getStreamUrl(id = "song-123", maxBitRate = 320)
val coverArtUrl = client.getCoverArtUrl(id = album.coverArt, size = "500")

// Star/Rate
client.star("song-123")
client.setRating(id = "song-123", rating = 5)

// Scrobble
client.scrobble(id = "song-123")
```

## Error Handling

```kotlin
try {
    val album = client.getAlbum(id = "invalid-id")
} catch (e: SubsonicException) {
    println("${e.code}: ${e.message}")
}
```

## Advanced Configuration

```kotlin
// Use a specific Ktor engine
val client = SubsonicClient(
    baseUrl = "https://your-server.com",
    auth = SubsonicAuth.Token(username = "user", password = "pass")
) {
    // Additional Ktor client configuration
    install(HttpTimeout) {
        requestTimeoutMillis = 30000
    }
}
```

## API Compatibility

This library targets Subsonic API version **1.16.1** and supports the Open Subsonic specification. It has been tested with:

- [Navidrome](https://www.navidrome.org/)

Most endpoints should work with any Subsonic-compatible server, though some features may vary by implementation.

## Contributing

Contributions are welcome and encouraged! Please feel free to submit issues or pull requests.

## License

This project is licensed under the [MIT License](LICENSE).

[badge-platform-jvm]: https://img.shields.io/badge/-jvm-DB413D.svg?style=flat
[badge-platform-android]: https://img.shields.io/badge/-android-3DDC84.svg?style=flat
[badge-platform-linux]: https://img.shields.io/badge/-linux-2D3F6C.svg?style=flat
[badge-platform-windows]: https://img.shields.io/badge/-windows-4D76CD.svg?style=flat
[badge-platform-macos]: https://img.shields.io/badge/-macos-111111.svg?style=flat
[badge-platform-ios]: https://img.shields.io/badge/-ios-CDCDCD.svg?style=flat
[badge-platform-tvos]: https://img.shields.io/badge/-tvos-808080.svg?style=flat
[badge-platform-watchos]: https://img.shields.io/badge/-watchos-C0C0C0.svg?style=flat
[badge-platform-js]: https://img.shields.io/badge/-js-F8DB5D.svg?style=flat
[badge-platform-wasm]: https://img.shields.io/badge/-wasm-624FE8.svg?style=flat