# Changelog

All notable changes to `SecondFactor.kt` are recorded here. The project
follows [Semantic Versioning](https://semver.org/).

## unreleased — 0.1.0

The first release: the client half of hosted verification for Android, as a
single file to copy into an app.

### Added

- `SecondFactor.openVerification(context, url)` opens the hosted page in a
  Custom Tab, after checking that `url` is one; a `javascript:`, `intent:` or
  any other URL throws `IllegalArgumentException` and is not opened.
- `SecondFactor.takeVerificationReturn(intent)` reads `sf_session_id` and
  `sf_return_token` from the App Link that opened the app, and removes the URL
  from the intent.
- `SecondFactor.parseVerificationReturn(url)` reads them from any URL.
- `VerificationReturn.toString()` leaves out the return token.
- A demo app and server under `demo/`.
