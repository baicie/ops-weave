"""Compile and execute the dependency-free Java domain checks using JDK 21+."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
if not shutil.which("javac") or not shutil.which("java"):
    raise SystemExit("JDK 21+ is required for domain validation")
sources = [str(p) for p in (root / "modules").rglob("*.java")]
smokes = sorted((root / "tests/domain").glob("*.java"))
sources.extend(str(p) for p in smokes)
with tempfile.TemporaryDirectory(prefix="aiops-domain-") as output:
    # javac's argument file also works when the repository exceeds Windows' command-line limit.
    arguments = Path(output) / "sources.args"
    arguments.write_text("\n".join('"' + path.replace('\\', '/') + '"' for path in sources), encoding="utf-8")
    subprocess.run(["javac", "--release", "21", "-encoding", "UTF-8", "-d", output, "@" + str(arguments)], check=True)
    for smoke in smokes:
        subprocess.run(["java", "-cp", output, smoke.stem], check=True)
