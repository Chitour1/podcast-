#!/usr/bin/env bash
set -euo pipefail
cat tools/final_rebuild/part00 tools/final_rebuild/part01 tools/final_rebuild/rest00 tools/final_rebuild/rest01 tools/final_rebuild/rest02 tools/final_rebuild/rest03 tools/final_rebuild/rest04 tools/final_rebuild/rest05 tools/final_rebuild/rest06 tools/final_rebuild/rest07 tools/final_rebuild/rest08 > generator.gz.b64
base64 -d generator.gz.b64 | gzip -d > build_fizilal_final.py
python3 - <<'PY'
from pathlib import Path
import re
p=Path('build_fizilal_final.py');s=p.read_text()
s=s.replace("'\\u0649':'\\u064a','\\u0629':'\\u0647','\\u0640':' ',","'\\u0649':'\\u064a','\\u0629':'\\u0647','\\u0640':'',")
a=s.index('starts = []\npos = 0\n');b=s.index('\nif len(starts) != 114',a)
robust='''starts = []
pos = 0
for idx, name in enumerate(names, 1):
    found = None
    primary = re.compile(r'^(?:المجلد\\s+[^0-9]{1,30}\\s+)?' + str(idx) + r'\\s+سوره(?:\\s|$)')
    fallback = re.compile(r'^سوره\\s+[^0-9]{1,50}\\s+' + str(idx) + r'(?:\\s|$)')
    for j in range(pos, len(np)):
        n, t, st = np[j]
        head = n[:180]
        if primary.search(head) or fallback.search(head):
            found = j
            break
    if found is None:
        raise SystemExit('MISSING_SURAH=%d:%s;FOUND=%d' % (idx, name, len(starts)))
    starts.append(found)
    pos = found + 1
'''
s=s[:a]+robust+s[b:]
a=s.index('def parse_range(text):');b=s.index('\nDB = ASSETS',a)
parser='''def parse_range(text):
    nt = norm(text)
    m = re.search(r'(?:^|\\s)سوره\\s+.{1,90}?\\s+[0-9٠-٩]+\\s+الايات\\s*[:\\-]?\\s*([0-9٠-٩]+)(?:\\s*(?:-|–|—|الى|الي)\\s*([0-9٠-٩]+))?', nt)
    if not m:
        return None
    a = int(digits(m.group(1)))
    b = int(digits(m.group(2) or m.group(1)))
    if a < 1 or b < a or b > 400:
        return None
    return a, b
'''
s=s[:a]+parser+s[b:]
s=s.replace("block = [paras[k][0] for k in range(start+1, end)]","block = [paras[k][0] for k in range(max(0,start), end)]")
old='''    for text in block:
        rg = parse_range(text)
        heading_style = len(text) < 220 and ('Heading' in '' or False)
        is_heading = rg is not None and len(text) < 240
        if is_heading:
            acc, order = flush_local(acc, order, title, current_range, seen_range)
            title = text.strip()
            current_range = rg
            seen_range = True
            continue
        acc.append(text)
        if len(acc) >= 34:
'''
new='''    for text in block:
        rg = parse_range(text)
        if rg is not None:
            acc, order = flush_local(acc, order, title, current_range, seen_range)
            lines = text.splitlines()
            heading = next((x.strip() for x in lines if parse_range(x) is not None), lines[0].strip() if lines else '')
            title = heading[:220] if heading else ('الآيات %d–%d' % rg)
            current_range = rg
            seen_range = True
            remainder = '\\n'.join(x for x in lines if x.strip() != heading).strip()
            if remainder:
                acc.append(remainder)
            continue
        acc.append(text)
        if len(acc) >= 34:
'''
if old not in s: raise SystemExit('RANGE_LOOP_NOT_FOUND')
p.write_text(s.replace(old,new))
PY
BOOK_URL='https://islamport.com/kindle/%D8%A7%D9%84%D9%85%D9%83%D8%AA%D8%A8%D8%A9%20%D8%A7%D9%84%D8%B4%D8%A7%D9%85%D9%84%D8%A9%20%D9%84%D9%84%D9%83%D9%86%D8%AF%D9%84/%D8%A7%D9%84%D8%AA%D9%81%D8%A7%D8%B3%D9%8A%D8%B1/%D9%81%D9%8A%20%D8%B8%D9%84%D8%A7%D9%84%20%D8%A7%D9%84%D9%82%D8%B1%D8%A2%D9%86/%D9%81%D9%8A%20%D8%B8%D9%84%D8%A7%D9%84%20%D8%A7%D9%84%D9%82%D8%B1%D8%A2%D9%86%20%D9%84%D9%80%20%D8%B3%D9%8A%D8%AF%20%D9%82%D8%B7%D8%A8.docx'
curl -L --fail --retry 4 -A 'Mozilla/5.0' "$BOOK_URL" -o book.docx
curl -L --fail --retry 4 'https://api.alquran.cloud/v1/quran/quran-uthmani' -o quran.json
curl -L --fail --retry 4 'https://api.alquran.cloud/v1/quran/quran-simple-clean' -o comparison.json
BOOK_DOCX=book.docx QURAN_JSON=quran.json COMPARE_JSON=comparison.json python3 build_fizilal_final.py | tee validation.txt
base64 -d tools/apply_precise_fix.py.gz.b64 | gzip -d > apply_precise_fix.py
python3 apply_precise_fix.py | tee -a validation.txt
base64 -d tools/runtime_fix.py.gz.b64 | gzip -d > runtime_fix.py
python3 runtime_fix.py | tee -a validation.txt
grep -q 'RUNTIME_FIX_WRITTEN=1' validation.txt
grep -q 'RECITER_COUNT=38' validation.txt
sed -i '/^package com.acrps.fizilalpro;/a import android.database.sqlite.SQLiteDatabase;' app/src/main/java/com/acrps/fizilalpro/LibraryActivity.java
gradle --no-daemon assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
test -f "$APK"
grep -q 'catch(Throwable' app/src/main/java/com/acrps/fizilalpro/AyahTafsirActivity.java
grep -q 'ReciterCatalog.NAMES' app/src/main/java/com/acrps/fizilalpro/MushafActivity.java
grep -q 'حفظ هذه الآية وتفسيرها PDF' app/src/main/java/com/acrps/fizilalpro/AyahTafsirActivity.java
python3 - <<'PY'
import zipfile,sqlite3,tempfile,os
apk='app/build/outputs/apk/debug/app-debug.apk'
with zipfile.ZipFile(apk) as z: data=z.read('assets/fizilal_pro.db')
p=tempfile.mktemp('.db');open(p,'wb').write(data);c=sqlite3.connect(p).cursor()
assert c.execute('select count(*) from ayah_tafsir').fetchone()[0]==6236
assert c.execute('select count(*) from ayahs').fetchone()[0]==6236
os.unlink(p)
print('RUNTIME_APK_VALIDATED=1')
PY
cp "$APK" Fi-Zilal-Runtime-Fixed.apk
sha256sum "$APK" | tee -a validation.txt
