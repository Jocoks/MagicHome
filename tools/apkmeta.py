import sys, zipfile
for apk in sys.argv[1:]:
    print("===", apk)
    try:
        z = zipfile.ZipFile(apk)
    except Exception as e:
        print("ERR", e); continue
    names = z.namelist()
    print("entries:", len(names))
    for n in names:
        if n.startswith("META-INF/xposed") or n in ("assets/xposed_init",):
            print("---", n)
            try:
                print(z.read(n).decode("utf-8", "replace")[:2000])
            except Exception as e:
                print("ERR", e)
