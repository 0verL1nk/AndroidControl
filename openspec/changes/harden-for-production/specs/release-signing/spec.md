## ADDED Requirements

### Requirement: Release builds are signed with the project key
The build system SHALL sign release APKs with a keystore supplied through
`.secrets/keystore.properties` or `ANDROID_KEYSTORE_*` environment variables, and SHALL fail
the build when no signing material is available.

#### Scenario: Local release build with keystore
- **WHEN** `keystore.properties` exists with a valid store file, alias, and passwords
- **THEN** `:app:assembleRelease` produces an APK whose signature verifies with `apksigner`

#### Scenario: Missing signing material
- **WHEN** neither keystore properties nor environment variables are present
- **THEN** the release build fails with an error naming the missing configuration

### Requirement: Releases are published from tags
CI SHALL build and publish a signed release APK when a tag matching `v*` is pushed.

#### Scenario: Tag push
- **WHEN** a tag matching `v*` is pushed
- **THEN** the workflow builds and signs the release APK and attaches it to a GitHub Release
