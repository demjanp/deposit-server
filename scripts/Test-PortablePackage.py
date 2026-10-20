import hashlib
import math
import json
import pathlib
import sys
import zipfile

MAX_ENTRIES = 100_000
MAX_EXPANDED = 2 * 1024 * 1024 * 1024


KINDS = {"classes":"Class", "objects":"Object", "descriptors":"Descriptor", "geometries":"DGeometry", "memberships":"Membership", "relationships":"Relationship", "classRelationships":"ClassRelationship", "patterns":"Pattern", "userTools":"UserTool", "resources":"DResource"}

def scientific(kind, value, resources):
    if value is None: return
    if kind == "Integer":
        if not isinstance(value, str) or str(int(value)) != value or not -(2**63) <= int(value) < 2**63: raise ValueError("noncanonical Integer")
    elif kind == "String":
        if not isinstance(value,str): raise ValueError("String type")
    elif kind == "Boolean":
        if type(value) is not bool: raise ValueError("Boolean type")
    elif kind == "Float":
        if type(value) not in (int,float) or not math.isfinite(value): raise ValueError("Float type")
    elif kind == "List":
        if value["itemType"] in ("DResource","DGeometry"): raise ValueError("forbidden List type")
        for item in value["items"]: scientific(value["itemType"],item,resources)
    elif kind == "DDict":
        for item in value.values():
            if item["type"] in ("DResource","DGeometry"): raise ValueError("forbidden DDict type")
            scientific(item["type"],item["value"],resources)
    elif kind == "DResource":
        if value["resourceId"] not in resources: raise ValueError("resource reference")
    elif kind in ("Date","Time","LocalTime","DateTime","LocalDateTime"):
        if not isinstance(value,str): raise ValueError("temporal type")
    elif kind == "Duration":
        for component in ("months","days","seconds","nanoseconds"): scientific("Integer",str(value[component]),resources)
    elif kind == "Point":
        scientific("Integer",str(value["srid"]),resources)
        if len(value["coordinates"]) not in (2,3): raise ValueError("Point dimensions")
    elif kind == "DGeometry":
        if not all(key in value for key in ("wkt","srid","axisOrder","dimensions")): raise ValueError("geometry fields")
    else: raise ValueError("unknown scientific type "+kind)

def state_check(state, resources):
    classes={id:row for (kind,id),row in state.items() if kind=="Class"}
    objects={id:row for (kind,id),row in state.items() if kind=="Object"}
    members={}
    declarations={}
    geometries={id:row for (kind,id),row in state.items() if kind=="DGeometry"}
    for (kind,id), row in state.items():
        if kind=="ClassDescriptor":
            if row["classId"] not in classes: raise ValueError("declaration owner")
            key=(row["classId"],row["name"])
            if key in declarations: raise ValueError("duplicate declaration")
            declarations[key]=row["type"]
        elif kind=="Membership":
            if row["objectId"] not in objects or row["classId"] not in classes or row["objectId"] in members: raise ValueError("membership reference")
            members[row["objectId"]]=row["classId"]
        elif kind in ("Relationship","ClassRelationship"):
            endpoints=objects if kind=="Relationship" else classes
            if row["sourceId"] not in endpoints or row["targetId"] not in endpoints: raise ValueError("relationship endpoints")
        elif kind=="Pattern":
            by_name={c["name"] for c in classes.values()}
            roles=row["definition"]["objects"]
            for role in roles.values():
                if role["className"] not in by_name: raise ValueError("Pattern Class")
                for edge in role["relationships"]:
                    if edge["targetName"] not in roles: raise ValueError("Pattern target")
    if set(members)!=set(objects): raise ValueError("missing membership")
    owners=set()
    for (kind,id), row in state.items():
        if kind!="Descriptor": continue
        if "value" not in row or row["objectId"] not in members: raise ValueError("Descriptor ownership/value")
        declared=declarations[(members[row["objectId"]],row["name"])]
        scientific(declared,row["value"],resources)
        if declared=="DGeometry" and row["value"] is not None:
            g=geometries[row["geometryId"]]
            if g["descriptorId"]!=id or row["geometryId"] in owners: raise ValueError("geometry ownership")
            owners.add(row["geometryId"])
            for field in ("wkt","srid","verticalSrid","axisOrder","dimensions"):
                if row["value"].get(field)!=g.get(field): raise ValueError("geometry value disagreement")
    if owners!=set(geometries): raise ValueError("unowned geometry")

