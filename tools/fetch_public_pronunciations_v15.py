#!/usr/bin/env python3
"""Fetch exact headword records from Kaikki/Wiktionary; never generate pronunciations."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.request import Request, urlopen
from urllib.parse import quote
from pathlib import Path
from collections import Counter
import json, time, hashlib
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
CACHE=ROOT/'tools/data/public_ipa_v15/kaikki'
CACHE.mkdir(parents=True,exist_ok=True)
words=json.loads((ASSETS/'vocabulary.json').read_text()); related=json.loads((ASSETS/'related_pronunciations.json').read_text())
main={w['word']:w for w in words}
us=dict(line.split('\t',1) for line in (CACHE.parent/'en_US.txt').read_text().splitlines() if '\t' in line)
uk=dict(line.split('\t',1) for line in (ROOT/'tools/data/en_UK-972cd67.txt').read_text().splitlines() if '\t' in line)
counts=Counter(x['word'] for w in words for field in ['derivatives','synonyms','confusables'] for x in w[field])
primary=[w['word'] for w in words if not w['pronunciationUk']]
extra=sorted([s for s,r in related.items() if s not in main and s.replace('-','').isalpha() and len(s)<24 and
              ((not r['uk'] and s not in uk) or (not r['us'] and s not in us))],key=lambda s:(-counts[s],len(s),s))[:700]
targets=list(dict.fromkeys(primary+extra))
(CACHE.parent/'kaikki-targets.json').write_text(json.dumps(targets,ensure_ascii=False))
def fetch(word):
    path=CACHE/(hashlib.sha256(word.encode()).hexdigest()+'.json')
    if path.exists():return json.loads(path.read_text())
    url='https://kaikki.org/dictionary/English/meaning/'+quote(word[:1],safe='')+'/'+quote(word[:2],safe='')+'/'+quote(word,safe='')+'.jsonl'
    row={'word':word,'url':url,'wiktionary':'https://en.wiktionary.org/wiki/'+quote(word,safe=''),'retrieved':'2026-10-26','sounds':[]}
    try:
        data=urlopen(Request(url,headers={'User-Agent':'Wordwise-offline-dictionary/2.0.8 (exact-word IPA supplement)'}),timeout=12).read()
        row['sha256']=hashlib.sha256(data).hexdigest()
        for line in data.decode().splitlines():
            entry=json.loads(line)
            if entry.get('lang_code')!='en' or entry.get('word')!=word:continue
            for sound in entry.get('sounds',[]):
                if sound.get('ipa'):row['sounds'].append({k:v for k,v in {'ipa':sound['ipa'],'tags':sound.get('tags',[]),'pos':entry.get('pos',''),'note':sound.get('note','')}.items()})
    except Exception as e:row['error']=str(e)
    path.write_text(json.dumps(row,ensure_ascii=False,separators=(',',':')))
    return row
start=time.monotonic();results=[]
with ThreadPoolExecutor(max_workers=16) as pool:
    tasks=[pool.submit(fetch,w) for w in targets]
    for task in as_completed(tasks):
        results.append(task.result())
        if len(results)%100==0:print({'processed':len(results),'total':len(targets),'withIPA':sum(bool(r['sounds']) for r in results),'seconds':round(time.monotonic()-start)},flush=True)
(CACHE.parent/'kaikki-pronunciations.json').write_text(json.dumps(sorted(results,key=lambda x:x['word']),ensure_ascii=False,indent=2))
print('Completed',len(results),'records;',sum(bool(r['sounds']) for r in results),'with IPA',flush=True)
