# Contributing Guidelines

- Follow the existing code style.
Run `./gradlew ktlintFormat` before opening a PR. Pull requests that do not follow style guidelines may be requested for changes.

- Document code changes within reason.
Public APIs (functions, classes, properties) should include clear KDoc describing purpose and usage.

- AI-assisted code is allowed, but contributors are responsible for correctness, readability, and tests.

## Making Changes

1. Clone the repository: `git clone https://github.com/zt64/subsonic-kotlin.git`
2. Open the project in IntelliJ IDEA (initial indexing may take a few minutes).
3. Create a separate branch for your change.
4. Implement your changes.
5. Run formatting: `./gradlew ktlintFormat`
6. Run verification: `./gradlew check`
7. Commit using [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/\).
8. Open a pull request with a clear description of what changed and why.

## Pull Request Checklist

- Code is formatted (`ktlintFormat`)
- Tests and checks pass (`check`)
- Public API changes are documented
- Commit messages follow convention
