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

The Java expression and validation engine must pass the conformance cases published in
[`@formkiln/spec`](https://github.com/liuyfly/formkiln/tree/main/packages/spec/conformance).
Semantic changes start in that repository.
