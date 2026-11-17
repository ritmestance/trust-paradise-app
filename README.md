# Trust Paradise — Android app

The Android window around https://app.trustparadise.ir (the shop's management app).
It holds no data, no business code and no keys: everything lives on the server. This repository is
public only so GitHub builds the app for free. The signing key is kept in this repository's
Actions secrets (`TP_KEYSTORE_B64`, `TP_KEYSTORE_PASSWORD`), which nobody can read.

Build: Actions → Android app → Run workflow. The APK is under the run's Artifacts.
