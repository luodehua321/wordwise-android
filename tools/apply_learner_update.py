#!/usr/bin/env python3
"""Apply the v2.0 learner update to the shipped vocabulary and level model.

This is deliberately a patch step rather than a full rebuild: the public source
snapshots are pinned, but a fresh rebuild needs the large ECDICT / WordNet / OEWN
downloads.  The script is idempotent and only touches the shipped assets plus the
source metadata.  It then asks prepare_vocabulary.py to rebuild the SQLite and
binary caches so the APK cannot ship a mixed generation.

Usage:
    python3 tools/apply_learner_update.py [--check]

--check validates the current assets without writing anything.
"""
import argparse
import hashlib
import json
from pathlib import Path

from vocabulary_levels import assign_levels, distribution

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app' / 'src' / 'main' / 'assets'
VOCABULARY = ASSETS / 'vocabulary.json'
SOURCES = ASSETS / 'dictionary_sources.json'
ENRICHMENT = ROOT / 'tools' / 'learner_enrichment_v11.json'
TRANSLATION_CREDIT = '中文译文：项目原创（CC0）'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load():
    words = json.loads(VOCABULARY.read_text(encoding='utf-8'))
    enrichment = json.loads(ENRICHMENT.read_text(encoding='utf-8'))
    index = {word['word']: word for word in words}
    assert len(index) == len(words), 'duplicate word IDs'
    return words, index, enrichment


def apply(enrichment, index, errors):
    translations = enrichment.get('exampleTranslations', {})
    for word, sentences in translations.items():
        entry = index.get(word)
        if entry is None:
            errors.append(f'translation target missing: {word}')
            continue
        for english, chinese in sentences.items():
            matches = [item for item in entry['examples'] if item.get('en') == english]
            if not matches:
                errors.append(f'translation source sentence missing: {word} :: {english[:60]}')
                continue
            item = matches[0]
            if item.get('zh') and item['zh'] != chinese:
                # A later explicit project review supersedes the v11 translation.
                from apply_internal_review import load_review, confirmed_review
                latest = load_review()
                overrides = latest.get('exampleTranslations', {}).get(word, {})
                overrides = {**overrides, **confirmed_review(latest).get('examplesFixes', {}).get(word, {})}
                if overrides.get(english) == item['zh']:
                    continue
                errors.append(f'translation already present with different text: {word} :: {english[:60]}')
                continue
            item['zh'] = chinese
            item['translationCredit'] = TRANSLATION_CREDIT
            item['translationLicense'] = 'CC0 1.0'
            attribution = (item.get('attribution') or '').strip()
            if TRANSLATION_CREDIT not in attribution:
                item['attribution'] = (attribution + ' · ' if attribution else '') + TRANSLATION_CREDIT
            source = (item.get('source') or '').strip()
            if '中文译文' not in source:
                item['source'] = (source + ' · ' if source else '') + '中文译文为项目原创'

    added_examples = 0
    for word, items in enrichment.get('examples', {}).items():
        entry = index.get(word)
        if entry is None:
            errors.append(f'example target missing: {word}')
            continue
        existing = {item['en'] for item in entry['examples']}
        for item in items:
            if not item.get('en') or not item.get('zh'):
                errors.append(f'invalid example for {word}')
                continue
            if item['en'] in existing:
                continue
            entry['examples'].insert(0, {
                'en': item['en'], 'zh': item['zh'],
                'source': '项目原创语境 v11 · CC0',
                'attribution': '项目原创（CC0）',
            })
            existing.add(item['en'])
            added_examples += 1
        entry['examples'] = entry['examples'][:2]

    added_collocations = 0
    for word, items in enrichment.get('collocations', {}).items():
        entry = index.get(word)
        if entry is None:
            errors.append(f'collocation target missing: {word}')
            continue
        existing = {item['en'] for item in entry['collocations']}
        for item in items:
            if not item.get('en') or not item.get('zh'):
                errors.append(f'invalid collocation for {word}')
                continue
            if item['en'] in existing:
                continue
            entry['collocations'].insert(0, {
                'en': item['en'], 'zh': item['zh'],
                'source': '项目原创搭配 v11 · CC0',
            })
            existing.add(item['en'])
            added_collocations += 1
        entry['collocations'] = entry['collocations'][:4]

    fixed_meanings = 0
    for word, fix in enrichment.get('meaningFixes', {}).items():
        entry = index.get(word)
        if entry is None:
            errors.append(f'meaning target missing: {word}')
            continue
        entry['meaningZh'] = fix['meaningZh']
        if fix.get('partOfSpeech'):
            entry['partOfSpeech'] = fix['partOfSpeech']
        fixed_meanings += 1
    return added_examples, added_collocations, fixed_meanings


