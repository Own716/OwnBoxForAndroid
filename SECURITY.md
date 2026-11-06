# Security Policy

## Release Signing & Keystore Notice

### Historical Keystore Deprecation
Notice: Any `release.keystore` files or credentials present in historical Git commits prior to September 2026 are explicitly marked as **compromised and untrusted**. They must NOT be used for any production deployments or trust decisions.

### Current Signing Architecture
All production and preview releases of OwnBox for Android use secure out-of-band signing materials managed exclusively via GitHub Actions encrypted repository secrets:
- `KEYSTORE_BASE64`: Base64-encoded release signing keystore.
- `KEYSTORE_PASS`: Keystore password.
- `ALIAS_NAME`: Signing key alias.
- `ALIAS_PASS`: Key password.

No keystores, passwords, or tokens are committed or stored within this repository. Release builds automatically fail if formal signing materials are not provided.
