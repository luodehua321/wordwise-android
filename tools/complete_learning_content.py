#!/usr/bin/env python3
"""Apply explicitly authored bilingual learning content; never fetch external data.

Existing English sentences retain their author/source/license. Only their missing
Chinese translation is added, with a separate CC0 project translation credit.
Unknown content remains in the per-word gap report rather than being fabricated.
"""
import argparse
import copy
import hashlib
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
INPUT = ROOT / 'tools/learning_content_v14.json'
READINGS = ROOT / 'tools/related_pronunciations_v14.json'
OUT = ROOT / 'dist/validation-v2.0.5'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def apply_review(words):
    review = load(INPUT)
    index = {w['word']: w for w in words}
    for word, translations in review['exampleTranslations'].items():
        for english, chinese in translations.items():
            found = [e for e in index[word]['examples'] if e['en'] == english or e.get('originalEn') == english]
            assert len(found) == 1, (word, english)
            item = found[0]
            original_translation = chinese
            chinese = review.get('englishFixes', {}).get(word, {}).get(english, {}).get('zh', chinese)
            assert item['zh'] in ('', original_translation, chinese), (word, 'translation conflict')
            item['zh'] = chinese
            item['translationCredit'] = '中文译文：项目原创复核 v14（CC0）'
            item['translationLicense'] = 'CC0 1.0'
    for word, fixes in review.get('englishFixes', {}).items():
        for original, fix in fixes.items():
            item = next(e for e in index[word]['examples']
                        if e['en'] == original or e.get('originalEn') == original)
            item['originalEn'] = original
            item['en'] = fix['en']
            item['zh'] = fix['zh']
            item['englishRevisionCredit'] = '项目英文校订 v14（CC0）；原句和原署名保留'
            item['englishRevisionReason'] = fix['reason']
    for word, examples in review['examples'].items():
        for item in reversed(examples):
            if any(x['en'] == item['en'] for x in index[word]['examples']):
                continue
            index[word]['examples'].insert(0, {**item,
                'source': '项目原创语境 v14 · CC0', 'attribution': '项目原创（CC0）'})
        index[word]['examples'] = index[word]['examples'][:2]
    for word, examples in review.get('replaceExamples', {}).items():
        index[word]['examples'] = [{**item, 'source': '项目原创语境 v14 · CC0',
            'attribution': '项目原创（CC0）'} for item in examples]
    for word, fix in review.get('meaningFixes', {}).items():
        index[word]['meaningZh'] = fix['meaningZh']
        if fix.get('partOfSpeech'):
            index[word]['partOfSpeech'] = fix['partOfSpeech']
    for word in words:
        for field in ('derivatives', 'confusables'):
            for item in word[field]:
                fix = review.get('meaningFixes', {}).get(item['word'])
                if fix:
                    item['meaningZh'] = fix['meaningZh']
    # A new original translation may also complete the exact same sentence in
    # another entry. Reuse only when its existing translations are unambiguous.
    donors = defaultdict(list)
    for entry in words:
        for item in entry['examples']:
            if item['zh']:
                donors[item['en']].append((entry['id'], item))
    for entry in words:
        for item in entry['examples']:
            same = donors.get(item['en'], [])
            if item['zh'] or not same or len({e['zh'] for _, e in same}) != 1:
                continue
            donor_id, donor = min(same, key=lambda pair: pair[0])
            item['zh'] = donor['zh']
            item['translationReuseWordId'] = donor_id
            item['translationCredit'] = donor.get('translationCredit', donor.get('attribution', ''))
            item['translationLicense'] = donor.get('translationLicense', donor.get('licenseUrl', ''))
            if donor.get('translationSourceUrl'):
                item['translationSourceUrl'] = donor['translationSourceUrl']
    return words


def update_metadata(words, metadata):
    from apply_complete_review import update_metadata as previous_metadata
    metadata = previous_metadata(words, metadata)
    metadata['contentVersion'] = 17
    metadata['learningContentReview'] = {'version': 'v14', 'file': 'tools/learning_content_v14.json',
        'authoredTranslations': sum(len(x) for x in load(INPUT)['exampleTranslations'].values()),
        'authoredExamples': sum(len(x) for x in load(INPUT)['examples'].values()),
        'replacedExampleEntries': len(load(INPUT).get('replaceExamples', {})),
        'englishRevisions': sum(len(x) for x in load(INPUT).get('englishFixes', {}).values()),
        'externalInformationQueried': False,
        'note': '仅项目既有资料及显式原创语境/译文，不把模板、拼接词义或错译当作完整例句。'}
    metadata['relatedPronunciations'] = {
        'asset': 'related_pronunciations.json', 'sha256': sha(READINGS),
        'source': '既有词条、原构建的固定音标快照及项目显式复核；没有查询新外部资料。',
        'originalUsSnapshotSha256': '2af6f154a5c363275f052d1f85acedef38ed185ca9745aa4314be77f6b70de67',
        'missingAccentPolicy': '缺所选口音而有另一口音时明确标美/英；两者都无时标音标待补。'}
    for filename in ['learning_content_v14.json', 'related_pronunciations_v14.json',
                     'related_ipa_review_v14.json', 'complete_learning_content.py']:
        metadata['originalInputSha256'][filename] = sha(ROOT / 'tools' / filename)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = ASSETS / 'vocabulary.json'
    before = load(source)
    words = apply_review(copy.deepcopy(before))
    from apply_complete_review import audit
    audit(words)
    metadata = update_metadata(words, load(ASSETS / 'dictionary_sources.json'))
    if args.check:
        assert words == before, 'content patch not applied'
        assert metadata == load(ASSETS / 'dictionary_sources.json'), 'metadata not current'
        assert (ASSETS / 'related_pronunciations.json').read_bytes() == READINGS.read_bytes()
        print('Verified all', len(words), 'records and authored content, metadata, related readings; idempotent.')
        return
    assert [w['id'] for w in words] == [w['id'] for w in before]
    source.write_text(json.dumps(words, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    (ASSETS / 'dictionary_sources.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (ASSETS / 'related_pronunciations.json').write_bytes(READINGS.read_bytes())
    gaps = [{'word': w['word'], 'missingExamples': not w['examples'],
             'untranslatedEnglish': [e['en'] for e in w['examples'] if not e['zh']]}
            for w in words if not w['examples'] or any(not e['zh'] for e in w['examples'])]
    readings = load(READINGS)
    OUT.mkdir(parents=True, exist_ok=True)
    report = {'wordCount': len(words), 'content': metadata['learningContentReview'],
        'wordsWithoutExamples': sum(not w['examples'] for w in words),
        'untranslatedExampleItems': sum(not e['zh'] for w in words for e in w['examples']),
        'relatedHeadwords': len(readings),
        'relatedWithEitherAccent': sum(bool(x['us'] or x['uk']) for x in readings.values()),
        'relatedWithoutIpa': [t for t, r in readings.items() if not (r['us'] or r['uk'])],
        'remainingContentGaps': gaps}
    (OUT / 'content-coverage.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('remainingContentGaps','relatedWithoutIpa')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
