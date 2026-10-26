#!/usr/bin/env python3
"""Recover incidental-word examples from the entire licensed bilingual corpus.

The target must occur literally (or as an already reviewed inflection), and its
Chinese sense must be present in the sentence translation. No word substitution.
"""
from pathlib import Path
from collections import defaultdict,Counter
import gzip,json,re,hashlib
from apply_internal_review import tokens as word_tokens
ROOT=Path(__file__).resolve().parents[1]
WORDS=json.loads(gzip.decompress((ROOT/'tools/data/v210-before-incidental-corpus.json.gz').read_bytes()))
targets=[w for w in WORDS if not w['examples'] or any(not e['zh'] for e in w['examples'])]
lookup=defaultdict(list);byid={w['id']:w for w in targets};senses={}
for w in targets:
 for token in {w['word'].lower()}|{f['word'].lower() for f in w['wordForms']}:
  if re.fullmatch('[a-z]+',token):lookup[token].append(w['id'])
 # Avoid cross-punctuation bigrams and grammatical boilerplate.
 parts=[p.strip() for p in re.split(r'[,，;；\n()（）]',w['meaningZh'])]
 phrases={g for p in parts for g in re.findall(r'[\u3400-\u9fff]{2,}',p) if g not in {'形容词','副词','动词','名词','过去式','过去分词','现在分词','现在','复数','第三人称单数','过去','某人','某物','有关','表示','进行','具有','一种','一个'}}
 excluded={'现在','过去','分词','第三','单数','复数','缩写','变形','形式','用法','词形','表示','有关','进行','具有'}
 stems={p[i:i+2] for p in phrases for i in range(len(p)-1) if not set(p[i:i+2])&set('的地得着了过和与或及是有无使把被不者人一某可能') and p[i:i+2] not in excluded}
 senses[w['id']]=phrases|stems
chosen=defaultdict(list);counts=Counter()
source=ROOT/'tools/data/open-dictionary-v2.0.jsonl.gz'
with gzip.open(source,'rt') as f:
 for line in f:
  row=json.loads(line)
  for group in row['pos_groups']:
   for sense in group['meanings']:
    if set(sense['labels'])&{'archaic','obsolete','nonstandard','rare','dialectal'}:continue
    for pair in sense['examples']:
     en=(pair['text'] or '').strip();zh=(pair['translation'] or '').strip()
     if not en or not zh or not en[0].isupper() or not en.endswith(('.', '?', '!')):continue
     if not 5<=len(en.split())<=28 or any(c in en for c in ['\n','[',']','…']):continue
     tokens=word_tokens(en)
     for wordid in {h for token in tokens for h in lookup.get(token,[])}:
      matched={g for g in senses[wordid] if g in zh}
      if not matched:continue
      score=(max(map(len,matched)),len(matched),wordid.lower() in tokens,-len(en))
      item={'en':en,'zh':zh,'source':'Open Dictionary v2.0 双语语料中的实际用词（机器扩写）','attribution':'ahpxex/open-dictionary contributors and Wiktionary contributors · CC BY-SA 4.0','sourceUrl':'https://github.com/ahpxex/open-dictionary/releases/tag/v2.0','translationSourceUrl':'https://github.com/ahpxex/open-dictionary/releases/tag/v2.0','licenseUrl':'https://creativecommons.org/licenses/by-sa/4.0/','sourceEntryId':row['entry_id'],'sourceSenseId':sense['sense_id'],'sourceHeadword':row['headword'],'sourceHeadwordUrl':'https://en.wiktionary.org/wiki/'+row['headword'],'sourceSnapshotSHA256':'95026a4b78aa83f1f32481e8ff30ea3f7d942b648b3d6e66ef64ca3a194cb66e','selectionEvidence':{'literalTarget':wordid,'matchingChinesePhrases':sorted(matched)},'generationDisclosure':'Wiktionary-grounded model-expanded public dataset; incidental target token and translated sense selected by project.'}
      existing=chosen[wordid]
      if any(x[1]['en']==en for x in existing):continue
      existing.append((score,item));existing.sort(key=lambda x:x[0],reverse=True);del existing[3:]
      counts['candidatePairs']+=1
result={'license':'CC BY-SA 4.0','source':'https://github.com/ahpxex/open-dictionary/releases/tag/v2.0','sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'words':{h:[x[1] for x in values] for h,values in chosen.items()}}
(ROOT/'tools/public_bilingual_corpus_v17.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('matched words',len(chosen),'missing-example words',sum(not byid[h]['examples'] for h in chosen),'candidate pairs',counts['candidatePairs'])