def semantic(manifest, graph, history, hashes):
    if set(graph)!=set(KINDS): raise ValueError("category set")
    ns=manifest["sourceNamespaceId"]
    state={}
    resources={r["id"]:r for r in graph["resources"]}
    for category,kind in KINDS.items():
        for row in graph[category]:
            key=(kind,row["id"])
            if row["kind"]!=kind or row.get("namespaceId",ns)!=ns or key in state: raise ValueError("record identity/category")
            state[key]=row
    for clazz in graph["classes"]:
        for declaration in clazz["descriptors"]:
            key=("ClassDescriptor",declaration["id"])
            if key in state: raise ValueError("duplicate declaration identity")
            state[key]=declaration
    for resource in resources.values():
        size,digest=hashes["resources/"+resource["sha256"]]
        if size!=resource["bytes"] or digest!=resource["sha256"]: raise ValueError("resource metadata")
    state_check(state,resources)
    if manifest["scope"]=="subgraph" and graph["userTools"]: raise ValueError("subgraph User Tools")
    revision_count=0
    if history is not None:
        if (manifest["scope"]!="namespace" or history["namespaceId"]!=ns or
                history["historyMode"]!="persistent" or not history["complete"]):
            raise ValueError("history provenance/mode")
        active=None
        epoch_ids=set()
        for epoch in history["epochs"]:
            if epoch["id"] in epoch_ids or epoch["baseline"]!=epoch["revisions"][0]: raise ValueError("history epoch/baseline")
            epoch_ids.add(epoch["id"]);current={};parent=None;sequence=-1
            if epoch["baselineRevisionId"]!=epoch["baseline"]["revisionId"] or epoch["headRevisionId"]!=epoch["revisions"][-1]["revisionId"]: raise ValueError("history epoch bounds")
            for revision in epoch["revisions"]:
                if revision.get("parentRevisionId")!=parent or revision["sequence"]<=sequence or revision["historyEpochId"]!=epoch["id"]: raise ValueError("history chain")
                keys=set()
                for change in revision["changes"]:
                    key=(change["entityKind"],change["entityId"])
                    if key in keys or current.get(key)!=change["before"]: raise ValueError("history before image")
                    keys.add(key)
                    for image in (change["before"],change["after"]):
                        if image is not None and (image["kind"],image["id"],image["namespaceId"])!=(*key,ns): raise ValueError("history image identity")
                for change in revision["changes"]:
                    key=(change["entityKind"],change["entityId"])
                    if change["after"] is None: current.pop(key,None)
                    else: current[key]=change["after"]
                state_check(current,resources)
                parent=revision["revisionId"];sequence=revision["sequence"];revision_count+=1
            if epoch["id"]==history["activeEpochId"]: active=epoch
        if active is None or active["headRevisionId"]!=history["headRevisionId"] or active["closed"]: raise ValueError("active history head")
    expected_history=manifest["scope"]=="namespace" and manifest["historyMode"]=="persistent"
    if manifest["historyMode"] not in ("disabled","transient","persistent") or bool(manifest["includeHistory"])!=expected_history or ((history is not None)!=expected_history): raise ValueError("history mode/flag")
    if manifest["scope"]=="namespace" and graph["userTools"]:
        if graph["userTools"][0]["data"]!="  {not json} ?\n": raise ValueError("fixture opaque tool changed")
        counts=[d["value"] for d in graph["descriptors"] if d["name"]=="Count"]
        if counts!=[str(-(2**63))]: raise ValueError("fixture signed Integer changed")
        if not any(d["name"]=="Unknown" and d["value"] is None for d in graph["descriptors"]): raise ValueError("fixture explicit null missing")
    if manifest["scope"]=="subgraph":
        if len(graph["objects"])!=3 or len(graph["patterns"])!=1 or resources or manifest["omittedEdgeCount"]!=1: raise ValueError("boundary fixture differs")
    return {"scope":manifest["scope"],"counts":{k:len(v) for k,v in graph.items()},"historyRevisions":revision_count,"sourceRevision":manifest["exportedRevision"]}

def validate(path: pathlib.Path) -> None:
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_ENTRIES:
            raise ValueError("entry limit exceeded")
        names = [entry.filename for entry in entries]
        if len(set(names)) != len(names):
            raise ValueError("duplicate archive path")
        if any(entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) for entry in entries):
            raise ValueError("unsupported compression")
        expanded = 0
        hashes = {}
        for entry in entries:
            name = entry.filename
            if (not name or name.startswith(("/", "\\")) or "\\" in name or ":" in name
                    or any(part in ("", ".", "..") for part in name.split("/"))):
                raise ValueError("unsafe archive path")
            if name not in ("manifest.json", "graph.json", "history.json") and not (
                    name.startswith("resources/") and len(name) == 74
                    and all(c in "0123456789abcdef" for c in name[10:])):
                raise ValueError("unsupported package member")
            if entry.is_dir():
                raise ValueError("directory members are not allowed")
            digest = hashlib.sha256()
            size = 0
            with archive.open(entry) as stream:
                while chunk := stream.read(64 * 1024):
                    size += len(chunk)
                    expanded += len(chunk)
                    if expanded > MAX_EXPANDED:
                        raise ValueError("decompressed size limit exceeded")
                    digest.update(chunk)
            hashes[name] = (size, digest.hexdigest())
            if name.startswith("resources/") and name[10:] != digest.hexdigest():
                raise ValueError("resource bytes differ from content-addressed path")
        if "manifest.json" not in hashes or "graph.json" not in hashes:
            raise ValueError("required package members are missing")
        manifest = json.loads(archive.read("manifest.json"))
        graph = json.loads(archive.read("graph.json"))
        if set(manifest["files"]) != set(names) - {"manifest.json"}:
            raise ValueError("manifest member set differs from archive")
        for name, metadata in manifest["files"].items():
            if metadata["bytes"] != hashes[name][0] or metadata["sha256"] != hashes[name][1]:
                raise ValueError(f"manifest checksum mismatch: {name}")
        if manifest.get("scope")=="subgraph" and "history.json" in hashes:
            raise ValueError("subgraph package contains history")
        expected = {"classes", "objects", "descriptors", "geometries", "memberships",
                    "relationships", "classRelationships", "patterns", "userTools", "resources"}
        if set(graph) != expected:
            raise ValueError("graph categories are incomplete")
        history = json.loads(archive.read("history.json")) if "history.json" in hashes else None
        semantic(manifest,graph,history,hashes)
        print("PASS: Deposit package validation")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("FAIL: Deposit package validation; usage: Test-PortablePackage.py <package.zip>")
        raise SystemExit(1)
    try:
        validate(pathlib.Path(sys.argv[1]))
    except Exception as failure:
        print(f"FAIL: Deposit package validation: {failure}")
        raise SystemExit(1)
    print("independent-package-validation-passed")
