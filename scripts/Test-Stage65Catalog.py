"""Independent source/doc catalog gate; deployed reflection is checked by Stage65CoreIT."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
catalog = json.loads((root / "runtime/public-api.json").read_text(encoding="utf-8"))
api = (root / "plugin/src/main/java/deposit/api/DepositAPI.java").read_text(encoding="utf-8")
hub = root.parent / "deposit-hub"
document = (hub / "deposit-server-catalog.md").read_text(encoding="utf-8")
names = [entry["name"] for entry in catalog]
assert len(names) == len(set(names)), "Duplicate public procedure"
registered = re.findall(r'@Procedure\(name="([^"]+)"', api)
assert sorted(registered) == sorted(names), "Catalog/registration mismatch"
for entry in catalog:
    args = [arg["name"] for arg in entry["args"]]
    if entry["name"] not in {"deposit.utils.login", "deposit.utils.bootstrap"}:
        args.append("token")
    output = {"query": "data, columns, presence", "eligibility": "value, data", "page": "data"}.get(entry["output"], entry["output"])
    assert f'| `{entry["name"]}` | `{", ".join(args)}` | `{output}` |' in document, entry["name"]
    assert "context" not in args and "request" not in args, entry["name"]
for path in hub.glob("*.md"):
    # Acceptance evidence retains the original claims and signatures, explicitly historical.
    if path.name in {"implementation_status.md", "TODO.md"}:
        continue
    text = path.read_text(encoding="utf-8")
    for match in re.finditer(r"CALL\s+deposit\.[\w.]+[\s\S]{0,700}?YIELD[^\n`]*", text):
        call = match.group()
        assert not re.search(r"YIELD[^\n`]*\bmeta\b|\$(?:context|ctx|request)\b", call), (path.name, call)
print(f"PASS: {len(names)} delivered positional contracts, exact documentation columns, and current examples")
