# Security policy

## Reporting a problem

Open a private report through GitHub's **Security → Advisories → Report a vulnerability** form for
this repository. Please do not file a public issue for anything credential-related.

## What must never be committed

| Item | Why |
|---|---|
| `*.jks`, `*.keystore`, `*.p12`, `*.pfx` | private signing keys |
| `keystore.properties` | holds the key path and its password |
| `local.properties` | machine-specific SDK path |
| `*.apk`, `*.aab`, `release/` | build artefacts; publish them as Release assets instead |
| API tokens, `.env`, `*.pem` | credentials |

Enforcement:

* `.gitignore` — path patterns above.
* `.githooks/pre-commit` — refuses staged signing material, local config, build artefacts and
  credential-shaped strings. Install with
  `powershell -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1`.
* `.github/workflows/secret-guard.yml` — fails CI if any of those become tracked.
* CI builds sign from `ECHOSONAR_STORE_FILE`, `ECHOSONAR_STORE_PASSWORD`, `ECHOSONAR_KEY_ALIAS`
  and `ECHOSONAR_KEY_PASSWORD` (repository secrets), never from a committed file.

## Incident log

### 2026-11-14 — release signing key published

* **What happened.** The initial commit (`df5d0c3`) tracked `keystore/echosonar-release.jks`,
  `keystore.properties` (including its password), `local.properties` and a signed APK. The
  repository is public, so all of it was downloadable.
* **Impact.** Anyone could have produced APKs signed for `com.echosonar`, i.e. forged updates to any
  installed copy. No release had been distributed at the time.
* **Response.**
  1. The key was rotated: new 4096-bit RSA key, random 32-byte password, stored **outside** the
     working tree (`~/.keys/echosonar/release.jks`). The old key was deleted locally.
  2. History was rewritten with `git filter-repo` and force-pushed with a lease; the branch now
     contains no signing material (`472de7f`, `cb794db`).
  3. Local object store pruned (`git gc --prune=now`).
  4. Guards added (this document, `.githooks/pre-commit`, CI workflow, README section).
* **Residual risk.** GitHub may keep unreachable objects addressable by exact SHA until its
  housekeeping or a Support request purges them; forks and clones made in the meantime keep their
  own copies. The leaked key is therefore treated as permanently compromised:

  * old certificate SHA-256 `cdb32d206bb5134daf85efa4630e530c4909a9a8cb88e40ae8876176e8caa71a`
    — **do not trust**;
  * current certificate SHA-256
    `29e767921139a7c71d3b7561a10bcf4f3fbfb01075f8fa59640f093256fd06f2`.

  Any device that installed the old APK must uninstall it before installing a build signed with the
  current key (Android rejects an update with a different signing identity).
* **Follow-up for the repository owner.** Enable **Settings → Code security → Secret scanning** and
  **Push protection** on GitHub, and ask GitHub Support to purge cached views of the old commit.