# ShipRAG: one-time permanent signing setup

ShipRAG uses a single permanent Android signing identity. Old GitHub Actions APKs were produced with ephemeral runner debug keys; Android therefore rejected every subsequent install as a signature mismatch. APK upgrades preserve the private library, SQLite index, model URLs and Android Keystore-encrypted API keys **only when package ID and signing certificate stay the same** and the new APK's `versionCode` is higher.

## One-time GitHub secret

1. Download the private [ShipRAG-signing-secret.txt] supplied in the ChatGPT conversation. Keep it private.
2. Open `https://github.com/snehithgit/docling-stage2b-rag-mobile/settings/secrets/actions`.
3. Select **New repository secret**. Name it exactly `SHIPRAG_SIGNING_PASSPHRASE`.
4. Paste the single line from the private text file as the **secret value**. Save.
5. Open **Actions → ShipRAG Android — tested, permanently signed updates → Run workflow**, and select `shiprag/android-v0.1`. Download the **ShipRAG-Android-permanently-signed-APK** artifact.
6. On the first move from an old, randomly debug-signed version, an uninstall/reinstall may be required. **Save your original ZIP outside ShipRAG first.** The existing app-private data is lost on uninstall. After this one-time migration, future APKs from this workflow can install **over** ShipRAG without reimporting ZIPs or entering settings.

**Do not put this secret, the decrypted JKS, or the private backup keystore into GitHub commits, issues, Actions logs, or screenshots.** The repository includes only an OpenSSL AES-256-CBC/PBKDF2-encrypted keystore, and the GitHub Actions secret is never printed. The CI workflow validates the fixed certificate fingerprint before signing *and* verifies the finished APK's signer.

Expected certificate SHA-256:
`BD:4E:DE:B4:88:A6:04:3F:FF:43:12:DF:71:EC:76:C9:B6:81:D6:F8:A5:E6:1A:C5:0B:97:4D:9E:EA:BE:6F:10`.

Never regenerate the signing key for this Android package. If you change it, Android will again require an uninstall. Keep the secret file and original `ShipRAG-permanent-signing.jks` backed up securely, separately from your public GitHub repo.

### Workflows without a configured secret

Push and pull-request builds still compile and run tests, but **no APK artifact is published** until the permanent signing secret has been configured. This prevents accidental installation of an APK with a new ephemeral debug signing identity.

### APK versioning

Each GitHub Actions workflow run automatically sets a higher `versionCode`, so newer builds can be installed as Android upgrades without losing the saved corpus or model settings. Installing an older APK over a newer one is blocked by Android's downgrade protection.
