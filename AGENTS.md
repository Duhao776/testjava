# AGENTS.md

## Build & Run

No build system — use `javac` and `java` directly:

```bash
javac ATMSystem.java
java ATMSystem
```

## Project Layout

- Single source file: `ATMSystem.java` in the project root.
- All classes (`Account`, `ATM`, `ArrayAccountRepository`, `ATMSystem`) live in the same file.
- JDK 21 (IntelliJ IDEA module, not Maven/Gradle).
- `.class` files are build artifacts — add a `.gitignore` entry for `*.class` before committing.

## Architecture

- `ATMSystem` — main entry point with console menu (Scanner-based).
- `ATM` — business logic (deposit, withdraw, transfer, balance query).
- `ArrayAccountRepository` implements `AccountRepository` — in-memory array storage with auto-resize.
- `Account` — plain data class with getters/setters.

## Conventions

- No test framework configured. To add tests, set up JUnit manually.
- No CI, no linter/formatter. Standard Java conventions expected.
- Comments are in Chinese; code identifiers are in English.
