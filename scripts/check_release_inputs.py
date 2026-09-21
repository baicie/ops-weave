"""Check bootstrap artifacts; this is not a substitute for security or integration testing."""
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
required = [
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "pnpm-lock.yaml",
    "pnpm-workspace.yaml",
    "Cargo.lock", "apps/platform-api/gradle.lockfile",
    "apps/ingestion-worker/gradle.lockfile",
]
missing = [p for p in required if not (root / p).is_file()]
if missing:
    print("Initialization is incomplete. Generate, review and commit:")
    print("\n".join(f"  - {p}" for p in missing))
    sys.exit(1)
print("Bootstrap files exist. Continue with complete builds, security review, SBOM and integration tests.")
