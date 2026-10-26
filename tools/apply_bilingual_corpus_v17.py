"""Fill from actual incidental target occurrences, preserving replacement history."""
import copy,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def apply_review(words):
 candidates=json.loads((ROOT/'tools/public_bilingual_corpus_v17.json').read_text())['words'];archive=[];counts={'newExampleWords':0,'filledIdenticalTranslations':0,'replacedUntranslatedItems':0}
 rejected={'She is an enthusiastic gymnastic enthusiast.'}
 for w in words:
  pairs=[e for e in candidates.get(w['word'],[]) if e['en'] not in rejected]
  if not w['examples'] and pairs:
   w['examples']=[copy.deepcopy(pairs[0])];counts['newExampleWords']+=1
  for e in w['examples']:
   if not e['zh']:
    exact=next((p for p in pairs if p['en']==e['en']),None)
    if exact:
     e.update(zh=exact['zh'],translationCredit=exact['attribution'],translationSourceUrl=exact['translationSourceUrl'],translationLicense='CC BY-SA 4.0');counts['filledIdenticalTranslations']+=1
  used={e['en'] for e in w['examples'] if e['zh']}
  for n,e in enumerate(w['examples']):
   if e['zh']:continue
   new=next((p for p in pairs if p['en'] not in used),None)
   if new:
    archive.append({'wordId':w['id'],'original':copy.deepcopy(e),'replacement':new,'reason':'以公开语料中实际出现目标词、且中文译文包含相应义项的完整句对补齐。'})
    w['examples'][n]=copy.deepcopy(new);used.add(new['en']);counts['replacedUntranslatedItems']+=1
  unique={}
  for e in w['examples']:
   if e['en'] not in unique:unique[e['en']]=e
   elif e['zh'] and not unique[e['en']]['zh']:unique[e['en']]=e
  w['examples']=list(unique.values())
 return words,counts,archive
