#!/usr/bin/env python3
"""Merge attested public bilingual examples and IPA without changing learner IDs."""
import json,re,copy,hashlib
from pathlib import Path
from collections import defaultdict,Counter
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'app/src/main/assets'
def load(p):return json.loads(p.read_text())
def ideograms(s):
 s=re.sub('[^\u3400-\u9fff]','',s)
 return {s[i:i+2] for i in range(len(s)-1)}
def bilingual_candidates(words,public):
 index={w['word']:w for w in words};candidates=defaultdict(list)
 posmap={'n':'noun','v':'verb','vt':'verb','vi':'verb','a':'adj','adj':'adj','adv':'adv','pron':'pron','prep':'prep','conj':'conj'}
 for row in public['records']:
  head=row['word'];w=index.get(head)
  if not w:continue
  pos={posmap.get(t,t) for t in re.findall(r'\b(n|v|vt|vi|a|adj|adv|pron|prep|conj)\.',w['partOfSpeech'])}
  if row['pos'] not in pos:continue
  forms={w['word'].lower()}|{f['word'].lower() for f in w['wordForms']}
  for sense in row['senses']:
   if set(sense['tags'])&{'archaic','obsolete','rare','nonstandard'}:continue
   if not any(ideograms(g)&ideograms(w['meaningZh']) for g in sense['glosses']):continue
   for e in sense['examples']:
    ref=e.get('ref','')
    if ref:
     year=re.match(r'^\s*(\d{4})\b',ref)
     if not year or int(year[1])>1900:continue
    en=e['text'].strip();zh=e['translation'].strip()
    if not forms.intersection(set(re.findall(r"[a-z]+(?:['’][a-z]+)?",en.lower()))):continue
    if '[' in en or '…' in en or '\n' in en or len(en.split())>30:continue
    if not en[:1].isupper() or not en.endswith(('.', '?', '!')):continue
    item={'en':en,'zh':zh,'source':'中文维基词典 / Kaikki 公开双语语境','attribution':'Wiktionary contributors · CC BY-SA 4.0','sourceUrl':row['sourceUrl'],'translationSourceUrl':row['sourceUrl'],'licenseUrl':'https://creativecommons.org/licenses/by-sa/4.0/','sourceSnapshotSHA256':public['snapshotSHA256']}
    if ref:item['originalReference']=ref
    candidates[head].append(item)
 return candidates

def apply_review(words,public=None):
 public=public or load(ROOT/'tools/public_content_v17.json');words=copy.deepcopy(words);cs=bilingual_candidates(words,public)
 readings=defaultdict(lambda:defaultdict(list));counts=Counter()
 for row in public['records']:
  for sound in row['sounds']:
   ipa=sound['ipa'];tags=set(sound.get('tags',[]))
   raw=' '.join(sound.get('raw_tags',[]))
   if '英國' in raw or '英式' in raw:tags.add('UK')
   if '美國' in raw or '美式' in raw:tags.add('US')
   if not re.fullmatch(r'/[^/\[\]\n]+/',ipa):continue
   if tags&{'obsolete','archaic','rare','nonstandard','dialectal'}:continue
   for accent,accepted in [('us',{'US','General-American'}),('uk',{'UK','Received-Pronunciation','British'})]:
    if tags&accepted and ipa not in readings[row['word']][accent]:readings[row['word']][accent].append(ipa)
 donors=defaultdict(list)
 for c in cs.values():
  for item in c:donors[item['en']].append(item)
 for w in words:
  if not w['examples'] and cs[w['word']]:
   # One complete semantic example suffices; do not displace existing content.
   w['examples']=[min(cs[w['word']],key=lambda e:(bool(e.get('originalReference')),len(e['en'])))];counts['newBilingualExampleWords']+=1
  for e in w['examples']:
   ds=donors.get(e['en'],[])
   if not e['zh'] and ds and len({d['zh'] for d in ds})==1:
    d=ds[0];e['zh']=d['zh'];e['translationCredit']='Wiktionary contributors · CC BY-SA 4.0';e['translationLicense']='CC BY-SA 4.0';e['translationSourceUrl']=d['sourceUrl'];counts['publicTranslatedExamples']+=1
  for field,accent in [('pronunciationUs','us'),('pronunciationUk','uk')]:
   if not w[field] and readings[w['word']][accent]:w[field]=', '.join(readings[w['word']][accent][:4]);counts['mainIPA_'+accent]+=1
 return words,readings,dict(counts)
if __name__=='__main__':
 words=load(ASSETS/'vocabulary.json');result,readings,counts=apply_review(words)
 print(json.dumps(counts,ensure_ascii=False))
 print('Example samples',[(w['word'],w['examples'][0]['en'],w['examples'][0]['zh']) for w,b in zip(result,words) if not b['examples'] and w['examples']][:14])


def attested_missing_forms(words,public):
    """Only fill an empty inflection list from a matching, current source sense."""
    index={w['word']:w for w in words};chosen={};credits=[]
    for row in public['records']:
        head=row['word'];word=index.get(head)
        if not word or word['wordForms'] or head in chosen:continue
        senses=[s for s in row['senses'] if not set(s['tags'])&{'obsolete','archaic','rare','nonstandard'} and any(ideograms(g)&ideograms(word['meaningZh']) for g in s['glosses'])]
        if not senses:continue
        pos=row['pos'];candidates=[]
        if pos not in {'verb','adj','noun'}:continue
        if pos=='verb' and not re.search(r'\b(?:v|vt|vi)\.',word['partOfSpeech']):continue
        if pos=='adj' and not re.search(r'\b(?:a|adj)\.',word['partOfSpeech']):continue
        if pos=='noun' and ('n.' not in word['partOfSpeech'] or not any('countable' in s['tags'] for s in senses)):continue
        for form in row.get('forms',[]):
            tags=set(form.get('tags',[]));spelling=form.get('form','').strip();label=''
            if not spelling or spelling==head or tags&{'alternative','obsolete','archaic','rare','uncommon','dated','dialectal','abbreviation','possessive'}:continue
            if pos=='verb':
                if {'present','participle'}<=tags:label='现在分词'
                elif {'past','participle'}<=tags:label='过去分词'
                elif 'past' in tags:label='过去式'
                elif {'third-person','singular'}<=tags:label='第三人称单数'
            elif pos=='adj':
                if 'comparative' in tags:label='比较级'
                elif 'superlative' in tags:label='最高级'
            elif pos=='noun' and 'plural' in tags:label='复数（可数义）'
            if label and re.fullmatch(r"[A-Za-z][A-Za-z '-]*",spelling):
                existing=next((x for x in candidates if x['word']==spelling),None)
                if existing:
                    if label not in existing['meaningZh']:existing['meaningZh']+=' / '+label
                else:candidates.append({'word':spelling,'meaningZh':label})
                credits.append({'word':head,'form':spelling,'label':label,'tags':sorted(tags),'sourceUrl':row['sourceUrl'],'license':'CC BY-SA 4.0'})
        if candidates:chosen[head]=candidates[:6]
    return chosen,credits
