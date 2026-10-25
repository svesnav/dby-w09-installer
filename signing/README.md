# Signing key

`shared.keystore` (alias `matepad`, passwords `android`) signs every build so
that each new APK installs over the previous one. It is deliberately public,
like Android's debug key: it proves nothing about who built an APK. A
published release should be signed with a private key kept in repository
secrets instead.
