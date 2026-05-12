# Publishing to Maven Central

This artifact is published to **Maven Central** through the new [Sonatype
Central Portal](https://central.sonatype.com). The release pipeline lives in
[`.github/workflows/release.yml`](.github/workflows/release.yml) and runs
automatically when a `v*.*.*` tag is pushed.

This document covers the **one-time setup** you must do manually (Sonatype
account, namespace, GPG key, GitHub secrets) and the **per-release procedure**.

---

## 1. One-time setup (do this once)

### 1.1 Create a GitHub repository

The chosen `groupId` is `io.github.dev-abdulhay` — that's the reverse of the
GitHub URL `github.com/dev-abdulhay`. Central verifies ownership of this
namespace by checking that the matching GitHub account exists and that you can
prove control of it.

1. Create a public repo: `github.com/dev-abdulhay/telegram-auth-spring-boot-starter`.
2. Push this codebase to it.

### 1.2 Sign up for the Sonatype Central Portal

1. Go to <https://central.sonatype.com> → **Sign in** → use the same Google /
   GitHub identity you want associated with the namespace.
2. **Profile → View Account** → generate a **User Token**:
   - Click **Generate User Token**.
   - You get two values: a **username** (token name) and a **password**
     (token value). These are NOT your login credentials — they are the
     long-lived deploy token.
   - Save both. They go into GitHub secrets below as `SONATYPE_USERNAME`
     and `SONATYPE_PASSWORD`.

### 1.3 Verify the `io.github.dev-abdulhay` namespace

1. Central → **Namespaces** → **Add Namespace**.
2. Enter `io.github.dev-abdulhay`.
3. Central instructs you to **create a public GitHub repository whose name
   matches a verification code** (something like `OSSRH-12345`).
4. Create that repo on `github.com/dev-abdulhay` and click **Verify**.
5. Once verified you can delete the verification repo. The namespace stays
   verified.

### 1.4 Generate a GPG signing key

All Central artifacts must be GPG-signed.

```bash
# Generate a key (use a strong passphrase)
gpg --full-generate-key
# choose: RSA and RSA, 4096 bits, no expiry (or set one), real name, your email

# List the key
gpg --list-secret-keys --keyid-format LONG
# note the 16-char key id after "sec   rsa4096/<KEYID>"

# Publish to a keyserver (Central checks at least one of these)
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
gpg --keyserver keys.openpgp.org     --send-keys <KEYID>

# Export the *armored* private key (we feed this to GitHub Actions in memory —
# never commit it)
gpg --armor --export-secret-keys <KEYID> > signing-key.asc
```

You'll need three values in CI:
- `SIGNING_KEY_ID` — the 16-char hex key id.
- `SIGNING_KEY` — the **entire contents** of `signing-key.asc` (multi-line,
  starts with `-----BEGIN PGP PRIVATE KEY BLOCK-----`).
- `SIGNING_PASSWORD` — the passphrase you set above.

> After saving the key in GitHub secrets, **delete `signing-key.asc` locally**.

### 1.5 Add GitHub repository secrets

In your GitHub repo: **Settings → Secrets and variables → Actions → New
repository secret**, add five secrets:

| Name | Value |
|------|-------|
| `SONATYPE_USERNAME` | User Token *name* from step 1.2 |
| `SONATYPE_PASSWORD` | User Token *value* from step 1.2 |
| `SIGNING_KEY_ID`    | 16-char key id from step 1.4 |
| `SIGNING_KEY`       | Full armored private key (paste multi-line content) |
| `SIGNING_PASSWORD`  | GPG passphrase |

The release workflow already reads exactly these names — no further config.

---

## 2. Per-release procedure

Once 1.1–1.5 are done, every release is just a tag push.

### 2.1 Bump the version

Edit [`gradle.properties`](gradle.properties):

```properties
version=0.2.0
```

Commit on `main`:

```bash
git add gradle.properties
git commit -m "release: 0.2.0"
git push origin main
```

### 2.2 Tag and push

```bash
git tag v0.2.0
git push origin v0.2.0
```

That triggers `.github/workflows/release.yml`, which:

1. Runs the build (compile + sources jar + javadoc jar).
2. Signs every artifact with the GPG key.
3. Uploads to the Sonatype Central Portal **and** triggers
   `publishAndReleaseToMavenCentral`, which auto-releases the staging deployment
   if Central's validation passes (no manual "close & release" step).

After a few minutes the artifact appears at
<https://central.sonatype.com/artifact/io.github.dev-abdulhay/telegram-auth-spring-boot-starter>
and propagates to Maven Central proper within ~30 min.

### 2.3 Watch the workflow

GitHub repo → **Actions** → **Release to Maven Central** → latest run.
If `publishAndReleaseToMavenCentral` fails, the most common causes are:

| Symptom | Likely cause |
|---------|--------------|
| `401 Unauthorized` | `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` are wrong. Re-generate the User Token. |
| `Failed to read PGP secret key` | `SIGNING_KEY` is malformed (missing newlines, wrong armor). Re-export and paste the *entire* file content. |
| `gpg: public key not found` | You forgot to push the public key to a keyserver, or it hasn't propagated yet (wait 5–10 min). |
| `Invalid POM` from Central | Some required field missing — check the generated POM in `build/publications/maven/pom-default.xml`. |

---

## 3. Local dry-run (optional)

You can verify the artifacts locally without uploading:

```bash
# Generate everything but don't upload
./gradlew clean publishToMavenLocal

# Inspect the staged artifacts
ls ~/.m2/repository/io/github/dev-abdulhay/telegram-auth-spring-boot-starter/0.1.0/
```

For a signed local build that mirrors what CI uploads, you need GPG configured
locally and these `gradle.properties` overrides in `~/.gradle/gradle.properties`
(NOT in the repo):

```properties
signing.keyId=<last 8 chars of KEYID>
signing.password=<your passphrase>
signing.secretKeyRingFile=/Users/abdulhay/.gnupg/secring.gpg
```

(`secring.gpg` doesn't exist by default on modern GPG — generate it once with
`gpg --export-secret-keys -o ~/.gnupg/secring.gpg`.)

Then:

```bash
./gradlew clean publishAllPublicationsToMavenCentralRepository
```

This uploads a staging deployment but stops short of releasing — useful for
verifying signatures.

---

## 4. Version policy

| Bump | When |
|------|------|
| `0.x.Y` (patch) | bug fixes, docs |
| `0.X.0` (minor) | new transports, new config knobs (still pre-1.0, may break) |
| `1.0.0` | first production rollout — at this point we commit to semver |

Any breaking change at 0.x bumps the minor and is called out in
[`CHANGELOG.md`](CHANGELOG.md) (TODO once releases start).

---

## 5. Useful links

- Sonatype Central Portal docs: <https://central.sonatype.org/publish/publish-portal-gradle/>
- vanniktech/gradle-maven-publish-plugin (used by `build.gradle.kts`):
  <https://vanniktech.github.io/gradle-maven-publish-plugin/central/>
- Central Portal artifact page (once published):
  <https://central.sonatype.com/artifact/io.github.dev-abdulhay/telegram-auth-spring-boot-starter>
