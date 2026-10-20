This is a deliberately public **development-only** Android signing key.
Alias: `androiddebugkey`; store/key passwords: `android` (the standard debug password).
It provides a consistent identity for future 0.3+ CI debug APK upgrades, so
installing the next debug build does not rotate the certificate and force data loss.
It is not a production secret or a secure release identity. Never sign a stable
release with it. A stable release needs a separate privately managed signing key.
The random key used by earlier 0.2 Actions builds cannot be recovered from an APK;
updates from those builds may have a certificate mismatch. Do not uninstall an
existing installation with valuable saves without first preserving its app data.
