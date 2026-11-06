#!/usr/bin/env python3
"""Static loadability check for the native TLS pair (libtlsclient.so + libtlsclientbridge.so).

Checks, without any Android tooling:
  * every LOAD segment is 16 KB aligned (16 KB page-size devices),
  * the bridge's DT_NEEDED entries are bare sonames the Android linker can
    resolve from the APK's lib dir (no absolute / build-host paths),
  * the bridge exports the JNI entry point the app's Kotlin shim binds to,
  * the Go library exports the `request` symbol the bridge imports,
  * (with --apk) both .so files are STORED (uncompressed) in the APK at
    16 KB aligned offsets, so the platform can mmap them in place.
Exit code 1 on any failure.
"""
import struct, sys, zipfile

JNI_SYMBOL = "Java_com_aggregatorx_apq_engine_network_TlsClient_requestNative"

def elf_info(d):
    assert d[:4] == b"\x7fELF", "not an ELF file"
    phoff = struct.unpack_from("<Q", d, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", d, 0x36)
    loads = []
    for i in range(phnum):
        p_type, _f, off, va, _pa, _fs, _ms, al = struct.unpack_from("<IIQQQQQQ", d, phoff + i * phentsize)
        if p_type == 1:
            loads.append(al)
    shoff = struct.unpack_from("<Q", d, 0x28)[0]
    shentsize, shnum, _ = struct.unpack_from("<HHH", d, 0x3A)
    secs = [struct.unpack_from("<IIQQQQIIQQ", d, shoff + i * shentsize) for i in range(shnum)]
    dyn = next(s for s in secs if s[1] == 6)
    dynstr = secs[dyn[6]]
    dynsym = next(s for s in secs if s[1] == 11)
    def cstr(off):
        start = dynstr[4] + off
        return d[start:d.index(b"\0", start)].decode()
    needed = []
    for j in range(0, dyn[5], 16):
        tag, val = struct.unpack_from("<qQ", d, dyn[4] + j)
        if tag == 1:
            needed.append(cstr(val))
    defined, undefined = set(), set()
    for j in range(0, dynsym[5], 24):
        st_name, st_info, _o, st_shndx, _v, _s = struct.unpack_from("<IBBHQQ", d, dynsym[4] + j)
        if st_name == 0:
            continue
        (defined if st_shndx != 0 else undefined).add(cstr(st_name))
    return loads, needed, defined, undefined

def main():
    args = sys.argv[1:]
    apk = None
    if "--apk" in args:
        apk = args[args.index("--apk") + 1]
    libdir = args[0] if args and not args[0].startswith("--") else "app/src/main/jniLibs/arm64-v8a"
    failures = []
    def read(name):
        if apk:
            with zipfile.ZipFile(apk) as z:
                return z.read(f"lib/arm64-v8a/{name}")
        return open(f"{libdir}/{name}", "rb").read()
    go = read("libtlsclient.so")
    bridge = read("libtlsclientbridge.so")
    go_loads, _, go_defined, _ = elf_info(go)
    br_loads, br_needed, br_defined, br_undef = elf_info(bridge)
    for name, loads in (("libtlsclient.so", go_loads), ("libtlsclientbridge.so", br_loads)):
        bad = [hex(a) for a in loads if a < 0x4000]
        print(f"{name}: LOAD alignments {[hex(a) for a in loads]}")
        if bad:
            failures.append(f"{name}: LOAD segment alignment below 16 KB: {bad}")
    print(f"libtlsclientbridge.so: NEEDED {br_needed}")
    for n in br_needed:
        if "/" in n or "\\" in n:
            failures.append(f"bridge DT_NEEDED is a path, unresolvable on Android: {n}")
    if "libtlsclient.so" not in br_needed:
        failures.append("bridge does not depend on libtlsclient.so")
    if JNI_SYMBOL not in br_defined:
        failures.append(f"bridge does not export {JNI_SYMBOL}")
    else:
        print(f"bridge exports {JNI_SYMBOL}")
    if "request" in br_undef and "request" not in go_defined:
        failures.append("bridge imports `request` but libtlsclient.so does not export it")
    elif "request" in go_defined:
        print("libtlsclient.so exports `request`")
    if apk:
        with zipfile.ZipFile(apk) as z:
            for info in z.infolist():
                if info.filename.startswith("lib/arm64-v8a/") and info.filename.endswith(".so"):
                    # data offset = local header offset + 30 + name len + extra len
                    with open(apk, "rb") as f:
                        f.seek(info.header_offset)
                        hdr = f.read(30)
                        nlen, xlen = struct.unpack_from("<HH", hdr, 26)
                    data_off = info.header_offset + 30 + nlen + xlen
                    stored = info.compress_type == zipfile.ZIP_STORED
                    print(f"APK {info.filename}: stored={stored} dataOffset={data_off} 16KB-aligned={data_off % 16384 == 0}")
                    if info.filename.split('/')[-1] in ("libtlsclient.so", "libtlsclientbridge.so"):
                        if not stored:
                            failures.append(f"{info.filename} is compressed in the APK")
                        if data_off % 16384 != 0:
                            failures.append(f"{info.filename} not 16 KB aligned in the APK (offset {data_off})")
    if failures:
        print("NATIVE PAIR CHECK FAILED:")
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("NATIVE PAIR CHECK PASSED")

if __name__ == "__main__":
    main()
