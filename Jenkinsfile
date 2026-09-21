pipeline {
  agent any
  stages {
    stage('Contracts') { steps { sh 'python3 scripts/check_repo.py && python3 -m pytest tests/contracts' } }
    stage('Java') { steps { sh './gradlew :apps:platform-api:bootJar :apps:ingestion-worker:bootJar' } }
    stage('Rust') { steps { sh 'cargo test --locked --workspace && cargo check --locked --workspace --all-features --all-targets' } }
    stage('Web') { steps { sh 'pnpm install --frozen-lockfile && pnpm build:web' } }
  }
}
// Linux agent template. On Windows use bat/powershell and the corresponding wrapper.
