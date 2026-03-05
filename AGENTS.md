# Repository Guidelines

## Project Structure & Module Organization
This repository is currently documentation-first. Core artifacts live at the root and under `docs/`:
- `설계문서.md`: end-to-end system design and strategy rationale.
- `docs/phase1-execution-todo.md`: milestone checklist for Phase 1 execution.
- `docs/strategy-comparison.md`: canonical template for benchmark results.
- `docs/decision-log.md`: default technical choices and the rationale behind them.
- `CLAUDE.md`: engineering workflow and technical stack reference.

Planned application structure (once implementation starts) is Spring Boot-style:
`src/main/java/com/couponrush/{domain,strategy,api,config}` and matching tests in `src/test/java`.

## Build, Test, and Development Commands
Use these commands once the Gradle project scaffold is added:
- `docker compose up -d`: start local dependencies (MySQL/Redis/Kafka/observability).
- `./gradlew build`: compile and package.
- `./gradlew bootRun --args='--coupon.strategy=pessimistic'`: run with a selected issuance strategy.
- `./gradlew test`: run full test suite (Testcontainers expected).
- `./gradlew test --tests "com.couponrush.strategy.PessimisticLockStrategyTest"`: run a single test class.
- `k6 run k6/scenarios/spike.js`: execute load profile.

## Decision Documentation Rules
- Do not leave technical choices implicit. For any new choice (version, dependency, infra, config convention), document `what/why/trade-off` in `docs/decision-log.md`.
- When implementing a default choice without explicit user instruction, prioritize existing repository standards first, then document the assumption in `docs/decision-log.md`.
- If a choice has realistic alternatives, record why the alternative was not selected.
- If a later change reverses an earlier choice, append a new decision-log entry instead of deleting history.

## Learning Collaboration Rules
- Project goal is learning-first. Do not optimize only for speed; optimize for user understanding and decision-making.
- Before implementation, explain in one short sentence why the step is needed.
- At decision points (version, architecture, naming, config strategy), ask the user first instead of deciding silently.
- After each code change, explain `what changed` and `why` in concise terms.
- End each meaningful step with one checkpoint question so the user can confirm understanding or choose direction.
- When useful, provide a small “user implementation task” option and review the result instead of always implementing everything directly.

## Coding Style & Naming Conventions
- Language targets: Java 21, Spring Boot 4.x.
- Use 4-space indentation and standard Java naming (`PascalCase` class names, `camelCase` methods/fields, `UPPER_SNAKE_CASE` constants).
- Keep strategy implementations explicit and aligned to `IssuanceStrategy` variants (e.g., `PessimisticLockStrategy`, `RedisCounterStrategy`).
- Prefer clear package boundaries by responsibility (`domain`, `strategy`, `api`, `config`).

## Testing Guidelines
- Prioritize correctness first: no oversell, no duplicate issuance, deterministic reconciliation behavior.
- Keep unit/integration test names descriptive, e.g., `issueCoupon_shouldNotOversell_underConcurrentRequests`.
- Run strategy comparisons under identical test conditions and record metrics in `docs/strategy-comparison.md`.

## Commit & Pull Request Guidelines
- Follow concise, scoped commit prefixes seen in history (example: `docs: ...`).
- For technical decisions, include explicit `[결정]` and `[근거]` in commit messages.
- PRs should include: objective, changed files, validation evidence (test/load results), and related issue or milestone.
- For performance-related changes, attach before/after TPS/p99/error-rate data and the exact test profile used.
