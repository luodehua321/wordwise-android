#!/usr/bin/env python3
"""Traverse every shipped record; write changes, checks and honest coverage gaps.

This is a source/structural audit, not a claim of 25k manually verified senses.
"""
import argparse
import csv
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from build_vocabulary import validate, learning_rows, clean_meaning, part_of_speech
from vocabulary_levels import assign_levels, OVERRIDES, NGSL

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ['pronunciationUk', 'examples', 'collocations', 'derivatives', 'confusables', 'wordForms']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ecdict', type=Path, required=True)
    p.add_argument('--before', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    old = {w['id']: w for w in json.loads(args.before.read_text())}
    words = json.loads((ROOT / 'app/src/main/assets/vocabulary.json').read_text())
    validate(words)
    current = {w['id']:w for w in words}
    assert len(current) == len(words)
    assert old.keys() <= current.keys(), 'update must not delete learning IDs'
    rows = {r['word']:r for r in learning_rows(args.ecdict)}
    levels = assign_levels(words, rows)
    review = json.loads((ROOT / 'tools/content_review_v10.json').read_text())
    result = []
    for w in words:
        tokens = {w['word'].lower()} | {f['word'].lower() for f in w['wordForms']}
        for example in w['examples']:
            assert tokens.intersection(re.findall(r"[a-z]+(?:'[a-z]+)?",example['en'].lower())), (w['id'], example['en'])
        assert not re.search(r'\\[nrt]|\ufffd', w['meaningZh']), w['id']
        assert re.search('[\u4e00-\u9fff]',w['meaningZh'])
        assert w['word'] == w['word'].strip()
        for field, identity in [('examples','en'),('collocations','en'),('derivatives','word'),('confusables','word'),('wordForms','word')]:
            values = [item[identity] for item in w[field]]
            assert len(values) == len(set(values)), (w['id'], field, 'duplicate')
        before = old.get(w['id'])
        changes = [k for k in w if before and before.get(k) != w[k]]
        missing = [field for field in FIELDS if not w[field]]
        if not any(x['zh'] for x in w['examples']): missing.append('bilingualExamples')
        result.append({'word':w['word'], 'oldLevel':before['difficulty'] if before else None,
            'level':w['difficulty'], 'levelEvidence':levels[w['word']]['evidence'],
            'new':before is None, 'changedFields':changes, 'missing':missing,
            'commonSenseReviewed':w['word'].lower() in OVERRIDES,
            'contentReviewed':w['word'] in review,
            'checksPassed':['required-fields','unique-id','valid-ipa-structure','example-word-match',
                            'no-duplicate-details','level-recomputed','no-escaped-newline']})
    summary={'beforeCount':len(old),'afterCount':len(words),'retainedOriginalIds':len(old),
        'addedCount':sum(r['new'] for r in result),'reclassifiedCount':sum(bool(r['oldLevel']) and r['oldLevel']!=r['level'] for r in result),
        'changedMeaningCount':sum('meaningZh' in r['changedFields'] for r in result),
        'changedPosCount':sum('partOfSpeech' in r['changedFields'] for r in result),
        'changedFormCount':sum('wordForms' in r['changedFields'] for r in result),
        'changedUsPronunciationCount':sum('pronunciationUs' in r['changedFields'] for r in result),
        'newUkPronunciationCount':sum(bool(w['pronunciationUk']) and not old.get(w['id'],{}).get('pronunciationUk') for w in words),
        'levelCounts':dict(sorted(Counter(w['difficulty'] for w in words).items())),
        'reviewedCommonSenseWords':sum(r['commonSenseReviewed'] for r in result),
        'reviewedContentWords':sum(r['contentReviewed'] for r in result),
        'covered':{k:sum(bool(w[k]) for w in words) for k in FIELDS},
        'bilingualExampleWords':sum(any(x['zh'] for x in w['examples']) for w in words),
        'remainingMissing':dict(Counter(k for r in result for k in r['missing']))}
    report={'summary':summary,'limitations':['所有词均完成规则检查和等级重算，不代表所有词义与译文已经逐条人工审核。',
       '没有常见派生、易混词或词形变化的词可以合理留空；这些缺口并非全部为数据错误。',
       '无可靠资料的英国发音和中文例句不填模板；缺口逐词保留。',
       '未出现在NGSL或主题复核中的词仍主要依靠词频和学段辅助分级，不能视为标准能力测量。'], 'words':result}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    with args.output.with_suffix('.csv').open('w',newline='') as f:
        writer=csv.writer(f);writer.writerow(['word','oldLevel','level','new','commonSenseReviewed','contentReviewed','changedFields','missing','levelEvidence'])
        for r in result:writer.writerow([r[k] if not isinstance(r[k],list) else '; '.join(r[k]) for k in ['word','oldLevel','level','new','commonSenseReviewed','contentReviewed','changedFields','missing','levelEvidence']])
    print(json.dumps(summary,ensure_ascii=False,indent=2))


if __name__ == '__main__':main()
