#!/usr/bin/env python3
"""Validate/apply the authored v16 batch in memory; never mutate Android assets by default.

Existing English sentences retain their original sources. Missing translations
have a separate CC0 project credit. Removed examples stay in the authored review
archive; they must not be appended as learning examples.
"""
import copy
import json
import re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
INPUT=ROOT/'tools/authored_content_v16.json'

def apply_review(words, review=None):
    review=review or json.loads(INPUT.read_text())
    result=copy.deepcopy(words)
    index={w['id']:w for w in result}
    for word_id, patch in review['words'].items():
        assert word_id in index,word_id
        word=index[word_id]
        for english,chinese in patch.get('exampleTranslations',{}).items():
            found=[x for x in word['examples'] if x['en']==english]
            assert len(found)==1,(word_id,english)
            item=found[0]
            if item['zh']:
                # A concurrently merged public translation takes precedence over
                # this optional authored fill, avoiding a silent overwrite.
                continue
            item['zh']=chinese
            item['translationCredit']='中文译文：项目原创复核 v16（CC0）'
            item['translationLicense']='CC0 1.0'
        if 'replaceExamples' in patch:
            word['examples']=copy.deepcopy(patch['replaceExamples'])
        for field in ['meaningZh','partOfSpeech']:
            if field in patch:word[field]=patch[field]
        for item in patch.get('collocations',[]):
            if not any(old['en']==item['en'] for old in word['collocations']):
                word['collocations'].append(copy.deepcopy(item))
        assert len(word['examples'])<=2,(word_id,'too many examples')
        assert len(word['collocations'])<=4,(word_id,'too many collocations')
        for item in word['examples']:
            assert item.get('en') and item.get('zh') is not None
        for item in patch.get('collocations',[]):
            assert re.search(r"(?<![a-z])"+re.escape(word['word'].lower())+r"(?![a-z])",item['en'].lower()),(word_id,item['en'])
    return result

def coverage(words):
    return {str(level):{
        'words':sum(w['difficulty']==level for w in words),
        'missingExamples':sum(not w['examples'] for w in words if w['difficulty']==level),
        'missingAnyBilingual':sum(not any(e['zh'] for e in w['examples']) for w in words if w['difficulty']==level),
        'untranslatedItems':sum(not e['zh'] for w in words if w['difficulty']==level for e in w['examples']),
        'missingCollocations':sum(not w['collocations'] for w in words if w['difficulty']==level),
    } for level in range(1,11)}

def main():
    words=json.loads((ROOT/'app/src/main/assets/vocabulary.json').read_text())
    review=json.loads(INPUT.read_text())
    result=apply_review(words,review)
    original={w['id']:w for w in words}
    for word in result:
        old=original[word['id']]
        for field in ['id','word','difficulty','rank','pronunciationUs','pronunciationUk','wordForms','derivatives','synonyms','confusables']:
            assert word[field]==old[field],(word['id'],field)
    assert result==apply_review(result,review),'Authored patch must be idempotent'
    authored=sum(e.get('source')=='项目原创语境 v16 · CC0' for p in review['words'].values() for e in p.get('replaceExamples',[]))
    translated=sum(e.get('translationCredit')=='中文译文：项目原创复核 v16（CC0）' for w in result for e in w['examples'])
    report={
        'patchWords':len(review['words']),
        'explicitTranslationMappings':sum(len(p.get('exampleTranslations',{})) for p in review['words'].values()),
        'authoredExampleItems':authored,
        'displayedAuthoredTranslations':translated,
        'authoredCollocations':sum(len(p.get('collocations',[])) for p in review['words'].values()),
        'replacedExampleEntries':sum('replaceExamples' in p for p in review['words'].values()),
        'meaningUpdatedEntries':sum('meaningZh' in p for p in review['words'].values()),
        'removedExamplesArchived':sum(len(p.get('removedExamples',[])) for p in review['words'].values()),
        'before':coverage(words),'after':coverage(result),
    }
    (ROOT/'tools/authored_content_v16_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ['before','after']},ensure_ascii=False))
    print('Verified in memory; Android/iOS assets were not modified.')

if __name__=='__main__':main()
