#!/usr/bin/env python3
"""Apply the v13 offline content and difficulty review, then audit every entry.

Only shipped records and explicit project-authored inputs are read. No downloads,
word-generation templates, database reset, or learning-ID migration is involved.
"""
import argparse
import copy
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
CONTENT = ROOT / 'tools/internal_content_v13.json'
ADDITIONS = ROOT / 'tools/missing_words_v13.json'
REPORTS = ROOT / 'dist/validation-v2.0.4'
DETAILS = {'examples': 'en', 'collocations': 'en', 'derivatives': 'word',
           'confusables': 'word', 'wordForms': 'word', 'synonyms': 'word'}


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def authored_pronunciations(field):
    """Explicit readings for new reviewed headwords, never generated from US IPA."""
    if not ADDITIONS.exists():
        return {}
    return {w['word']: w[field] for w in load(ADDITIONS)['words'] if w.get(field)}


def apply_review(words):
    review = load(CONTENT)
    additions = load(ADDITIONS)
    index = {w['word']: w for w in words}
    assert len(index) == len(words), 'duplicate headword'
    counts = Counter()
    for entry in additions['words']:
        word = entry['word']
        if word not in index:
            fields = ('id', 'word', 'pronunciationUs', 'pronunciationUk', 'partOfSpeech',
                      'meaningZh', 'difficulty', 'rank', *DETAILS)
            new = {k: copy.deepcopy(entry[k]) for k in fields}
            for item in new['examples']:
                item.setdefault('source', '项目原创语境 v13 · CC0')
                item.setdefault('attribution', '项目原创（CC0）')
            for item in new['collocations']:
                item.setdefault('source', '项目原创搭配 v13 · CC0')
            words.append(new)
            index[word] = new
            counts['headwordsAdded'] += 1
    for word, fix in review.get('meaningFixes', {}).items():
        entry = index[word]
        if entry['meaningZh'] != fix['meaningZh']:
            entry['meaningZh'] = fix['meaningZh']
            counts['meaningsCorrected'] += 1
        if fix.get('partOfSpeech') and entry['partOfSpeech'] != fix['partOfSpeech']:
            entry['partOfSpeech'] = fix['partOfSpeech']
            counts['partsOfSpeechCorrected'] += 1
    for word, fix in review.get('fieldFixes', {}).items():
        entry = index[word]
        for field, value in fix.items():
            if field in ('reason', 'evidence'):
                continue
            assert field not in ('id', 'word', 'rank', 'difficulty'), (word, field)
            if entry[field] != value:
                entry[field] = copy.deepcopy(value)
                counts[field + 'Corrected'] += 1
    for field, key in [('examples', 'en'), ('collocations', 'en')]:
        for word, additions in review.get(field, {}).items():
            entry = index[word]
            for item in reversed(additions) if field == 'examples' else additions:
                existing = next((x for x in entry[field] if x[key] == item[key]), None)
                if existing:
                    if existing['zh'] != item['zh']:
                        existing['zh'] = item['zh']
                        existing['translationCredit'] = '项目原创复核 v13（CC0）'
                        counts[field + 'TranslationsCorrected'] += 1
                    continue
                original = {**item, 'source': '项目原创学习内容 v13 · CC0'}
                if field == 'examples':
                    original['attribution'] = '项目原创（CC0）'
                    entry[field].insert(0, original)
                    entry[field] = entry[field][:2]
                    counts['originalExamplesAdded'] += 1
                elif len(entry[field]) < 4:
                    entry[field].append(original)
                    counts['originalCollocationsAdded'] += 1
    for entry in words:
        for item in entry['collocations']:
            fix = review.get('collocationFixes', {}).get(item['en'])
            if fix and item['zh'] != fix['zh']:
                item['zh'] = fix['zh']
                item['translationCredit'] = '项目原创译法修订 v13（CC0）'
                counts['collocationTranslationsCorrected'] += 1
        for field in ('derivatives', 'confusables'):
            for item in entry[field]:
                fix = review.get('meaningFixes', {}).get(item['word'])
                if fix:
                    prefix = '词族关联：' if item['meaningZh'].startswith('词族关联') else ''
                    value = prefix + fix['meaningZh']
                    if value != item['meaningZh']:
                        item['meaningZh'] = value
                        counts['relatedMeaningsCorrected'] += 1
    for fix in review.get('detailFixes', []):
        items = index[fix['word']][fix['field']]
        for position, item in enumerate(items):
            if item['word'] != fix['matchWord']:
                continue
            if fix['new'] is None:
                del items[position]
                counts['wrongSenseRelationsRemoved'] += 1
            else:
                replacement = {**copy.deepcopy(fix['new']),
                    'translationCredit': '项目原创义项修订 v13（CC0）'}
                if item != replacement:
                    items[position] = replacement
                    counts['relationSensesCorrected'] += 1
            break
    from vocabulary_levels import assign_levels
    old_levels = {w['id']: w['difficulty'] for w in words}
    level_report = assign_levels(words)
    counts['levelsChanged'] = sum(w['difficulty'] != old_levels[w['id']] for w in words)
    return dict(counts), level_report


