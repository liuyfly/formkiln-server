# Contributing to Formkiln Server

Thank you for your interest in contributing.

## Ground rules

- Discuss larger changes in an issue before opening a pull request.
- Keep pull requests focused. One logical change per PR.
- All code, comments, commit messages and documentation are written in English.

## Developer Certificate of Origin (DCO)

All commits must be signed off to certify that you wrote the code or have the right to submit it under the project's license ([developercertificate.org](https://developercertificate.org/)):

```bash
git commit -s -m "feat(runtime): add draft endpoint"
```

## Commit messages

We follow [Conventional Commits](https://www.conventionalcommits.org/): `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`.

## Workflow

1. Fork the repository and create a branch from `main`.
2. Make your change and add tests.
3. Run `./gradlew build` (Docker must be running for integration tests).
4. Open a pull request. CI must pass before review.

## Database migrations

- Add a new Flyway migration (`src/main/resources/db/migration/V<n>__<description>.sql`) for every schema change.
- Never edit a migration that has been released.

## Conformance suite

The Java expression and validation engine must pass the conformance cases of the form
specification in [`liuyfly/formkiln`](https://github.com/liuyfly/formkiln/tree/main/packages/spec).
Semantic changes start in that repository.

### Following the specification

The build takes `packages/spec` (JSON Schema, conformance cases, examples) from a pinned commit of
liuyfly/formkiln and verifies a SHA-256 digest of the extracted files (`formkilnSpecCommit`,
`formkilnSpecSha256` in `gradle.properties`). No npm release is needed: once a spec change is on
formkiln's `main`, pin its commit here.

```bash
git -C ../formkiln fetch && python3 scripts/spec-digest.py ../formkiln origin/main
```

Copy both printed properties into `gradle.properties`, then run `./gradlew build`. The digest is
computed from your own clone, so a tampered download fails the build.

To test against a spec that is not on `main` yet (for example while changing the conformance cases), point the build at a local
checkout of the formkiln repository:

```bash
./gradlew test -PformkilnSpecDir=../formkiln/packages/spec
```
