#!/usr/bin/env python3
"""Review every shipped entry using only project-owned inputs and the shipped data.

No network, downloaded dictionary, suffix inference, or US-to-UK IPA conversion.
Run normally to apply the idempotent review; --check requires it already applied.
Rebuild the prepared assets separately with prepare_vocabulary.py afterwards.
"""
import argparse
import copy
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
REVIEW_FILE = ROOT / 'tools/internal_content_v12.json'
FIELDS = ('examples', 'collocations', 'derivatives', 'confusables', 'wordForms', 'synonyms')
CREDIT = '中文译文：项目原创复核 v12（CC0）'


def load_review():
    return json.loads(REVIEW_FILE.read_text(encoding='utf-8'))


def confirmed_review(review):
    return json.loads((REVIEW_FILE.parent / review['confirmedFixesFile']).read_text(encoding='utf-8'))


def tokens(text):
    found = set(re.findall(r"[a-z]+(?:['’][a-z]+)?", text.lower()))
    # A possessive is still an occurrence of its noun, not a different lemma.
    return found | {word[:-2] for word in found if word.endswith(("'s", "’s"))}


def mark_translation(item, chinese):
    item['zh'] = chinese
    item['translationCredit'] = CREDIT
    item['translationLicense'] = 'CC0 1.0'
    if CREDIT not in item.get('attribution', ''):
        item['attribution'] = (item.get('attribution', '') + ' · ' + CREDIT).strip(' ·')
    if '中文译文' not in item.get('source', ''):
        item['source'] = (item.get('source', '') + ' · 中文译文为项目原创').strip(' ·')


