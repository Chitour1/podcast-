from pathlib import Path
from PyInstaller.utils.hooks import collect_all, collect_submodules

root = Path(SPECPATH).parent.parent

datas = []
binaries = []
hiddenimports = []

for package in ("df", "libdf", "soundfile", "webview", "pyloudnorm", "yaml"):
    package_datas, package_binaries, package_hidden = collect_all(package)
    datas += package_datas
    binaries += package_binaries
    hiddenimports += package_hidden

hiddenimports += collect_submodules("webview")
hiddenimports += ["webview.platforms.edgechromium", "webview.platforms.winforms", "clr"]

for source_dir, target_dir in (
    (root / "desktop_web", "desktop_web"),
    (root / "config", "config"),
    (root / "models" / "DeepFilterNet3", "models/DeepFilterNet3"),
):
    for item in source_dir.rglob("*"):
        if item.is_file():
            relative_parent = item.parent.relative_to(source_dir)
            datas.append((str(item), str(Path(target_dir) / relative_parent)))

binaries += [
    (str(root / "bin" / "ffmpeg.exe"), "bin"),
    (str(root / "bin" / "ffprobe.exe"), "bin"),
]

a = Analysis(
    [str(root / "desktop_app" / "main.py")],
    pathex=[str(root)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib", "notebook", "IPython"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="ACRPS Podcast Engine",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
