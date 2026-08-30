# Continuous integration

The private fork uses a non-signing GitHub Actions workflow to validate the Android frontend and the private melonDS core submodule.

## Required repository secret

The Android repository cannot read the separate private core repository with its default `GITHUB_TOKEN`. Configure a fine-grained personal access token and store it as the repository secret:

```text
CORE_REPO_TOKEN
```

Recommended token scope:

- Owner: `Proginji2000`
- Repository access: only
  - `melonDS-android-fastforward-audio`
  - `melonDS-core-fastforward-audio`
- Repository permission: **Contents: Read-only**
- No Actions, Administration, Issues, Pull requests or other write permission is required.
- Use an expiration date and rotate the token when appropriate.

Add it in the Android repository under:

```text
Settings
→ Secrets and variables
→ Actions
→ New repository secret
```

Name: `CORE_REPO_TOKEN`

The secret value must never be committed to the repository, printed in logs or placed in `local.properties`.

## CI environment

The workflow matches the project configuration:

- Java 21
- Android compile/target SDK 36
- NDK `28.0.13004108`
- CMake `3.22.1`
- ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64`

## Validation steps

The workflow:

1. checks that `CORE_REPO_TOKEN` exists;
2. checks out the Android repository and recursive submodules;
3. verifies that `melonDS-android-lib` exactly matches the gitlink recorded by the parent commit;
4. runs `:app:testGitHubProdDebugUnitTest --rerun-tasks`;
5. builds `:app:assembleGitHubProdDebug`;
6. builds `:app:buildCMakeRelWithDebInfo`;
7. uploads the unsigned/debug APK as a private workflow artifact for 14 days.

No keystore is used. The workflow does not create GitHub releases, modify tags, publish to the Play Store or upload anything outside GitHub Actions artifacts.

## Activation policy

The workflow is initially `workflow_dispatch` only. This prevents a failing automatic run before the private-core credential is configured.

After one successful manual run, enable automatic validation for pushes and pull requests targeting `thor-fastforward-audio`.

## Security notes

Do not broaden the token beyond the repositories and read permissions required by the private submodule checkout. If the fork becomes public later, revisit pull-request secret handling before allowing CI from untrusted forks.
