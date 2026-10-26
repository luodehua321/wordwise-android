#!/usr/bin/env python3
"""Android-only additions: authored distinctions and attested, dialect-safe IPA.
Run after fetch_public_pronunciations_v15.py; existing nonempty IPA is preserved.
No headword, level, rank, example or user learning record is modified.
"""
from pathlib import Path
from urllib.parse import quote
from collections import defaultdict,Counter
import json,re,hashlib,copy
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'app/src/main/assets';DATA=ROOT/'tools/data/public_ipa_v15'
def load(p):return json.loads(p.read_text())
def write(p,v):p.write_text(json.dumps(v,ensure_ascii=False,separators=(',',':'))+'\n')
def dictionary(path):return dict(line.split('\t',1) for line in path.read_text().splitlines() if '\t' in line)
def canonical(value):
    return re.sub(r'[\s/\[\]ˈˌ.͡]','',value).replace('ɹ','r').replace('ɫ','l').replace('ɚ','ər').replace('ɝ','ɜr')
def wikipron(accent):
    result=defaultdict(set)
    for word,ipa in (line.split('\t',1) for line in (DATA/f'eng_latn_{accent}_broad.tsv').read_text().splitlines() if '\t' in line):result[word].add(canonical(ipa))
    return result

def public_readings():
    corroboration={a:wikipron(a) for a in ['us','uk']}
    rows=load(DATA/'kaikki-pronunciations.json'); output={};credits=[]
    main={w['word']:w for w in load(ASSETS/'vocabulary.json')}
    posmap={'n':'noun','v':'verb','vt':'verb','vi':'verb','adj':'adj','a':'adj','adv':'adv'}
    for row in rows:
        readings={}
        preferred=re.search(r'\b(n|vt|vi|v|adj|adv|a)\.',main.get(row['word'],{}).get('partOfSpeech',''))
        preferred=posmap.get(preferred.group(1),'') if preferred else ''
        sounds=sorted(row['sounds'],key=lambda s:s.get('pos')!=preferred) if preferred else row['sounds']
        for accent,dialects in [('us',{'US','General-American'}),('uk',{'UK','Received-Pronunciation','British'})]:
            candidates=[]
            for sound in sounds:
                ipa=sound['ipa'];tags=set(sound.get('tags',[]))
                if not re.fullmatch(r'/[^/\[\]\n]+/',ipa):continue
                if tags.intersection({'rare','archaic','obsolete','nonstandard','dialectal'}):continue
                if dialects.intersection(tags):kind='explicit dialect label'
                elif not tags and canonical(ipa) in corroboration[accent].get(row['word'],set()):kind='unlabelled IPA exactly matches dialect-labelled WikiPron'
                else:continue
                candidates.append((sound,kind))
            # Prefer explicit dialect evidence and the headword's first part of speech.
            candidates.sort(key=lambda x:(x[0].get('pos')!=preferred if preferred else False,x[1]!='explicit dialect label'))
            seen=set();chosen=[]
            for sound,kind in candidates:
                ipa=sound['ipa']
                if ipa in seen:continue
                seen.add(ipa);chosen.append(ipa)
                credits.append({'word':row['word'],'accent':accent,'ipa':ipa,'tags':sound.get('tags',[]),'pos':sound.get('pos',''),'match':kind,'sourceUrl':row['url'],'wiktionaryUrl':row['wiktionary'],'sourceSHA256':row.get('sha256',''),'license':'CC-BY-SA-4.0 / Wiktionary contributors'})
                if len(chosen)==4:break
            if chosen:readings[accent]=', '.join(chosen)
        if readings:output[row['word']]=readings
    return output,credits

