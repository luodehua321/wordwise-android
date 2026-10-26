#!/usr/bin/env python3
"""Select complete, sense-matched pairs from CC-BY-SA Open Dictionary v2.0.

This is a secondary, machine-expanded Wiktionary dataset, not a human reviewed
dictionary. Keep its provenance explicit and do not treat unused fields as facts.
"""
import copy,json,re
from pathlib import Path
from collections import Counter,defaultdict
ROOT=Path(__file__).resolve().parents[1]
SOURCE='https://github.com/ahpxex/open-dictionary/releases/tag/v2.0'
LICENSE='https://creativecommons.org/licenses/by-sa/4.0/'
SHA='95026a4b78aa83f1f32481e8ff30ea3f7d942b648b3d6e66ef64ca3a194cb66e'
def grams(s):
 s=re.sub('[^\u3400-\u9fff]','',s or '')
 return {s[i:i+2] for i in range(len(s)-1)}
def positions(w):
 mapping={'n':'noun','v':'verb','vi':'verb','vt':'verb','a':'adj','adj':'adj','adv':'adv','pron':'pron','prep':'prep','conj':'conj','int':'intj','num':'num','aux':'verb'}
 return {mapping[x] for x in re.findall(r'\b(n|v|vi|vt|a|adj|adv|pron|prep|conj|int|num|aux)\.',w['partOfSpeech'])}
def candidates(w,row,inflected=False):
 result=[];pos=positions(w)
 for group in row['pos_groups']:
  if group['pos'] not in pos or group.get('proper_name') and not w['word'][:1].isupper():continue
  tokens={w['word'].lower()}|{f['word'].lower() for f in w['wordForms']}
  # Use this app's reviewed inflection list. A template form in another POS must
  # not introduce rare denominal verbs such as "gulfed" as ordinary gulf usage.
  for s in group['meanings']:
   if set(s['labels'])&{'archaic','obsolete','nonstandard','rare','dialectal','uncommon','proscribed'}:continue
   overlap=grams((s['short_gloss'] or '')+'；'+(s['learner_explanation'] or ''))&grams(w['meaningZh'])
   if not overlap:continue
   for e in s['examples']:
    en=e['text'].strip();zh=e['translation'].strip()
    if not zh or not re.search('[\u3400-\u9fff]',zh) or not 4<=len(en.split())<=32:continue
    if not en[:1].isupper() or not en.endswith(('.', '?', '!')) or any(c in en for c in ['\n','[',']','…']):continue
    if not tokens.intersection(re.findall(r"[a-z]+(?:['’][a-z]+)?",en.lower())):continue
    # Prefer the headword literally, direct translated sense and a short context.
    score=(len(overlap),bool(grams(zh)&grams(s['short_gloss'])),w['word'].lower() in re.findall('[a-z]+',en.lower()),s['priority']=='core',-len(en))
    item={'en':en,'zh':zh,'source':'Open Dictionary v2.0（基于维基词典的机器扩写）','attribution':'ahpxex/open-dictionary contributors and Wiktionary contributors · CC BY-SA 4.0','sourceUrl':SOURCE,'translationSourceUrl':SOURCE,'licenseUrl':LICENSE,'sourceEntryId':row['entry_id'],'sourceSenseId':s['sense_id'],'sourcePos':group['pos'],'sourceSnapshotSHA256':SHA,'sourceHeadwordUrl':'https://en.wiktionary.org/wiki/'+row['headword'],'generationDisclosure':'Public dataset: Wiktionary-grounded model-generated learner content.'}
    result.append((score,item))
 result.sort(key=lambda x:x[0],reverse=True)
 seen=set();pairs=[]
 for _,item in result:
  if item['en'] not in seen:pairs.append(item);seen.add(item['en'])
 return pairs
