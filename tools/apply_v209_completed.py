#!/usr/bin/env python3
"""Package only the v209 batches completed before the user stopped further work."""
from pathlib import Path
from collections import Counter,defaultdict
import copy,hashlib,json,shutil
from apply_authored_content_v16 import apply_review as authored
from apply_level_review_v16 import apply_review as levels
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'app/src/main/assets'
def load(p):return json.loads(p.read_text())
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    words=load(ASSETS/'vocabulary.json');before=copy.deepcopy(words);old={w['id']:w for w in before}
    words=authored(words);index={w['id']:w for w in words}
    inputs=['authored_content_v16.json','semantic_example_fixes_v16.json','authored_examples_v16_grade6.json','authored_examples_v16_root_a.json','authored_translations_v16_grade45_b.json','authored_translations_v16_grade6_a.json','level_review_v16.json']
    # Completed semantic replacements, retaining removed originals for traceability.
    archive=[]
    fixes=load(ROOT/'tools/semantic_example_fixes_v16.json')['words']
    for head,patch in fixes.items():
        w=index[head]
        if 'replaceExamples' in patch:
            archive.append({'word':head,'examples':copy.deepcopy(w['examples']),'reason':patch.get('reason','')})
            w['examples']=[{**e,'source':'项目原创语境 v16 · CC0','attribution':'项目原创（CC0）'} for e in patch['replaceExamples']]
        for en,new in patch.get('replaceByEnglish',{}).items():
            for n,e in enumerate(w['examples']):
                if e['en']==en:
                    archive.append({'word':head,'examples':[copy.deepcopy(e)],'reason':new.get('reason','')})
                    w['examples'][n]={k:v for k,v in new.items() if k!='reason'}
                    w['examples'][n].update(source='项目原创语境 v16 · CC0',attribution='项目原创（CC0）')
        for en,zh in patch.get('translationRevisions',{}).items():
            for e in w['examples']:
                if e['en']==en:
                    e.setdefault('originalZh',e['zh']);e['zh']=zh;e['translationCredit']='中文译文：项目校订 v16（CC0）';e['translationLicense']='CC0 1.0'
    for name in ['authored_examples_v16_grade6.json','authored_examples_v16_root_a.json']:
        for head,patch in load(ROOT/'tools'/name)['words'].items():
            w=index[head]
            for e in patch.get('examples',[]):
                if not any(x['en']==e['en'] for x in w['examples']):w['examples'].append(copy.deepcopy(e))
            for field in ['meaningZh','partOfSpeech']:
                if field in patch:w[field]=patch[field]
            for c in patch.get('collocations',[]):
                if not any(x['en']==c['en'] for x in w['collocations']):w['collocations'].append(copy.deepcopy(c))
    # Explicit modern senses already identified during the completed review.
    modern={
        'blockbuster':'n. 非常成功的电影、书籍等；轰动的作品；巨型炸弹',
        'stakeholder':'n. 利益相关者；有利害关系的人或组织；赌金保管者',
        'worldview':'n. 世界观；对世界的总体看法',
        'vinyl':'n. 乙烯基；乙烯基塑料；黑胶唱片\nadj. 乙烯基塑料制的',
        'billing':'n. 开具账单；计费；账单；演出广告中演员的排名\nv. 开账单（bill 的现在分词）',
        'promotional':'adj. 宣传的；促销的；晋升的',
        'sidebar':'n. （网页、应用或版面中的）侧边栏；补充报道；法庭上的席边商议',
    }
    for head,meaning in modern.items():index[head]['meaningZh']=meaning
    translations={}
    for name in ['authored_translations_v16_grade45_b.json','authored_translations_v16_grade6_a.json']:
        translations.update(load(ROOT/'tools'/name)['translations'])
    for patch in fixes.values():translations.update(patch.get('exampleTranslations',{}))
    for w in words:
        for e in w['examples']:
            if not e['zh'] and e['en'] in translations:
                e['zh']=translations[e['en']];e['translationCredit']='中文译文：项目原创复核 v16（CC0）';e['translationLicense']='CC0 1.0'
    # Apply the already identified tighter translations, without altering English.
    for w in words:
        for e in w['examples']:
            if e['en']=='Please stay seated until we reach the terminal.' and e.get('translationCredit','').endswith('v16（CC0）'):e['zh']='到达终点站前，请保持就座。'
            if 'wanted to destroy his tormentors' in e['en'] and e.get('translationCredit','').endswith('v16（CC0）'):e['zh']='他受到了严重的折磨，甚至想毁掉那些折磨他的人。'
    # Reuse a translation only for an exactly identical English sentence.
    donors=defaultdict(list)
    for w in words:
        for e in w['examples']:
            if e['zh']:donors[e['en']].append((w['id'],e))
    for w in words:
        for e in w['examples']:
            ds=donors.get(e['en'],[])
            if not e['zh'] and ds and len({d['zh'] for _,d in ds})==1:
                source,donor=ds[0];e['zh']=donor['zh'];e['translationReuseWordId']=source
                for key in ['translationCredit','translationLicense','translationSourceUrl']:
                    if donor.get(key):e[key]=donor[key]
    # Refresh only inherited full glosses, retaining sense-specific relation notes.
    for w in words:
        for field in ['derivatives','confusables','synonyms']:
            for item in w[field]:
                head=item['word']
                if head in index and head in old and item['meaningZh']==old[head]['meaningZh']:
                    item['meaningZh']=index[head]['meaningZh']
    levels(words)
    assert len(words)==len(before)==28167
    assert [w['id'] for w in words]==[w['id'] for w in before]
    for w in words:
        assert w['meaningZh'] and w['partOfSpeech'] and 1<=w['difficulty']<=10
        assert len(w['examples'])<=2 and len(w['collocations'])<=4,w['id']
        assert all(e['en'] and isinstance(e['zh'],str) for e in w['examples']),w['id']
        assert all(w[k]==old[w['id']][k] for k in ['word','rank','pronunciationUs','pronunciationUk','wordForms'])
    payload=json.dumps(words,ensure_ascii=False,separators=(',',':'))+'\n'
    (ASSETS/'vocabulary.json').write_text(payload)
    m=load(ASSETS/'dictionary_sources.json');m['contentVersion']=19;m['vocabularySha256']=hashlib.sha256(payload.encode()).hexdigest()
    m['difficultyCounts']=dict(sorted(Counter(w['difficulty'] for w in words).items()))
    m['enhancedCountsByDifficulty']={str(d):sum(bool(w['examples']) for w in words if w['difficulty']==d) for d in range(1,11)}
    m['enhancedCounts']={f:sum(bool(w[f]) for w in words) for f in ['examples','collocations','derivatives','confusables','wordForms']}
    for field,key in [('examples','exampleItemCount'),('collocations','collocationItemCount'),('derivatives','derivativeItemCount'),('wordForms','wordFormItemCount')]:m[key]=sum(len(w[field]) for w in words)
    m['bilingualExampleWordCount']=sum(any(e['zh'] for e in w['examples']) for w in words)
    m['englishOnlyExampleWordCount']=sum(bool(w['examples']) and not any(e['zh'] for e in w['examples']) for w in words)
    m['completeCoreWordCount']=sum(all(w[f] for f in ['examples','collocations','derivatives','confusables']) for w in words)
    description='学习顺序v5：在原分级基础上对504个具体词复核，修正日常词误判过难、同义英美拼写不一致及词族惩罚；保留同形异义词差别。这是项目内部学习顺序，不是CEFR或考试成绩。'
    m['difficultyDescriptionZh']=description;m['levelMethod'].update(version='learning-order-v5',descriptionZh=description,reviewInput='tools/level_review_v16.json',explicitReviewCount=504)
    m['relatedPronunciations']['sha256']=digest(ASSETS/'related_pronunciations.json')
    m['relatedPronunciations']['source']='既有词库及v15公开读音补充，保留实际口音与逐词来源。'
    report={'version':'2.0.9','scope':'Completed batches only; user stopped further translation and review','wordCount':len(words),'regradedWords':sum(w['difficulty']!=old[w['id']]['difficulty'] for w in words),'wordsChanged':sum(w!=old[w['id']] for w in words),'newExampleItems':sum(max(0,len(w['examples'])-len(old[w['id']]['examples'])) for w in words),'authoredExampleItems':sum(e.get('source')=='项目原创语境 v16 · CC0' for w in words for e in w['examples']),'newTranslatedItems':sum(not e['zh'] for w in before for e in w['examples'])-sum(not e['zh'] for w in words for e in w['examples']),'newCollocations':sum(len(w['collocations'])-len(old[w['id']]['collocations']) for w in words),'missingExampleWords':sum(not w['examples'] for w in words),'untranslatedExampleItems':sum(not e['zh'] for w in words for e in w['examples']),'inputs':inputs}
    m['androidV16CompletedReview']=report
    for name in inputs:m.setdefault('originalInputSha256',{})[name]=digest(ROOT/'tools'/name)
    (ASSETS/'dictionary_sources.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
    for name in ['vocabulary_levels.py','level_review_v16.json']:
        shutil.copy2(ROOT/'tools'/name,ASSETS/'level_sources'/name)
    out=ROOT/'dist/validation-v2.0.9';out.mkdir(parents=True,exist_ok=True)
    (out/'completed-changes.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    (out/'removed-examples.json').write_text(json.dumps(archive,ensure_ascii=False,indent=2)+'\n')
    notice=ASSETS/'dictionary_licenses.txt';text=notice.read_text();marker='\n\n===== PROJECT AUTHORED V16 COMPLETED BATCHES =====\n'
    if marker not in text:notice.write_text(text+marker+'New English examples, Chinese translations, collocations and gloss revisions in the completed v16 batches are project-authored contributions provided under CC0 1.0. Existing English examples retain their original credits and licenses; project translations have a separate translationCredit and translationLicense. Original examples replaced for lexical mismatch are retained in the source review archives.\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