def apply_review(words, review=None, confirmed=None):
    review = review if review is not None else load_review()
    confirmed = confirmed if confirmed is not None else confirmed_review(review)
    index = {w['word']: w for w in words}
    assert len(index) == len(words), 'duplicate IDs'
    counts = Counter()
    removed_examples = []

    for word, fix in review.get('meaningFixes', {}).items():
        entry = index[word]
        if entry['meaningZh'] != fix['meaningZh']:
            entry['meaningZh'] = fix['meaningZh']
            counts['primaryMeaningsCorrected'] += 1
        if fix.get('partOfSpeech') and entry['partOfSpeech'] != fix['partOfSpeech']:
            entry['partOfSpeech'] = fix['partOfSpeech']
            counts['partsOfSpeechCorrected'] += 1

    for word, forms in confirmed.get('formFixes', {}).items():
        if index[word]['wordForms'] != forms:
            counts['wordFormsRemoved'] += len(index[word]['wordForms']) - len(forms)
            counts['wordFormEntriesCorrected'] += 1
            index[word]['wordForms'] = copy.deepcopy(forms)
    for word, ipa in confirmed.get('pronunciationUsFixes', {}).items():
        ipa = ipa.get('value', ipa.get('ipa')) if isinstance(ipa, dict) else ipa
        if index[word]['pronunciationUs'] != ipa:
            index[word]['pronunciationUs'] = ipa
            counts['usPronunciationsDisambiguated'] += 1

    for word, rejected in review.get('removeExamples', {}).items():
        entry = index[word]
        for item in entry['examples']:
            if item['en'] in rejected:
                removed_examples.append({'word': word, 'en': item['en'], 'reason': 'reviewed homograph/sense mismatch'})
        entry['examples'] = [e for e in entry['examples'] if e['en'] not in rejected]
    for word, rejected in confirmed.get('removeCollocations', {}).items():
        old = index[word]['collocations']
        index[word]['collocations'] = [x for x in old if x['en'] not in rejected]
        counts['invalidCollocationsRemoved'] += len(old) - len(index[word]['collocations'])
    for word, fixes in confirmed.get('collocationFixes', {}).items():
        for item in index[word]['collocations']:
            if item['en'] in fixes and item['zh'] != fixes[item['en']]:
                item['zh'] = fixes[item['en']]
                item['source'] = '项目内置短语复核 v12 · 中文修订 CC0'
                counts['collocationTranslationsCorrected'] += 1

    translations = copy.deepcopy(review.get('exampleTranslations', {}))
    for word, fixes in confirmed.get('examplesFixes', {}).items():
        translations.setdefault(word, {}).update(fixes)
    for word, fixes in translations.items():
        for english, chinese in fixes.items():
            matches = [e for e in index[word]['examples'] if e['en'] == english]
            assert matches, f'missing reviewed sentence: {word}: {english}'
            if matches[0]['zh'] != chinese:
                counts['authoredTranslationsAdded' if not matches[0]['zh'] else 'exampleTranslationsCorrected'] += 1
                mark_translation(matches[0], chinese)

    for word, additions in review.get('examples', {}).items():
        entry = index[word]
        for item in reversed(additions):
            if any(e['en'] == item['en'] for e in entry['examples']):
                continue
            entry['examples'].insert(0, {**item, 'source': '项目原创语境 v12 · CC0',
                'attribution': '项目原创（CC0）'})
            counts['originalExamplesAdded'] += 1
        entry['examples'] = entry['examples'][:2]
    for word, additions in review.get('collocations', {}).items():
        entry = index[word]
        for item in additions:
            if not any(c['en'] == item['en'] for c in entry['collocations']) and len(entry['collocations']) < 4:
                entry['collocations'].append({**item, 'source': '项目原创搭配 v12 · CC0'})
                counts['originalCollocationsAdded'] += 1

    # The previous related-word glosses can retain a corrected primary-sense error.
    # Synonyms use their own synset sense instead of blindly copying a headword gloss.
    for entry in words:
        for field in ('derivatives', 'confusables'):
            for item in entry[field]:
                fix = review.get('meaningFixes', {}).get(item['word'])
                if fix and item['meaningZh'] != fix['meaningZh']:
                    prefix = '词族关联：' if item['meaningZh'].startswith('词族关联') else ''
                    new = prefix + fix['meaningZh']
                    if item['meaningZh'] != new:
                        item['meaningZh'] = new
                        counts['relatedMeaningsCorrected'] += 1
                # Resolve only explicit equivalence to another existing entry.
                if not re.search(r'[\u3400-\u9fff]', item['meaningZh']):
                    alias = re.fullmatch(r'[a-z.,\s]*=\s*([A-Za-z-]+)', item['meaningZh'])
                    if alias and alias[1] in index:
                        item['meaningZh'] = f"同 {alias[1]}：{index[alias[1]]['meaningZh']}"
                        counts['explicitAliasMeaningsCompleted'] += 1
        for item in entry.get('synonyms', []):
            translated = review.get('synonymSenseTranslations', {}).get(item.get('senseEn'))
            if translated and item['meaningZh'] != translated:
                item['meaningZh'] = translated
                item['translationCredit'] = '义项中文：项目原创复核 v12（CC0）'
                counts['synonymSenseGlossesCorrected'] += 1

    # Reuse only an identical sentence with one unambiguous existing Chinese text.
    donors = defaultdict(list)
    for entry in words:
        for item in entry['examples']:
            if item['zh']:
                donors[item['en']].append((entry['word'], item))
    for entry in words:
        for item in entry['examples']:
            same = donors.get(item['en'], [])
            if item['zh'] or not same or len({e['zh'] for _, e in same}) != 1:
                continue
            donor_word, donor = sorted(same, key=lambda x: x[0])[0]
            item['zh'] = donor['zh']
            item['translationReuseWordId'] = donor_word
            item['translationCredit'] = f"同句译文复用自内置词条 {donor_word}：{donor.get('attribution', '')}"
            item['translationLicense'] = donor.get('translationLicense', donor.get('licenseUrl', 'CC0 1.0'))
            if donor.get('translationSourceUrl'):
                item['translationSourceUrl'] = donor['translationSourceUrl']
            item['attribution'] = (item.get('attribution', '') + ' · ' + item['translationCredit']).strip(' ·')
            counts['identicalSentenceTranslationsReused'] += 1

    # Removing inherited forms can reveal previously attached wrong-lemma examples.
    for entry in words:
        acceptable = {entry['word'].lower()} | {f['word'].lower() for f in entry['wordForms']}
        valid = []
        for item in entry['examples']:
            if acceptable & tokens(item['en']):
                valid.append(item)
            else:
                removed_examples.append({'word': entry['word'], 'en': item['en'],
                    'reason': 'target absent after attested form cleanup'})
        entry['examples'] = valid
    counts['wrongSenseOrLemmaExamplesRemoved'] = len(removed_examples)
    return dict(counts), removed_examples


