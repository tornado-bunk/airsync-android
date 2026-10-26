# AirBridge regression checks

Compile `Main.kt` together with the production `RelayProtocol.kt` and
`RelayUrlPolicy.kt` from `app/src/main/java/com/sameerasw/airsync/utils/` in an isolated
CI job with Kotlin/JDK installed; run `com.sameerasw.airsync.utils.MainKt`.
These checks need no Android runtime, third-party dependencies, or network access.

They exercise the same AB1 wire fixture as the Swift checks: bidirectional payloads,
Unicode and pipes in JSON, reflection, duplicate data, replayed hello, reordering,
prior-session replay, legacy rejection, and deceptive private-looking domains.
Update Mac and Android together; AB1 deliberately rejects the legacy relay format.

## Integration verification on a disposable device

- Pair through the existing QR flow and verify LAN connection. No extra pairing
  confirmation or activity/screen recreation is introduced by the relay changes.
- Send LAN-only commands via relay with a dummy authenticated peer, including while
  LAN is also active. Expect rejection; ordinary clipboard/media sync should work.

These isolated checks complement full app builds and device integration tests.
They do not replace end-to-end replay and transport-isolation testing with real
encryption and a disposable peer.
