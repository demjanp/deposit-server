"""Stopped, single-installation OCFL backup drill; no public export/import protocol."""
import argparse, hashlib, json, os, re, shutil, subprocess, zipfile
from pathlib import Path, PurePosixPath
ROOT = Path(r"C:\data_processed\Deposit_v2\stage0")
RUN = ROOT / "runs/stage0-host"
PARTS = ("neo4j/data", "neo4j/conf", "resources", "exports", "installed-artifacts.json", "owner.json")
def digest_stream(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1024*1024), b""): h.update(block)
    return h.hexdigest()
def guard():
    assert RUN.resolve() == Path(r"C:\data_processed\Deposit_v2\stage0\runs\stage0-host"), "Wrong installation"
    owner=json.loads((RUN/"owner.json").read_text(encoding="utf-8-sig"))
    assert owner["runId"]=="stage0-host" and owner["root"]==str(ROOT), "Wrong owner"
    check=subprocess.run(["powershell.exe","-NoProfile","-Command",
        "$p=Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains('C:\\data_processed\\Deposit_v2\\stage0') -and $_.CommandLine -match 'org\\.neo4j\\.server\\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}; if($p){exit 1}"],capture_output=True)
    assert check.returncode==0, "Stop the test-owned server before backup or restore"
    for part in PARTS:
        p=RUN/part
        assert p.exists() and p.resolve()==p.absolute(), "Missing or linked backup component"
        for base,dirs,files in os.walk(p):
            for name in dirs+files:
                child=Path(base)/name
                assert child.resolve()==child.absolute() and not child.is_symlink(), "Linked backup path"
def allowed(name):
    p=PurePosixPath(name)
    return not p.is_absolute() and ".." not in p.parts and ":" not in name and "\\" not in name and any(name==x or name.startswith(x+"/") for x in PARTS)
def snapshot(path):
    assert not path.exists(), "Use a fresh backup path"
    manifest={"schema":1,"installation":"stage0-host","parts":list(PARTS),"files":{},"directories":[]}
    with zipfile.ZipFile(path,"w",zipfile.ZIP_DEFLATED,compresslevel=1) as z:
        for part in PARTS:
            p=RUN/part
            if p.is_dir():
                manifest["directories"].append(part)
                entries=sorted(p.rglob("*"))
            else: entries=[p]
            for f in entries:
                name=f.relative_to(RUN).as_posix()
                if f.is_dir(): manifest["directories"].append(name); continue
                with f.open("rb") as stream: checksum=digest_stream(stream)
                manifest["files"][name]={"sha256":checksum,"bytes":f.stat().st_size}
                z.write(f,name)
        z.writestr("backup-manifest.json",json.dumps(manifest,sort_keys=True))
    path.with_suffix(".sha256").write_text(hashlib.sha256(path.read_bytes()).hexdigest()+"\n")
    return verify(path)
def validate_ocfl(z):
    prefix="resources/ocfl/"
    names=set(z.namelist())
    def read(name): return z.read(prefix+name)
    assert read("0=ocfl_1.1")==b"ocfl_1.1\n"
    extension="0004-hashed-n-tuple-storage-layout"
    assert json.loads(read("ocfl_layout.json"))["extension"]==extension
    config=json.loads(read("extensions/"+extension+"/config.json"))
    assert config["digestAlgorithm"]=="sha256" and config["tupleSize"]==3 and config["numberOfTuples"]==3 and config["shortObjectRoot"] is False
    objects=[]
    for inventory_path in sorted(n for n in names if re.fullmatch(prefix+r"[0-9a-f]{3}/[0-9a-f]{3}/[0-9a-f]{3}/[0-9a-f]{64}/inventory.json",n)):
        base=inventory_path.rsplit("/",1)[0]+"/"
        raw=z.read(inventory_path);inv=json.loads(raw);identity=inv["id"]
        assert re.fullmatch(r"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}",identity)
        h=hashlib.sha256(identity.encode()).hexdigest()
        assert base==prefix+f"{h[:3]}/{h[3:6]}/{h[6:9]}/{h}/"
        assert z.read(base+"0=ocfl_object_1.1")==b"ocfl_object_1.1\n"
        assert inv["type"]=="https://ocfl.io/1.1/spec/#inventory" and inv["digestAlgorithm"]=="sha256"
        assert inv["head"]=="v1" and set(inv["versions"])=={"v1"} and len(inv["manifest"])==1
        assert z.read(base+"v1/inventory.json")==raw
        expected=hashlib.sha256(raw).hexdigest()+"  inventory.json"
        assert z.read(base+"inventory.json.sha256").decode().strip()==expected
        assert z.read(base+"v1/inventory.json.sha256").decode().strip()==expected
        checksum,locations=next(iter(inv["manifest"].items()))
        assert len(locations)==1 and len(inv["versions"]["v1"]["state"])==1
        logical=inv["versions"]["v1"]["state"][checksum]
        assert len(logical)==1 and re.fullmatch(r"[a-zA-Z0-9_.-]+",logical[0]) and logical[0] not in (".","..")
        assert locations==["v1/content/"+logical[0]]
        with z.open(base+locations[0]) as stream: assert digest_stream(stream)==checksum
        expected_files={base+s for s in ("0=ocfl_object_1.1","inventory.json","inventory.json.sha256","v1/inventory.json","v1/inventory.json.sha256",locations[0])}
        assert {n for n in names if n.startswith(base)}==expected_files
        objects.append({"id":identity,"payload":logical[0],"sha256":checksum,"status":"valid-v1"})
    # Any hash-shaped leaf must be a complete object; inventory files are not resource candidates.
    leaves={n[:len(prefix)]+"/".join(n[len(prefix):].split("/")[:4])+"/" for n in names if re.match(prefix+r"[0-9a-f]{3}/",n)}
    assert len(leaves)==len(objects), "Incomplete OCFL object"
    return objects
