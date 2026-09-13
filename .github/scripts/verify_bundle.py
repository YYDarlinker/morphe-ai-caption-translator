"""Validate the actual installable asset, not just the source inventory."""
import hashlib, json, sys, zipfile, io
from pathlib import Path
v=sys.argv[1]
p=Path(f"patches/build/libs/patches-{v}.mpp")
with zipfile.ZipFile(p) as z:
    assert z.testzip() is None, "corrupt MPP"
    assert "classes.dex" in z.namelist(), "Manager requires root Android DEX"
    assert "extensions/extension.mpe" in z.namelist(), "missing extension"
    mf=z.read("META-INF/MANIFEST.MF").decode().replace("\r\n ","").replace("\n ","")
    assert "YYDarlinker/morphe-ai-caption-translator" in mf, "wrong repository identity"
    assert "YYDarlinker/morphe-ai-captions\n" not in mf
    assert v in mf, "wrong version"
    with zipfile.ZipFile(io.BytesIO(z.read("extensions/extension.mpe"))) as e:
        assert e.testzip() is None
        assert any(n.endswith(".dex") for n in e.namelist())
print(json.dumps({"asset":p.name,"bytes":p.stat().st_size,"sha256":hashlib.sha256(p.read_bytes()).hexdigest(),"root_dex":True,"extension":True}))
