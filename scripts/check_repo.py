"""Static checks for the initialized repository; not a production readiness certification."""
from pathlib import Path
import json
import os
import re
import sys
import yaml
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
count = 0
def source_files():
    excluded = {"node_modules", ".git", ".venv", "build", "target", "__pycache__", ".pytest_cache", ".tmp"}
    for directory, directories, files in os.walk(ROOT, followlinks=False):
        directories[:] = [name for name in directories if name not in excluded and not (Path(directory) / name).is_symlink()]
        for name in files:
            path = Path(directory) / name
            if path.is_file():
                yield path

for path in source_files():
    try:
        if path.suffix == ".json":
            data = json.loads(path.read_text(encoding="utf-8")); count += 1
            if path.name.endswith("schema.json"):
                Draft202012Validator.check_schema(data)
            if path.name.endswith(".tool.json"):
                for field in ("inputSchema", "outputSchema"):
                    Draft202012Validator.check_schema(data[field])
        elif path.suffix in (".yaml", ".yml"):
            list(yaml.safe_load_all(path.read_text(encoding="utf-8"))); count += 1
    except Exception as exc:
        errors.append(f"{path.relative_to(ROOT)}: {exc}")

# Domain and shared kernel must not depend on frameworks, transport or persistence.
for path in (ROOT / "modules").rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    if "domain" in path.parts or "shared-kernel" in path.parts:
        for forbidden in ("org.springframework", "jakarta.persistence", "java.sql", "java.net.http"):
            if re.search(r"import\s+" + re.escape(forbidden), text):
                errors.append(f"Forbidden domain import: {path.relative_to(ROOT)}: {forbidden}")
    if re.search(r"import\s+com\.acme\.opsweave\..*\.infrastructure\.", text):
        errors.append(f"Review cross-infrastructure import: {path.relative_to(ROOT)}")

tools = {}
for path in (ROOT / "contracts/tools").glob("*.tool.json"):
    data = json.loads(path.read_text(encoding="utf-8"))
    key = f"{data['id']}@{data['version']}"
    if key in tools: errors.append(f"Duplicate tool: {key}")
    tools[key] = data
    if data["effect"] != "read": errors.append(f"Starter must not enable action: {key}")
    if data.get("implementationStatus") not in {"notImplemented", "fixtureOnly", "implemented"}:
        errors.append(f"Verify implementation status before advertising tool: {key}")
    if data.get("implementationStatus") == "implemented":
        implementation = data.get("implementationRef", "")
        implementation_path = (ROOT / implementation).resolve()
        if not implementation or not implementation_path.is_relative_to(ROOT.resolve()) or not implementation_path.is_file():
            errors.append(f"Implemented tool must name checked-in executor code: {key}")
    forbidden = {"tenantId", "userId", "permissions", "approvalStatus"}
    if forbidden.intersection(data["inputSchema"].get("properties", {})):
        errors.append(f"Tool accepts trusted identity from model: {key}")

for path in (ROOT / "extensions/skills").glob("*/skill.yaml"):
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    for field in ("inputSchema", "outputSchema", "prompt"):
        target = (path.parent / data[field]).resolve()
        if not target.is_relative_to(path.parent.resolve()) or not target.is_file():
            errors.append(f"Invalid skill reference: {path}: {field}")
    for tool in data.get("allowedTools", []):
        if tool not in tools: errors.append(f"Unregistered skill tool: {tool}")
    if data.get("sideEffects") != "forbidden": errors.append("Starter Skill must forbid side effects")
    skill_md = (path.parent / "SKILL.md").read_text(encoding="utf-8")
    if not skill_md.startswith("---\n"):
        errors.append(f"Missing Skill frontmatter: {path}")
    else:
        metadata = yaml.safe_load(skill_md.split("---", 2)[1])
        if metadata.get("name") != path.parent.name: errors.append("Skill name must match its directory")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", metadata.get("name", "")):
            errors.append("Invalid portable Skill name")

for path in (ROOT / "extensions/skills").glob("*/skill.json"):
    data = json.loads(path.read_text(encoding="utf-8"))
    for field in ("prompt", "outputSchema"):
        target = (path.parent / data[field]).resolve()
        if not target.is_relative_to(path.parent.resolve()) or not target.is_file():
            errors.append(f"Invalid JSON Skill reference: {path}: {field}")
    for tool in data.get("allowedTools", []):
        if tool not in tools: errors.append(f"Unregistered JSON Skill tool: {tool}")
    if data.get("sideEffects") != "forbidden": errors.append("JSON Skill must forbid effects")

if errors:
    print("\n".join(errors)); sys.exit(1)
for path in (ROOT / "apps/agent-runtime/src").glob("domain*.rs"):
    text = path.read_text(encoding="utf-8")
    for forbidden in ("rig::", "sqlx::", "axum::", "rmcp::"):
        if forbidden in text: errors.append(f"Rust domain imports SDK/framework: {path}: {forbidden}")
if (ROOT / "apps/ai-runtime").exists(): errors.append("Old Python runtime still exists")
if errors:
    print("\n".join(errors)); sys.exit(1)
print(f"Repository static checks passed: {count} structured files; {len(tools)} read-only tool definitions")
