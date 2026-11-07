#!/usr/bin/env python3
"""Static loadability check for the native TLS pair (libtlsclient.so + libtlsclientbridge.so).

Usage:
  native_pair_check.py --jnilibs app/src/main/jniLibs --abis arm64-v8a,armeabi-v7a,x86_64
  native_pair_check.py --apk app/build/outputs/apk/debug/app-debug.apk --abis ...

Checks per ABI, without any Android tooling:
  * both .so files exist (in the APK: lib/<abi>/...),
  * every LOAD segment is >= 16 KB aligned (16 KB page-size devices),
  * libtlsclient.so's DT_SONAME is the plain `libtlsclient.so`,
  * the bridge's DT_NEEDED contains the plain soname `libtlsclient.so` and no
    entry is a path (Android's linker treats a NEEDED with '/' as a path),
  * the bridge exports exactly the JNI symbols the Kotlin `external fun`s in
    TlsClient.kt bind to (derived from the Kotlin source: package + object + fun),
  * the Go library exports `request` and was built from bogdanfinn/tls-client,
  * (with --apk) both .so files are STORED (uncompressed) at 16 KB-aligned offsets.
Exit code 1 on any failure.
"""
import argparse, os, re, struct, sys, zipfile

KOTLIN_SRC = "app/src/main/java/com/aggregatorx/app/engine/network/TlsClient.kt"


def jni_mangle(s):
    out = []
    for ch in s:
        if ch == "_": out.append("_1")
        elif ch == ";": out.append("_2")
        elif ch == "[": out.append("_3")
        elif ch.isalnum() and ord(ch) < 128: out.append(ch)
        elif ch in "./": out.append("_")
        else: out.append("_0%04x" % ord(ch))
    return "".join(out)


def expected_jni_symbols(path):
    src = open(path, encoding="utf-8").read()
    pkg = re.search(r"^package\s+([\w.]+)", src, re.M).group(1)
    obj = re.search(r"^(?:object|class)\s+(\w+)", src, re.M).group(1)
    funs = re.findall(r"\bexternal\s+fun\s+(\w+)", src)
    if not funs:
        raise SystemExit(f"no `external fun` found in {path}")
    cls = f"{pkg}.{obj}"
    return cls, ["Java_" + jni_mangle(cls) + "_" + jni_mangle(f) for f in funs]