def audit(words):
    from build_vocabulary import validate
    validate(words)
    ids = set()
    results = []
    for entry in words:
        assert entry['id'] not in ids
        ids.add(entry['id'])
        assert re.search(r'[\u3400-\u9fff]', entry['meaningZh']), entry['word']
        assert re.fullmatch(r'/[^/]+/(?:, /[^/]+/)*', entry['pronunciationUs']), entry['word']
        acceptable = {entry['word'].lower()} | {x['word'].lower() for x in entry['wordForms']}
        for field, key in DETAILS.items():
            values = [x[key] for x in entry[field]]
            assert len(values) == len(set(values)), (entry['word'], field)
            for item in entry[field]:
                assert item[key].strip(), (entry['word'], field)
        for item in entry['examples']:
            tokens = set(re.findall(r"[a-z]+(?:'[a-z]+)?", item['en'].lower()))
            assert acceptable & tokens, (entry['word'], item['en'])
        results.append({'word': entry['word'], 'difficulty': entry['difficulty'],
            'checks': ['required-fields', 'unique-id', 'valid-level', 'ipa-format',
                       'detail-uniqueness', 'example-target-or-attested-form'],
            'missing': [k for k in DETAILS if not entry[k]] +
                (['pronunciationUk'] if not entry['pronunciationUk'] else []) +
                (['bilingualExamples'] if not any(x['zh'] for x in entry['examples']) else [])})
    return results


def update_metadata(words, metadata):
    from apply_internal_review import update_metadata as previous_metadata
    from vocabulary_levels import VERSION, distribution, REVIEWED_FLOOR, LEVEL_CORRECTIONS
    content_version = max(16, metadata.get('contentVersion', 0))
    metadata = previous_metadata(words, metadata)
    metadata['contentVersion'] = content_version
    metadata['difficultyCounts'] = distribution(words)
    metadata['difficultyDescriptionZh'] = (
        '学习顺序v4：保留词频骨架、基础词核心与现有词头信号，修复无词频默认过难、'
        '英美拼写难度分离与复核只能升不能降的问题；明确复核词可按常用义调整。'
        '等级是项目学习顺序估计，不等同CEFR、词汇量或考试分数。')
    metadata['levelMethod'].update({'version': VERSION,
        'descriptionZh': metadata['difficultyDescriptionZh'],
        'reviewedFloorCount': len(REVIEWED_FLOOR),
        'explicitCorrectionCount': len(LEVEL_CORRECTIONS),
        'reviewFile': 'tools/vocabulary_levels.py',
        'reviewInput': 'tools/level_review_v13.json'})
    metadata['selectionDescriptionZh'] = (
        f'保留已有词头与学习ID，新增项目复核的日常及数字生活缺词。共{len(words)}词，'
        '均有美式音标、中文释义和词性；新增词的音标为显式项目复核记录。'
        '无语料词频时rank=1000000仅用于排序，不表示真实词频。')
    metadata['britishPronunciationWordCount'] = sum(bool(w['pronunciationUk']) for w in words)
    metadata['missingBritishPronunciationWordCount'] = sum(not w['pronunciationUk'] for w in words)
    metadata['completeReview'] = {'version': 'v13', 'externalDictionaryUsed': False,
        'contentFile': 'tools/internal_content_v13.json', 'additionFile': 'tools/missing_words_v13.json',
        'descriptionZh': '全量结构和内部一致性检查；显式补词、原创语境、纠错及难度复核。保留来源元数据及学习ID，界面隐藏技术来源。全量规则检查不等于所有语义逐条人工审定。'}
    for file in ['internal_content_v13.json', 'missing_words_v13.json', 'level_review_v13.json',
                 'apply_complete_review.py', 'vocabulary_levels.py', 'level_corrections.json']:
        metadata.setdefault('originalInputSha256', {})[file] = sha(ROOT / 'tools' / file)
    return metadata