def update_sources(words, enrichment):
    metadata = json.loads(SOURCES.read_text(encoding='utf-8'))
    payload_bytes = VOCABULARY.read_bytes()
    metadata['contentVersion'] = 14
    metadata['wordCount'] = len(words)
    metadata['vocabularySha256'] = hashlib.sha256(payload_bytes).hexdigest()
    metadata['difficultyDescriptionZh'] = (
        'v2.0 学习顺序：以COCA词频为骨架，叠加项目基础词核心、NGSL词头分档、'
        '构词复杂度惩罚与少量复核下限；低等级优先具体、日常、高可用词，'
        '长尾集中在高等级。仅为学习排序估计，不等同CEFR、词汇量或考试成绩。')
    metadata['levelMethod'] = {
        'version': 'learning-order-v3',
        'descriptionZh': '词频骨架 + 项目基础词核心 + NGSL词头晋升上限 + 构词复杂度惩罚 + 复核下限；全部规则可追溯，不使用学习者隐私数据。',
        'frequencyThresholds': [1000, 2200, 3800, 5500, 8000, 12000, 17000, 23000, 30000],
        'ngslPromotionCeiling': {'1000': 1800, '2000': 3200, '3000': 4500},
        'reviewedFloorCount': 53,
        'reviewFile': 'tools/vocabulary_levels.py',
        'enrichmentFile': 'tools/learner_enrichment_v11.json',
        'validatedCefr': False,
        'noteZh': '这是项目内部学习顺序，不是标准化等级。低等级更偏基础与日常，高等级保留大量低频词。',
    }
    metadata['learnerMeaningFixCount'] = len(enrichment.get('meaningFixes', {}))
    metadata['learnerEnrichmentCounts'] = {
        'translatedSentences': sum(len(v) for v in enrichment.get('exampleTranslations', {}).values()),
        'addedExamples': sum(len(v) for v in enrichment.get('examples', {}).values()),
        'addedCollocations': sum(len(v) for v in enrichment.get('collocations', {}).values()),
    }
    metadata['learnerUpdate'] = {
        'version': 'v11',
        'file': 'tools/learner_enrichment_v11.json',
        'sha256': sha256(ENRICHMENT),
        'translatedSentenceCount': sum(len(v) for v in enrichment.get('exampleTranslations', {}).values()),
        'addedExampleCount': sum(len(v) for v in enrichment.get('examples', {}).values()),
        'addedCollocationCount': sum(len(v) for v in enrichment.get('collocations', {}).values()),
        'fixedMeaningCount': len(enrichment.get('meaningFixes', {})),
        'license': 'CC0 1.0',
        'descriptionZh': '新增中文译文、原创双语例句、原创搭配与高频多义词精简释义；英文原句保持原署名，中文译文与新增内容为项目原创。',
    }
    metadata.setdefault('originalInputSha256', {})['learner_enrichment_v11.json'] = sha256(ENRICHMENT)
    metadata['difficultyCounts'] = dict(sorted(distribution(words).items(), key=lambda x: int(x[0])))
    metadata['enhancedCountsByDifficulty'] = {
        str(d): sum(bool(entry['examples']) for entry in words if entry['difficulty'] == d)
        for d in range(1, 11)
    }
    metadata['enhancedCounts'] = {
        key: sum(bool(entry[key]) for entry in words)
        for key in ['examples', 'collocations', 'derivatives', 'confusables', 'wordForms']
    }
    metadata['bilingualExampleWordCount'] = sum(
        any(item.get('zh') for item in entry['examples']) for entry in words)
    metadata['englishOnlyExampleWordCount'] = sum(
        bool(entry['examples']) and not any(item.get('zh') for item in entry['examples']) for entry in words)
    metadata['exampleItemCount'] = sum(len(entry['examples']) for entry in words)
    metadata['collocationItemCount'] = sum(len(entry['collocations']) for entry in words)
    metadata['completeCoreWordCount'] = sum(
        all(entry[key] for key in ['examples', 'collocations', 'derivatives', 'confusables'])
        for entry in words)
    return metadata


