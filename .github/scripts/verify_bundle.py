"""Validate the actual installable asset, not just the source inventory."""
import hashlib, json, sys, zipfile
from pathlib import Path

v = sys.argv[1]
p = Path(f"build/n39/gradle/patches/libs/patches-{v}.mpp")
with zipfile.ZipFile(p) as z:
    assert z.testzip() is None, "corrupt MPP"
    assert "classes.dex" in z.namelist(), "Manager requires root Android DEX"
    assert "extensions/extension.mpe" in z.namelist(), "missing extension"
    assert not any(n.startswith("validation/") for n in z.namelist()), "test harness leaked into bundle"
    mf = z.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").replace("\n ", "")
    assert "YYDarlinker/morphe-ai-caption-translator" in mf, "wrong repository identity"
    assert "YYDarlinker/morphe-ai-captions\n" not in mf
    assert f"Version: {v}\n" in mf.replace("\r\n", "\n"), "wrong version"
    root_dex = z.read("classes.dex")
    dex = z.read("extensions/extension.mpe")
    assert dex.startswith(b"dex\n"), "Morphe extension must be raw DEX"
    assert int.from_bytes(dex[32:36], "little") == len(dex), "extension DEX length mismatch"
    assert b"NativeCaptionBridge" in dex
    release = tuple(map(int, v.split("-")[0].split(".")))
    if release >= (1, 5, 5):
        assert b"RebuildCueProjection" in dex, "cue projection missing from extension"
        assert b"REBUILD_CUE_PROJECTION" in dex, "cue projection diagnostics missing"
        assert b"RememberedCaptionSelection" not in dex, "removed language memory leaked into extension"
        assert b"CaptionTargetPolicy" not in dex, "removed target policy leaked into extension"
        assert b"Remember caption selection" not in root_dex, "removed language patch leaked into root"
        assert f"release-{v}".encode() in dex, "release build id missing from extension"
        assert f"local-{v}".encode() not in dex, "local build id leaked into release extension"
    if release >= (1, 3, 5):
        assert b"RebuildController" in dex and b"CaptionDiagnosticArchive" in dex
    else:
        assert b"AnchoredCaptionPlan" in dex
    assert b"SemanticLedgerCaptionController" not in dex and b"LocalDisplaySliceFallback" not in dex
    if release >= (1, 2, 0):
        locales = z.read("captionlocales/index.txt").decode().splitlines()
        assert len(locales) >= 14 and len(locales) == len(set(locales))
        for locale in locales:
            assert f"captionlocales/{locale}/caption_addon_strings.xml" in z.namelist()

print(json.dumps({"asset": p.name, "bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "root_dex": True, "extension": True}))