def verify(path):
    with path.open("rb") as stream: checksum=digest_stream(stream)
    assert checksum==path.with_suffix(".sha256").read_text().strip(), "Archive digest mismatch"
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        assert len(names)==len(set(names)), "Duplicate entries"
        manifest=json.loads(z.read("backup-manifest.json"))
        assert manifest["schema"]==1 and manifest["installation"]=="stage0-host" and manifest["parts"]==list(PARTS)
        assert set(names)==set(manifest["files"])|{"backup-manifest.json"}, "Incomplete backup"
        assert all(allowed(n) for n in manifest["files"]) and all(allowed(n) for n in manifest["directories"]), "Unsafe backup entry"
        for name,entry in manifest["files"].items():
            assert z.getinfo(name).file_size==entry["bytes"], "Backup size mismatch"
            with z.open(name) as stream: assert digest_stream(stream)==entry["sha256"], "Backup content digest mismatch"
        for namespace in ("neo4j","system"):
            assert any(n.startswith("neo4j/data/databases/"+namespace+"/") for n in names), "Missing database"
        assert "neo4j/conf/deposit.properties" in names and "installed-artifacts.json" in names
        assert "resources/staging" in manifest["directories"] and "resources/ocfl-work" in manifest["directories"]
        objects=validate_ocfl(z)
        return {"status":"verified","archive":str(path),"sha256":checksum,"files":len(manifest["files"]),"objects":objects}
def invalid_copies(path):
    results=[]
    for mode in ("corrupt","incomplete","graph-only"):
        out=path.with_name(mode+"-backup.zip")
        with zipfile.ZipFile(path) as source, zipfile.ZipFile(out,"w",zipfile.ZIP_DEFLATED,compresslevel=1) as dest:
            target=next(n for n in source.namelist() if "/v1/content/" in n)
            for entry in source.infolist():
                if mode=="graph-only" and entry.filename.startswith("resources/"): continue
                if entry.filename==target and mode=="incomplete": continue
                data=source.read(entry.filename)
                if entry.filename==target: data=bytes([data[0]^1])+data[1:] if data else b"x"
                dest.writestr(entry.filename,data)
        with out.open("rb") as stream: out.with_suffix(".sha256").write_text(digest_stream(stream)+"\n")
        try: verify(out)
        except AssertionError as error: results.append({"case":mode,"status":"rejected","reason":str(error)})
        else: raise AssertionError("Invalid backup accepted")
    return results
def restore(path,previous):
    verification=verify(path) # Complete verification before changing any target.
    # Verify the same stopped runtime/plugin set remains installed.
    with zipfile.ZipFile(path) as z:
        installed=json.loads(z.read("installed-artifacts.json").decode("utf-8-sig"))
        current=json.loads((RUN/"installed-artifacts.json").read_text(encoding="utf-8-sig"))
        assert installed==current, "Restore requires the same pinned installation"
        for plugin in installed["plugins"]:
            with (RUN/"neo4j/plugins"/plugin["name"]).open("rb") as stream: assert digest_stream(stream)==plugin["sha256"]
    snapshot(previous)
    for part in PARTS:
        target=RUN/part
        assert target.resolve()==target.absolute() and target.is_relative_to(RUN) and target!=RUN, "Restore target escaped the owned installation"
        if target.is_dir(): shutil.rmtree(target)
        elif target.exists(): target.unlink()
    with zipfile.ZipFile(path) as z:
        manifest=json.loads(z.read("backup-manifest.json"))
        for name in manifest["directories"]: (RUN/name).mkdir(parents=True,exist_ok=True)
        for name in manifest["files"]:
            target=RUN/name;target.parent.mkdir(parents=True,exist_ok=True)
            with z.open(name) as source,target.open("wb") as dest: shutil.copyfileobj(source,dest,1024*1024)
    return verification
if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("mode",choices=["backup","restore","verify"]);parser.add_argument("directory",type=Path);args=parser.parse_args()
    directory=args.directory.resolve()
    assert directory.is_relative_to(ROOT/"evidence") and directory!=ROOT/"evidence", "Evidence must stay under the approved root"
    directory.mkdir(parents=True,exist_ok=True);archive=directory/"operational-backup.zip"
    if args.mode!="verify": guard()
    if args.mode=="backup":
        report=snapshot(archive);report["negativeCases"]=invalid_copies(archive)
    elif args.mode=="restore": report=restore(archive,directory/"previous-state.zip")
    else: report=verify(archive)
    (directory/(args.mode+"-report.json")).write_text(json.dumps(report,indent=2))
    print(json.dumps({"status":report["status"],"objects":len(report["objects"]),"report":str(directory/(args.mode+"-report.json"))}))