def elf_info(d):
    assert d[:4] == b"\x7fELF", "not an ELF file"
    is64 = d[4] == 2
    if is64:
        phoff, shoff = struct.unpack_from("<Q", d, 0x20)[0], struct.unpack_from("<Q", d, 0x28)[0]
        phentsize, phnum = struct.unpack_from("<HH", d, 0x36)
        shentsize, shnum = struct.unpack_from("<HH", d, 0x3A)
    else:
        phoff, shoff = struct.unpack_from("<I", d, 0x1C)[0], struct.unpack_from("<I", d, 0x20)[0]
        phentsize, phnum = struct.unpack_from("<HH", d, 0x2A)
        shentsize, shnum = struct.unpack_from("<HH", d, 0x2E)
    loads = []
    for i in range(phnum):
        o = phoff + i * phentsize
        if is64:
            p_type, _f, _off, _va, _pa, _fs, _ms, al = struct.unpack_from("<IIQQQQQQ", d, o)
        else:
            p_type, _off, _va, _pa, _fs, _ms, _f, al = struct.unpack_from("<IIIIIIII", d, o)
        if p_type == 1:
            loads.append(al)
    secs = []
    for i in range(shnum):
        o = shoff + i * shentsize
        if is64:
            nm, typ, _fl, _ad, off, size, link, _inf, _al, _es = struct.unpack_from("<IIQQQQIIQQ", d, o)
        else:
            nm, typ, _fl, _ad, off, size, link, _inf, _al, _es = struct.unpack_from("<IIIIIIIIII", d, o)
        secs.append((typ, off, size, link))
    dyn = next(s for s in secs if s[0] == 6)
    dynstr = secs[dyn[3]]
    dynsym = next(s for s in secs if s[0] == 11)

    def cstr(off):
        start = dynstr[1] + off
        return d[start:d.index(b"\0", start)].decode()

    needed, soname = [], None
    dent = 16 if is64 else 8
    for j in range(0, dyn[2], dent):
        tag, val = struct.unpack_from("<qQ" if is64 else "<iI", d, dyn[1] + j)
        if tag == 1: needed.append(cstr(val))
        elif tag == 14: soname = cstr(val)
        elif tag == 0: break
    defined, undefined = set(), set()
    sent = 24 if is64 else 16
    for j in range(0, dynsym[2], sent):
        if is64:
            st_name, _info, _o, shndx, _v, _s = struct.unpack_from("<IBBHQQ", d, dynsym[1] + j)
        else:
            st_name, _v, _s, _info, _o, shndx = struct.unpack_from("<IIIBBH", d, dynsym[1] + j)
        if st_name == 0:
            continue
        (defined if shndx != 0 else undefined).add(cstr(st_name))
    return dict(is64=is64, loads=loads, needed=needed, soname=soname, defined=defined, undefined=undefined)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jnilibs", default=None)
    ap.add_argument("--bridge-dir", default=None,
                    help="dir holding <abi>/libtlsclientbridge.so when not in --jnilibs (CMake output)")
    ap.add_argument("--apk", default=None)
    ap.add_argument("--abis", default="arm64-v8a")
    a = ap.parse_args()
    abis = [x for x in a.abis.split(",") if x]
    cls, jni_syms = expected_jni_symbols(KOTLIN_SRC)
    print(f"Kotlin JNI class: {cls}; expected bridge exports: {jni_syms}")
    failures = []
    zf = zipfile.ZipFile(a.apk) if a.apk else None

    def read(abi, name):
        if zf:
            try:
                return zf.read(f"lib/{abi}/{name}")
            except KeyError:
                return None
        for base in ([a.jnilibs] if name == "libtlsclient.so" else [a.bridge_dir, a.jnilibs]):
            if base and os.path.exists(f"{base}/{abi}/{name}"):
                return open(f"{base}/{abi}/{name}", "rb").read()
        return None

    for abi in abis:
        print(f"--- {abi} ---")
        go, bridge = read(abi, "libtlsclient.so"), read(abi, "libtlsclientbridge.so")
        if go is None: failures.append(f"{abi}: libtlsclient.so missing"); continue
        if bridge is None: failures.append(f"{abi}: libtlsclientbridge.so missing"); continue
        gi, bi = elf_info(go), elf_info(bridge)
        for name, info in (("libtlsclient.so", gi), ("libtlsclientbridge.so", bi)):
            print(f"{abi}/{name}: ELF{'64' if info['is64'] else '32'} LOAD align {[hex(x) for x in info['loads']]}")
            bad = [hex(x) for x in info["loads"] if x < 0x4000]
            if bad: failures.append(f"{abi}/{name}: LOAD alignment below 16 KB: {bad}")
        print(f"{abi}/libtlsclient.so: SONAME {gi['soname']}")
        if gi["soname"] != "libtlsclient.so":
            failures.append(f"{abi}/libtlsclient.so: SONAME is {gi['soname']!r}, expected 'libtlsclient.so'")
        print(f"{abi}/libtlsclientbridge.so: NEEDED {bi['needed']}")
        for n in bi["needed"]:
            if "/" in n or "\\" in n:
                failures.append(f"{abi}: bridge DT_NEEDED is a path, unresolvable on Android: {n}")
        if "libtlsclient.so" not in bi["needed"]:
            failures.append(f"{abi}: bridge does not depend on libtlsclient.so by soname")
        for sym in jni_syms:
            if sym in bi["defined"]: print(f"{abi}: bridge exports {sym}")
            else: failures.append(f"{abi}: bridge does not export {sym}")
        stray = sorted(s for s in bi["defined"] if s.startswith("Java_") and s not in jni_syms)
        if stray: failures.append(f"{abi}: bridge exports JNI symbols for another class: {stray}")
        if "request" not in gi["defined"]:
            failures.append(f"{abi}: libtlsclient.so does not export `request`")
        elif "request" in bi["undefined"]:
            print(f"{abi}: bridge imports `request`, libtlsclient.so exports it")
        if b"github.com/bogdanfinn/tls-client" not in go:
            failures.append(f"{abi}: libtlsclient.so does not contain bogdanfinn/tls-client")
        else:
            print(f"{abi}: libtlsclient.so built from github.com/bogdanfinn/tls-client")
        if zf:
            for name in ("libtlsclient.so", "libtlsclientbridge.so"):
                info = zf.getinfo(f"lib/{abi}/{name}")
                with open(a.apk, "rb") as f:
                    f.seek(info.header_offset)
                    hdr = f.read(30)
                nlen, xlen = struct.unpack_from("<HH", hdr, 26)
                off = info.header_offset + 30 + nlen + xlen
                stored = info.compress_type == zipfile.ZIP_STORED
                print(f"APK lib/{abi}/{name}: stored={stored} offset={off} 16KB-aligned={off % 16384 == 0}")
                if not stored: failures.append(f"APK lib/{abi}/{name} is compressed")
                if off % 16384: failures.append(f"APK lib/{abi}/{name} not 16 KB aligned (offset {off})")
    if failures:
        print("NATIVE PAIR CHECK FAILED:")
        for f in failures: print("  - " + f)
        sys.exit(1)
    print("NATIVE PAIR CHECK PASSED")


if __name__ == "__main__":
    main()
