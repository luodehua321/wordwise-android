#!/usr/bin/env python3
"""Extract exact English headwords from the Chinese Wiktionary public snapshot."""
from pathlib import Path
from collections import Counter
from urllib.parse import quote
import gzip,json,re,hashlib
ROOT=Path(__file__).resolve().parents[1];SRC=ROOT/'tools/data/zh-wiktionary-2026-10-26.jsonl.gz'
words=json.loads((ROOT/'app/src/main/assets/vocabulary.json').read_text());names={w['word'] for w in words}
names.update(json.loads((ROOT/'app/src/main/assets/related_pronunciations.json').read_text()))
records=[];count=Counter()
with gzip.open(SRC,'rt') as stream:
 for line in stream:
  row=json.loads(line)
  if row.get('lang_code')!='en' or row.get('word') not in names:continue
  word=row['word'];count['matchedEntries']+=1
  senses=[]
  for sense in row.get('senses',[]):
   examples=[]
   for example in sense.get('examples',[]):
    count['exampleItems']+=1
    text=example.get('text','');translation=example.get('translation','')
    if translation:count['withTranslation']+=1
    if not text or not translation or not re.search('[\u3400-\u9fff]',translation):continue
    if len(text)>260 or len(text.split())>40 or len(text.split())<4:continue
    if example.get('ref'):
     year=re.search(r'\b(1[0-8]\d\d|1900)\b',example['ref'])
     if not year:continue
    count['usableBilingual']+=1
    examples.append({k:v for k,v in example.items() if k in ['text','translation','ref','type']})
   senses.append({'glosses':sense.get('glosses',[]),'tags':sense.get('tags',[]),'examples':examples,'synonyms':sense.get('synonyms',[])})
  records.append({'word':word,'pos':row.get('pos',''),'senses':senses,'sounds':[s for s in row.get('sounds',[]) if s.get('ipa')], 'forms':row.get('forms',[]),'derived':row.get('derived',[]),'synonyms':row.get('synonyms',[]), 'sourceUrl':'https://zh.wiktionary.org/wiki/'+quote(word,safe='')})
output={'source':'Chinese Wiktionary via Kaikki/Wiktextract','url':'https://kaikki.org/dictionary/rawdata.html','license':'CC BY-SA 4.0','retrieved':'2026-10-26','snapshotSHA256':hashlib.sha256(SRC.read_bytes()).hexdigest(),'records':records,'counts':dict(count)}
p=ROOT/'tools/public_content_v17.json';p.write_text(json.dumps(output,ensure_ascii=False,separators=(',',':'))+'\n')
print(json.dumps(count),flush=True)
print('Words with usable bilingual:',len({r['word'] for r in records if any(s['examples'] for s in r['senses'])}),flush=True)
print('Samples',[(r['word'],s['examples'][0]) for r in records for s in r['senses'] if s['examples']][:5],flush=True)
