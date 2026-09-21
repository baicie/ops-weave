"""Compile and execute the dependency-free Java domain checks using JDK 21+."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
if not shutil.which("javac") or not shutil.which("java"):
    raise SystemExit("JDK 21+ is required for domain validation")
sources = [str(p) for p in (root / "modules").rglob("*.java")]
sources.append(str(root / "tests/domain/DomainSmoke.java"))
with tempfile.TemporaryDirectory(prefix="aiops-domain-") as output:
    subprocess.run(["javac", "--release", "21", "-encoding", "UTF-8", "-d", output, *sources], check=True)
    subprocess.run(["java", "-cp", output, "DomainSmoke"], check=True)