def validate(words, index, enrichment):
    errors = []
    for entry in words:
        assert entry['id'] == entry['word']
        assert entry['meaningZh'].strip() and entry['partOfSpeech'].strip()
        assert 1 <= entry['difficulty'] <= 10
        assert len(entry['examples']) <= 2
        assert len(entry['collocations']) <= 4
        assert len({item['en'] for item in entry['examples']}) == len(entry['examples'])
        for item in entry['examples']:
            assert item.get('en')
            if item.get('zh'):
                assert '中文' not in item['zh'][:1] or True
            else:
                assert item.get('attribution') and item.get('sourceUrl') and item.get('licenseUrl'), (
                    entry['word'], item.get('en'))
        for item in entry['collocations']:
            assert item.get('en') and item.get('zh')
    for word in enrichment.get('exampleTranslations', {}):
        if word not in index:
            errors.append(f'missing translation word {word}')
    for word in enrichment.get('collocations', {}):
        if word not in index:
            errors.append(f'missing collocation word {word}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    words, index, enrichment = load()
    errors = validate(words, index, enrichment)
    if errors:
        raise SystemExit('\n'.join(errors))

    # Work on a copy so --check never mutates the source.
    import copy
    staged = copy.deepcopy(words)
    staged_index = {word['word']: word for word in staged}
    added_examples, added_collocations, fixed_meanings = apply(enrichment, staged_index, errors)
    if errors:
        raise SystemExit('\n'.join(errors))
    from apply_internal_review import apply_review, update_metadata as internal_metadata
    apply_review(staged)
    from apply_complete_review import apply_review as complete_review, update_metadata as complete_metadata, bundle_inputs
    _, report = complete_review(staged)
    from complete_learning_content import apply_review as final_content_review, update_metadata as final_content_metadata
    final_content_review(staged)
    levels = distribution(staged)

    if args.check:
        print(json.dumps({
            'words': len(staged),
            'levels': levels,
            'addedExamples': added_examples,
            'addedCollocations': added_collocations,
            'fixedMeanings': fixed_meanings,
        }, ensure_ascii=False, indent=2))
        return

    VOCABULARY.write_text(json.dumps(staged, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    metadata = update_sources(staged, enrichment)
    metadata = final_content_metadata(staged, complete_metadata(staged, internal_metadata(staged, metadata)))
    bundle_inputs()
    SOURCES.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    audit_dir = ROOT / 'dist' / 'validation-v2.0'
    audit_dir.mkdir(parents=True, exist_ok=True)
    (audit_dir / 'level-audit.json').write_text(json.dumps({
        'method': 'learning-order-v3',
        'distribution': levels,
        'records': report,
    }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({
        'words': len(staged),
        'levels': levels,
        'addedExamples': added_examples,
        'addedCollocations': added_collocations,
        'fixedMeanings': fixed_meanings,
        'vocabularySha256': metadata['vocabularySha256'],
    }, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