def bundle_inputs():
    bundle = ASSETS / 'level_sources'
    for file in ['vocabulary_levels.py', 'level_corrections.json', 'level_review_v13.json']:
        (bundle / file).write_bytes((ROOT / 'tools' / file).read_bytes())
    (ASSETS / 'project_review_v13.json').write_bytes(ADDITIONS.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--baseline', type=Path, help='Optional earlier vocabulary for a cumulative change report')
    args = parser.parse_args()
    source = ASSETS / 'vocabulary.json'
    before = load(source)
    words = copy.deepcopy(before)
    counts, levels = apply_review(words)
    records = audit(words)
    old = {w['id']: w for w in before}
    assert [w['id'] for w in words if w['id'] in old] == list(old), 'existing learning IDs/order must survive'
    metadata = update_metadata(words, load(ASSETS / 'dictionary_sources.json'))
    if args.check:
        assert words == before, f'review not applied: {counts}'
        assert metadata == load(ASSETS / 'dictionary_sources.json'), 'stale metadata'
        assert sha(source) == metadata['vocabularySha256']
        for file in ['vocabulary_levels.py', 'level_corrections.json', 'level_review_v13.json']:
            assert (ASSETS / 'level_sources' / file).read_bytes() == (ROOT / 'tools' / file).read_bytes(), file
        assert (ASSETS / 'project_review_v13.json').read_bytes() == ADDITIONS.read_bytes()
        print(json.dumps({'wordCount': len(words), 'allRecordsChecked': len(records), 'idempotent': True}, ensure_ascii=False))
        return
    source.write_text(json.dumps(words, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    (ASSETS / 'dictionary_sources.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    bundle_inputs()
    comparison = load(args.baseline) if args.baseline else before
    old = {w['id']: w for w in comparison}
    assert set(old) <= {w['id'] for w in words}, 'baseline learning IDs must survive'
    changes = [{'word': w['word'], 'added': w['id'] not in old,
                'changedFields': [k for k in w if w[k] != old.get(w['id'], {}).get(k)],
                'previousDifficulty': old.get(w['id'], {}).get('difficulty'),
                'difficulty': w['difficulty'], 'levelEvidence': levels[w['word']]}
               for w in words if w != old.get(w['id'])]
    REPORTS.mkdir(parents=True, exist_ok=True)
    if changes or not (REPORTS / 'applied-review.json').exists():
        summary = {'headwordsAdded': sum(w['id'] not in old for w in words),
            'existingEntriesChanged': sum(w['id'] in old and w != old[w['id']] for w in words),
            'existingLevelsChanged': sum(w['id'] in old and w['difficulty'] != old[w['id']]['difficulty'] for w in words),
            'newEnglishExamples': sum(x['en'] not in {e['en'] for e in old.get(w['id'], {}).get('examples', [])}
                                      for w in words for x in w['examples']),
            'newCollocations': sum(x['en'] not in {e['en'] for e in old.get(w['id'], {}).get('collocations', [])}
                                   for w in words for x in w['collocations']),
            'existingFieldsChanged': dict(Counter(k for change in changes if not change['added'] for k in change['changedFields']))}
        (REPORTS / 'applied-review.json').write_text(json.dumps({'lastRunOperations': counts, 'summary': summary,
            'previousWordCount': len(comparison), 'wordCount': len(words), 'changes': changes,
            'allWordChecks': records, 'levels': levels}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'wordCount': len(words), 'operations': counts}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