def main():
    words=load(ASSETS/'vocabulary.json');before=copy.deepcopy(words);index={w['word']:w for w in words}
    curated=[]
    for line in (ROOT/'tools/confusable_pairs_v15.tsv').read_text().splitlines():
        if not line or line.startswith('#'):continue
        a,b,note=line.split('\t');
        if a not in index or b not in index:continue
        curated.append([a,b])
        for head,other in [(a,b),(b,a)]:
            row=next((r for r in index[head]['confusables'] if r['word']==other),None)
            if row is None:
                row={'word':other,'meaningZh':index[other]['meaningZh'],'note':'易错辨析：'+note};index[head]['confusables'].insert(0,row)
            elif row['note'].startswith(('形近对照','同音对照','易错辨析：')):row['note']='易错辨析：'+note
    # Add explicit adjacent-letter spelling comparisons, retaining genuine word meanings.
    shape_pairs='angel angle;dairy diary;trail trial;causal casual;form from;salt slat;cost coast;suit suite;medal metal;aboard abroad;quiet quite;adapt adopt;contact contract;altitude attitude;conservation conversation;canvas canvass;decent descent;decent dissent;persecute prosecute;through thorough;through though;though thought;complement compliment;stationary stationery'.split(';')
    shape_added=0
    for pair in shape_pairs:
        a,b=pair.split()
        if a not in index or b not in index:continue
        for head,other in [(a,b),(b,a)]:
            if any(r['word']==other for r in index[head]['confusables']):continue
            index[head]['confusables'].append({'word':other,'meaningZh':index[other]['meaningZh'],'note':f'形近对照：对照 {head} 与 {other} 的字母顺序和拼写，分别记住两词的意思。'})
            shape_added+=1
    public,credits=public_readings()
    us=dictionary(DATA/'en_US.txt');assert hashlib.sha256((DATA/'en_US.txt').read_bytes()).hexdigest()=='2af6f154a5c363275f052d1f85acedef38ed185ca9745aa4314be77f6b70de67'
    uk=dictionary(ROOT/'tools/data/en_UK-972cd67.txt')
    main_added=Counter()
    for w in words:
        for field,accent in [('pronunciationUs','us'),('pronunciationUk','uk')]:
            if not w[field] and public.get(w['word'],{}).get(accent):w[field]=public[w['word']][accent];main_added[accent]+=1
    related=load(ASSETS/'related_pronunciations.json');related_added=Counter()
    for spelling,row in related.items():
        for accent,field,base in [('us','pronunciationUs',us),('uk','pronunciationUk',uk)]:
            if row[accent]:continue
            value=index.get(spelling,{}).get(field) or base.get(spelling) or public.get(spelling,{}).get(accent,'')
            if value:row[accent]=value;related_added[accent]+=1
    for old,new in zip(before,words):
        assert old['id']==new['id'] and all(old[k]==new[k] for k in old if k not in {'confusables','pronunciationUs','pronunciationUk'})
        for field in ['pronunciationUs','pronunciationUk']:
            if old[field]:assert old[field]==new[field]
        assert len({r['word'] for r in new['confusables']})==len(new['confusables'])
    write(ASSETS/'vocabulary.json',words)
    write(ASSETS/'related_pronunciations.json',related);write(ROOT/'tools/related_pronunciations_v15.json',related)
    payload={'retrieved':'2026-10-26','words':public,'credits':credits,'sources':{'kaikki':'https://kaikki.org/dictionary/English/','wiktionary':'https://en.wiktionary.org/','wikipron':'https://github.com/CUNY-CL/wikipron','license':'CC BY-SA 4.0; original Wiktionary contributors; WikiPron code is separately Apache 2.0'},'sourceFilesSHA256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in DATA.glob('*.tsv')}}
    write(ASSETS/'public_pronunciations_v15.json',payload)
    metadata=load(ASSETS/'dictionary_sources.json');metadata['contentVersion']=18
    metadata['vocabularySha256']=hashlib.sha256((ASSETS/'vocabulary.json').read_bytes()).hexdigest()
    metadata['britishPronunciationWordCount']=sum(bool(w['pronunciationUk']) for w in words)
    metadata['missingBritishPronunciationWordCount']=sum(not w['pronunciationUk'] for w in words)
    metadata['sources']['wiktionaryIPA']={'project':'Wiktionary / Kaikki, with dialect-labelled WikiPron corroboration for untagged readings','url':'https://kaikki.org/dictionary/English/','retrieved':'2026-10-26','license':'CC BY-SA 4.0','licenseUrl':'https://creativecommons.org/licenses/by-sa/4.0/','creditsAsset':'public_pronunciations_v15.json','policy':'Exact spelling, explicit UK/US dialect tag or exact phoneme match to dialect-labelled WikiPron; existing readings unchanged; no inferred pronunciations.'}
    metadata['enhancedCounts']['confusables']=sum(bool(w['confusables']) for w in words)
    report={'wordCount':len(words),'curatedPairs':len(curated),'shapePairsAdded':shape_added,'mainPronunciationsAdded':dict(main_added),'relatedPronunciationsAdded':dict(related_added),'confusableWords':sum(any(not r['note'].startswith('形近对照') for r in w['confusables']) for w in words),'shapeWords':sum(any(r['note'].startswith('形近对照') for r in w['confusables']) for w in words),'shapeItems':sum(r['note'].startswith('形近对照') for w in words for r in w['confusables']),'confusableItems':sum(not r['note'].startswith('形近对照') for w in words for r in w['confusables']),'remainingMainUK':sum(not w['pronunciationUk'] for w in words),'remainingRelatedUS':sum(not r['us'] for r in related.values()),'remainingRelatedUK':sum(not r['uk'] for r in related.values())}
    metadata['androidV15Review']=report
    (ASSETS/'dictionary_sources.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
    (ROOT/'tools/public_enrichment_v15_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
