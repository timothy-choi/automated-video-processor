# GitHub development workflow

This repository uses GitHub Actions as a **build/test gate**, not as a deployment pipeline.

There is no production deploy, Docker publish, cloud environment, or release workflow yet. Those belong to later phases.

## Intended flow

```text
feature / phase branch
        |
        | git push
        v
GitHub Actions Branch CI
        |
        | tests pass
        v
Pull Request -> main
        |
        v
GitHub Actions PR / Main CI
        |
        | required checks pass
        v
Merge to main
        |
        v
GitHub Actions PR / Main CI (push to main)
        |
        v
verified main branch
```

Do not push feature work directly to `main`. Open a pull request instead.

## Workflows

| File | Workflow name | When it runs | What it validates |
| --- | --- | --- | --- |
| `.github/workflows/branch-ci.yml` | **Branch CI** | Pushes to every branch except `main` | Java 21 + `./mvnw clean test` in `Server/drive` |
| `.github/workflows/main-ci.yml` | **PR / Main CI** | Pull requests targeting `main`, and pushes/merges to `main` | The same Maven command |

Both jobs are named **Java tests**. That name is the status check to require on `main`.

`main` pushes do **not** run Branch CI. They run **PR / Main CI** so merged code is validated again.

## Local equivalent of CI

From `Server/drive`:

```bash
./mvnw --batch-mode --no-transfer-progress clean test
```

CI uses `--batch-mode` and `--no-transfer-progress` so Maven does not prompt and logs stay readable. The lifecycle is still `clean test`.

`verify` / `package` is not used: the current POM has no extra verification plugins, so `clean test` already compiles the application and runs all tests.

## Starting new work

```bash
git checkout main
git pull

git checkout -b phase-2a-job-api

# make changes

git add .
git commit -m "Add durable job API"

git push -u origin phase-2a-job-api
```

Branch names such as `phase-2a-job-api`, `feature/job-persistence`, or `fix/job-validation` are examples, not a required scheme.

Then:

1. Open a pull request into `main`.
2. Wait for **PR / Main CI** / **Java tests**.
3. Review the diff.
4. Merge only when CI is green.

Pushes to the feature branch also run **Branch CI**, so you get feedback before or without a PR.

## What YAML does *not* do

Creating these workflow files does **not** protect `main` by itself.

GitHub Actions will run tests. It will not:

- require a pull request,
- block direct pushes,
- block force pushes,
- mark a check as required.

Those are repository **branch protection** (or ruleset) settings and must be enabled in GitHub.

## Recommended `main` protection

In the GitHub repository settings, protect `main` with the following intent:

1. **Require a pull request before merging.** Feature work should not land by direct push.
2. **Require status checks to pass before merging.** After the first pull request has run CI, select the **Java tests** check from the **PR / Main CI** workflow. The UI may show it as `Java tests` or `PR / Main CI / Java tests`.
3. **Do not allow force pushes** to `main`.
4. **Do not allow deletion** of `main`.
5. **Require conversation resolution before merging**, so review comments are not skipped.
6. Optionally **require the branch to be up to date before merging**, so `main` is not behind when the check ran.

Exact menu labels vary. The intent is: `main` only changes through a reviewed PR whose Java tests passed, and history on `main` cannot be rewritten.

Until those settings are enabled, someone with write access can still push directly to `main`. **PR / Main CI** still runs on those pushes so a broken `main` is at least visible.

## CI vs later deployment

| Now | Later |
| --- | --- |
| Compile and test the Phase 1 Java control service | Deploy workers, scheduler, brokers, or cloud infrastructure |
| No secrets | Credentials only when a real deploy exists |
| `contents: read` only | Write/deploy permissions only when needed |

Do not add PostgreSQL, RabbitMQ, FFmpeg, Go, Docker, or cloud services to CI until those components exist in the active build.
