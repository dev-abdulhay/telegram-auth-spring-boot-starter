# Publishing to Maven Central

This artifact is published to **Maven Central** through the [Sonatype Central
Portal](https://central.sonatype.com) using the
[`central-publishing-maven-plugin`](https://central.sonatype.org/publish/publish-portal-maven/).
The release pipeline lives in
[`.github/workflows/release.yml`](.github/workflows/release.yml) and runs
automatically when a `v*.*.*` tag is pushed.

This document covers the **one-time setup** (Sonatype namespace, GPG key,
GitHub secrets) and the **per-release procedure**.

---

## 1. One-time setup (already done)

### 1.1 Sonatype Central Portal account
1. <https://central.sonatype.com> → **Sign in** (Google / GitHub identity).
2. **Profile → Generate User Token** — yields a token *username* and *password*.
   These are the long-lived deploy credentials.

### 1.2 Verify the `io.github.dev-abdulhay` namespace
- Central Portal → **Namespaces** → **Add Namespace** → `io.github.dev-abdulhay`.
- Signing in with the GitHub identity `dev-abdulhay` auto-verifies the
  `io.github.dev-abdulhay` namespace — no `OSSRH-XXXXX` verification repo
  needed.

### 1.3 GPG signing key
All Central artifacts must be GPG-signed.

```bash
gpg --full-generate-key   # RSA, 4096, no expiry, strong passphrase
gpg --list-secret-keys --keyid-format LONG   # note the 16-char KEYID
gpg --keyserver keys.openpgp.org     --send-keys <KEYID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
gpg --armor --export-secret-keys <KEYID> > signing-key.asc
```
Save three values: `KEYID`, the entire `signing-key.asc`, and the passphrase.
After uploading them to GitHub Actions secrets (next step), **delete the
local `signing-key.asc`**.

### 1.4 GitHub repository secrets
`Settings → Secrets and variables → Actions` — five secrets:

| Name | Value |
|------|-------|
| `SONATYPE_USERNAME` | User Token *username* from §1.1 |
| `SONATYPE_PASSWORD` | User Token *password* from §1.1 |
| `SIGNING_KEY_ID`    | 16-char GPG key id from §1.3 |
| `SIGNING_KEY`       | Full armored private key (paste multi-line) |
| `SIGNING_PASSWORD`  | GPG passphrase |

The release workflow reads exactly these names.

---

## 2. Per-release procedure

### 2.1 Bump the version in `pom.xml`

```bash
mvn -B versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
git add pom.xml
git commit -m "release: 0.2.0"
git push origin main
```

### 2.2 Tag and push

```bash
git tag v0.2.0
git push origin v0.2.0
```

That triggers [`.github/workflows/release.yml`](.github/workflows/release.yml):

1. Aligns `pom.xml` version to the tag (`mvn versions:set`).
2. Runs `mvn -Prelease deploy`:
   - Builds the main, sources, and javadoc jars.
   - Signs every artifact with GPG (via `maven-gpg-plugin`).
   - Uploads to the Sonatype Central Portal via
     `central-publishing-maven-plugin` with `autoPublish=true` — Central
     auto-releases the staging deployment once validation passes (no manual
     "close & release" step).

After a few minutes the artifact appears at
<https://central.sonatype.com/artifact/io.github.dev-abdulhay/telegram-auth-spring-boot-starter>
and propagates to Maven Central proper within ~30 min.

### 2.3 Watch the workflow
GitHub repo → **Actions** → **Release to Maven Central** → latest run.
Common failures:

| Symptom | Likely cause |
|---------|--------------|
| `401 Unauthorized` from Central | `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` wrong. Re-generate the User Token. |
| `gpg: signing failed: Inappropriate ioctl for device` | Missing `--pinentry-mode loopback` (already configured in pom.xml — only happens if you edit the gpg config). |
| `gpg: no default secret key` | `SIGNING_KEY` malformed (missing newlines, wrong armor). Re-export and paste the *entire* file content. |
| `Failed to deploy artifacts to central` | Validation failed — check the workflow log; Central reports the specific missing/invalid POM field. |

---

## 3. Local dry-run (optional)

Build everything Central would receive, without uploading:

```bash
# Build + sources jar + javadoc jar, no signing, no deploy
mvn -B clean verify

# As above plus GPG signing (needs gpg + your key in local keyring)
# Prompts for passphrase interactively.
mvn -B -Prelease clean verify -Dgpg.skip=false
```

The signed artifacts land in `target/`:

```
target/
  telegram-auth-spring-boot-starter-0.2.0.jar
  telegram-auth-spring-boot-starter-0.2.0.jar.asc
  telegram-auth-spring-boot-starter-0.2.0-sources.jar
  telegram-auth-spring-boot-starter-0.2.0-sources.jar.asc
  telegram-auth-spring-boot-starter-0.2.0-javadoc.jar
  telegram-auth-spring-boot-starter-0.2.0-javadoc.jar.asc
```

---

## 4. Version policy

| Bump | When |
|------|------|
| `0.x.Y` (patch) | bug fixes, docs |
| `0.X.0` (minor) | new transports, new config knobs (still pre-1.0, may break) |
| `1.0.0` | first production rollout — at this point we commit to semver |

---

## 5. Useful links

- Central Portal — Maven publishing: <https://central.sonatype.org/publish/publish-portal-maven/>
- `central-publishing-maven-plugin` docs: <https://central.sonatype.org/publish/publish-portal-maven/#configuration>
- `maven-gpg-plugin` docs: <https://maven.apache.org/plugins/maven-gpg-plugin/>
- Central Portal artifact page (after publishing):
  <https://central.sonatype.com/artifact/io.github.dev-abdulhay/telegram-auth-spring-boot-starter>