def apply_review(words):
 rows=json.loads((ROOT/'tools/data/open-dictionary-v2.0-matched.json').read_text());public={r['headword']:r for r in rows}
 inflections=defaultdict(list)
 for row in rows:
  for group in row['pos_groups']:
   for form in group['forms']:
    if set(form['tags'])&{'past','present','participle','plural'} and not set(form['tags'])&{'obsolete','archaic','rare','nonstandard','dialectal'}:
     inflections[form['text']].append(row)
 counts=Counter();archive=[];pronunciations=defaultdict(dict)
 for w in words:
  row=public.get(w['word'])
  pairs=candidates(w,row) if row else []
  if not pairs:
   for base in inflections.get(w['word'],[]):
    if base['headword']!=w['word']:pairs.extend(candidates(w,base,inflected=True))
  if not w['examples'] and pairs:
   w['examples']=[copy.deepcopy(pairs[0])];counts['newBilingualExampleWords']+=1
  # Replace a previously untranslated example with an independently complete
  # sense-matched bilingual context; never attach an unrelated translation.
  used={e['en'] for e in w['examples'] if e['zh']}
  for n,e in enumerate(w['examples']):
   if e['zh']:continue
   new=next((p for p in pairs if p['en'] not in used),None)
   if new:
    archive.append({'wordId':w['id'],'original':copy.deepcopy(e),'replacement':new,'reason':'旧例句缺中文；改用词性和中文义项匹配的完整公开双语语境，而非拼接不相干译文。'})
    w['examples'][n]=copy.deepcopy(new);used.add(new['en']);counts['replacedUntranslatedExamples']+=1
  unique={}
  for e in w['examples']:
   prior=unique.get(e['en'])
   if prior is None:unique[e['en']]=e
   elif not e['zh'] and prior['zh']:
    archive.append({'wordId':w['id'],'original':copy.deepcopy(e),'replacement':copy.deepcopy(prior),'reason':'相同英文已有完整公开双语内容，合并重复项。'})
    counts['consolidatedDuplicateExamples']+=1
   elif e['zh'] and not prior['zh']:unique[e['en']]=e
  w['examples']=list(unique.values())
  for group in (row['pos_groups'] if row else []):
   if group['pos'] not in positions(w):continue
   # Do not harvest an archaic POS group's pronunciation for a modern homograph.
   if not any(not set(s['labels'])&{'archaic','obsolete','nonstandard','rare','dialectal'} for s in group['meanings']):continue
   for pron in group['pronunciations']:
    ipa=pron.get('ipa') or '';tags=set(pron.get('tags',[]))
    if not re.fullmatch(r'/[^/\[\]\n]+/',ipa) or tags&{'archaic','obsolete','nonstandard','rare','dialectal'}:continue
    for accent,accepted in [('us',{'US','General-American'}),('uk',{'UK','Received-Pronunciation','British'})]:
     if tags&accepted:pronunciations[w['word']].setdefault(accent,[])
     if tags&accepted and ipa not in pronunciations[w['word']][accent]:pronunciations[w['word']][accent].append(ipa)
  for accent,field in [('us','pronunciationUs'),('uk','pronunciationUk')]:
   if not w[field] and pronunciations[w['word']].get(accent):
    w[field]=', '.join(pronunciations[w['word']][accent][:4]);counts['mainIPA_'+accent]+=1
 return words,pronunciations,dict(counts),archive

def relations_review(words):
 """Attested word-family links and shared-sense synonyms, only into empty lists."""
 rows=json.loads((ROOT/'tools/data/open-dictionary-v2.0-matched.json').read_text());index={w['word']:w for w in words};credits=[]
 for row in rows:
  w=index.get(row['headword'])
  if not w:continue
  derived=[];synonyms=[]
  for g in row['pos_groups']:
   if g['pos'] not in positions(w):continue
   if not any(not set(s['labels'])&{'archaic','obsolete','nonstandard','rare','dialectal'} for s in g['meanings']):continue
   forms={f['text'] for f in g['forms']}
   for link in g['relations']:
    name=link['word'];target=index.get(name)
    if not target or name==w['word'] or link.get('lang_code') not in (None,'en'):continue
    if link['type']=='derived_term' and name not in forms and ' ' not in name:
     # A morphological family must retain a substantial stem, not just be topical.
     stem=w['word'].lower().rstrip('e')
     if len(stem)>=4 and stem in name.lower():derived.append({'word':name,'meaningZh':target['meaningZh']})
    elif link['type']=='synonym' and positions(target)&{g['pos']} and grams(target['meaningZh'])&grams(w['meaningZh']):
     synonyms.append({'word':name,'meaningZh':target['meaningZh'],'senseEn':'公开词典同义关联；具体使用须结合语境'})
  for field,items in [('derivatives',derived),('synonyms',synonyms)]:
   if w[field]:continue
   seen=set()
   for item in items:
    if item['word'] in seen:continue
    w[field].append(item);seen.add(item['word'])
    credits.append({'word':w['word'],'field':field,'value':item,'source':SOURCE,'sourceEntryId':row['entry_id'],'license':'CC BY-SA 4.0','attribution':'ahpxex/open-dictionary and Wiktionary contributors','generationDisclosure':'Wiktionary-grounded model-expanded public dataset; selected and integrated by this project.'})
    if len(w[field])==4:break
 return words,credits
if __name__=='__main__':
 words=json.loads((ROOT/'app/src/main/assets/vocabulary.json').read_text());words,_,counts,_=apply_review(words)
 print(counts);print('missing',sum(not w['examples'] for w in words),'untranslated',sum(not e['zh'] for w in words for e in w['examples']))