def update_metadata(words, metadata):
    metadata = copy.deepcopy(metadata)
    payload = (json.dumps(words, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    metadata['contentVersion'] = 15
    metadata['wordCount'] = len(words)
    metadata['vocabularySha256'] = hashlib.sha256(payload).hexdigest()
    metadata['enhancedCounts'] = {k: sum(bool(w[k]) for w in words) for k in FIELDS if k != 'synonyms'}
    metadata['enhancedCountsByDifficulty'] = {str(d): sum(bool(w['examples']) for w in words if w['difficulty'] == d) for d in range(1, 11)}
    metadata['bilingualExampleWordCount'] = sum(any(e['zh'] for e in w['examples']) for w in words)
    metadata['englishOnlyExampleWordCount'] = sum(bool(w['examples']) and not any(e['zh'] for e in w['examples']) for w in words)
    for field, count in [('examples', 'exampleItemCount'), ('collocations', 'collocationItemCount'),
                         ('derivatives', 'derivativeItemCount'), ('wordForms', 'wordFormItemCount')]:
        metadata[count] = sum(len(w[field]) for w in words)
    metadata['synonymWordCount'] = sum(bool(w['synonyms']) for w in words)
    metadata['completeCoreWordCount'] = sum(all(w[k] for k in ['examples', 'collocations', 'derivatives', 'confusables']) for w in words)
    for file in ['internal_content_v12.json', 'confirmed_content_fixes_v12.json', 'apply_internal_review.py', 'american_ipa_corrections.json']:
        metadata.setdefault('originalInputSha256', {})[file] = hashlib.sha256((ROOT / 'tools' / file).read_bytes()).hexdigest()
    metadata['internalReview'] = {'version': 'v12', 'externalDictionaryUsed': False,
        'file': 'tools/internal_content_v12.json', 'confirmedFile': 'tools/confirmed_content_fixes_v12.json',
        'descriptionZh': '全量遍历内置词条，清理内部可互证的词形/义项错误，新增项目原创例句和译文；不导入新外部词库，不推造缺失发音或词族。规则检查不是所有义项的逐词人工审定。'}
    metadata['americanPronunciationCorrectionCount'] = len(json.loads((ROOT / 'tools/american_ipa_corrections.json').read_text()))
    return metadata


def audit_records(before, words):
    previous = {w['id']: w for w in before}
    result = []
    for w in words:
        result.append({'word': w['word'], 'difficulty': w['difficulty'],
            'changedFields': [k for k in w if w[k] != previous[w['id']].get(k)],
            'missing': [k for k in FIELDS if not w[k]] +
                (['pronunciationUk'] if not w['pronunciationUk'] else []) +
                (['bilingualExamples'] if not any(e['zh'] for e in w['examples']) else []),
            'checksPassed': ['required-fields', 'unique-id', 'valid-level', 'detail-uniqueness',
                             'example-target-or-attested-form', 'english-credit-retained']})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = ASSETS / 'vocabulary.json'
    before = json.loads(source.read_text(encoding='utf-8'))
    words = copy.deepcopy(before)
    counts, removed = apply_review(words)
    from build_vocabulary import validate
    validate(words)
    for w in words:
        for field, key in [('examples', 'en'), ('collocations', 'en'), ('derivatives', 'word'),
                           ('confusables', 'word'), ('wordForms', 'word'), ('synonyms', 'word')]:
            keys = [x[key] for x in w[field]]
            assert len(keys) == len(set(keys)), (w['word'], field)
    assert [w['id'] for w in before] == [w['id'] for w in words], 'learning IDs must be retained'
    if args.check:
        assert words == before, f'internal review not yet applied: {counts}'
        metadata = json.loads((ASSETS / 'dictionary_sources.json').read_text())
        assert hashlib.sha256(source.read_bytes()).hexdigest() == metadata['vocabularySha256']
        for file in ['internal_content_v12.json', 'confirmed_content_fixes_v12.json', 'apply_internal_review.py']:
            assert metadata['originalInputSha256'][file] == hashlib.sha256((ROOT / 'tools' / file).read_bytes()).hexdigest(), file
        print(json.dumps({'wordCount': len(words), 'idempotent': True}, ensure_ascii=False))
        return
    source.write_text(json.dumps(words, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    metadata = update_metadata(words, json.loads((ASSETS / 'dictionary_sources.json').read_text()))
    (ASSETS / 'dictionary_sources.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    out = ROOT / 'dist/validation-v2.0.3'
    out.mkdir(parents=True, exist_ok=True)
    report = {'wordCount': len(words), 'operations': counts, 'removedExamples': removed,
        'records': audit_records(before, words),
        'limitations': ['结构和内部互证全量检查不等于全部词义逐条人工验证。',
                        '缺失发音、派生、易混与例句不靠模板或后缀推造。']}
    report_path = out / 'applied-content-review.json'
    if words != before or not report_path.exists():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'wordCount': len(words), 'operations': counts}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
