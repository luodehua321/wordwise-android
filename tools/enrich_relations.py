"""Attested synset peers; homophones and explicitly labelled spelling candidates."""
import tarfile,re
from collections import defaultdict
from difflib import SequenceMatcher

def enrich_relations(words, path):
    indexed={w['word']:w for w in words}
    peers=defaultdict(list)
    with tarfile.open(path) as archive:
        for pos in ['noun','verb','adj','adv']:
            for line in archive.extractfile('dict/data.'+pos).read().decode().splitlines():
                if not line or not line[0].isdigit():continue
                fields,gloss=line.split('|',1);parts=fields.split()
                terms=[re.sub(r'\((?:a|p|ip)\)$','',parts[4+2*i]) for i in range(int(parts[3],16))]
                terms=[x for x in terms if x in indexed]
                sense=gloss.split('; "')[0].strip()
                for word in terms:
                    for other in terms:
                        if word!=other:peers[word].append((other,sense,parts[0]+'-'+pos))
    deletions=defaultdict(set);phones=defaultdict(set)
    for word,w in indexed.items():
        if word.islower():
            for key in {word}|{word[:i]+word[i+1:] for i in range(len(word))}:deletions[key].add(word)
        for ipa in w['pronunciationUs'].split(', '):
            phones[re.sub('[ˈˌ/.]','',ipa)].add(word)
    for word,w in indexed.items():
        seen=set();w['synonyms']=[]
        for other,sense,sourceid in sorted(peers[word],key=lambda x:(indexed[x[0]]['rank'],x[0])):
            if other in seen:continue
            seen.add(other);w['synonyms'].append({'word':other,'meaningZh':indexed[other]['meaningZh'],'senseEn':sense,'source':'Princeton WordNet 3.0 synset '+sourceid,'sourceUrl':'https://wordnet.princeton.edu/','note':'仅对应所列义项，不保证所有语境可互换。'})
            if len(w['synonyms'])==5:break
        existing={x['word'] for x in w['confusables']}
        excluded={word}|{f['word'] for f in w['wordForms']}|{f['word'] for f in w['derivatives']}
        candidates=set()
        for ipa in w['pronunciationUs'].split(', '):candidates.update(phones[re.sub('[ˈˌ/.]','',ipa)])
        homophones=candidates-excluded
        if len(word)>=4 and word.islower():
            for key in {word}|{word[:i]+word[i+1:] for i in range(len(word))}:candidates.update(deletions.get(key,()))
        ranked=[]
        for other in candidates-excluded-existing:
            if other not in indexed:continue
            similarity=SequenceMatcher(None,word,other).ratio()
            if other not in homophones and (len(other)<4 or similarity<.72):continue
            ranked.append((other not in homophones,-similarity,indexed[other]['rank'],other))
        for _,_,_,other in sorted(ranked)[:max(0,3-len(existing))]:
            if other in homophones:
                note='同音对照：美式词典中至少一个读音相同；注意区分拼写及各自词义。'
            else:
                edits=[]
                for op,a,b,c,d in SequenceMatcher(None,word,other).get_opcodes():
                    if op!='equal':edits.append(f'位置{a+1}：{word[a:b] or "∅"} → {other[c:d] or "∅"}')
                note='形近对照（自动筛选，未验证学习者错误率）：'+ '；'.join(edits)+'。请对照两词释义，不能仅凭相似拼写替换。'
            w['confusables'].append({'word':other,'meaningZh':indexed[other]['meaningZh'],'note':note})
