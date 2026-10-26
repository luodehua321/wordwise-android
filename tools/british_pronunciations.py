#!/usr/bin/env python3
"""Add attested British IPA without changing vocabulary selection or US data.

Run with --check to verify every packaged UK field against the pinned source, or
without arguments to enrich existing vocabulary, refresh metadata and source
notices. Then run prepare_vocabulary.py to rebuild the derived startup caches.
The full builder imports these same functions, so both paths are reproducible.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / 'app/src/main/assets'
SUPPLEMENT_PATH = HERE / 'british_ipa_supplement.json'
SOURCE_PATH = HERE / 'data/en_UK-972cd67.txt'
SOURCE = {
    'project': 'ipa-dict, en_UK (Received Pronunciation)',
    'repository': 'https://github.com/open-dict-data/ipa-dict',
    'commit': '972cd67dd5f96bc5660e28070cde26e3928abcd7',
    'path': 'data/en_UK.txt',
    'sha256': '221394caef0cf723b4f2df81a98ac33191293257b88aed5b1fb89466d3a0dc77',
    'license': 'GPL-3.0; derived from ipacards, original notice retained separately from US IPA',
    'licenseUrl': 'https://www.gnu.org/licenses/gpl-3.0.html',
    'upstreamRepository': 'https://github.com/melissaboiko/ipacards',
    'upstreamCredit': 'leoboiko (now melissaboiko), as credited by ipa-dict',
    'fields': ['pronunciationUk'],
    'sourceAsset': 'british_ipa/en_UK.txt',
    'generationSourceAsset': 'british_ipa/extract_british_ipa.py',
    'licenseAsset': 'british_ipa/LICENSE-GPL-3.0.txt',
}


def load_source(path=SOURCE_PATH):
    payload = Path(path).read_bytes()
    assert hashlib.sha256(payload).hexdigest() == SOURCE['sha256'], 'UK IPA source hash mismatch'
    rows = [line.split('\t') for line in payload.decode('utf-8').splitlines()]
    assert all(len(row) == 2 and row[0] and re.fullmatch(r'/[^/]+/(?:, /[^/]+/)*', row[1]) for row in rows)
    pronunciations = dict(rows)
    assert len(pronunciations) == len(rows) == 65119, 'UK IPA duplicate or missing source rows'
    return pronunciations


def supplement():
    rows = json.loads(SUPPLEMENT_PATH.read_text(encoding='utf-8'))
    assert all(re.fullmatch(r'/[^/]+/(?:, /[^/]+/)*', row['ipa']) and row['reference'] for row in rows.values())
    return {word: row['ipa'] for word, row in rows.items()}


def completed_british():
    path = ASSETS / 'oewn_detail_completions.json'
    if not path.exists(): return {}
    result = {r['word']:r['value'] for r in json.loads(path.read_text()) if r['field']=='pronunciationUk'}
    public = ASSETS / 'public_pronunciations_v15.json'
    if public.exists():
        result.update({word: readings['uk'] for word, readings in json.loads(public.read_text())['words'].items()
                       if readings.get('uk')})
    public_v17 = ASSETS / 'public_content_v17_applied.json'
    if public_v17.exists():
        result.update({word: readings['uk'] for word, readings in json.loads(public_v17.read_text())['words'].items()
                       if readings.get('uk')})
    return result


def enrich(words, path=SOURCE_PATH):
    pronunciations = {**supplement(), **load_source(path)}
    for word in words:
        # Exact spelling only. Do not guess dialects, substitute US IPA, generate
        # inflections, or rewrite American spellings to British spellings.
        word['pronunciationUk'] = pronunciations.get(word['word'], pronunciations.get(word['word'].lower(), ''))


def update_metadata(metadata, words):
    metadata['contentVersion'] = max(10, metadata.get('contentVersion', 0))
    metadata['britishSupplementWordCount'] = len(({word['word'] for word in words} & supplement().keys()) - load_source().keys())
    metadata['britishSupplementSha256'] = hashlib.sha256(SUPPLEMENT_PATH.read_bytes()).hexdigest()
    metadata['sources']['ipaUk'] = SOURCE
    metadata['britishPronunciationWordCount'] = sum(bool(word['pronunciationUk']) for word in words)
    metadata['missingBritishPronunciationWordCount'] = sum(not word['pronunciationUk'] for word in words)
    metadata['britishPronunciationDescriptionZh'] = (
        '英式音标取自独立 en_UK Received Pronunciation 数据集，原文不改写。复核的大写学习词可匹配相同小写拼写，月份等独立读音使用明确补充项。'
        f'补充表包含{len(supplement())}个项目复核词（参考ECDICT及词典），随包保留补充表；其余缺失时留空，不用美式音标替代，不机械转换。'
        '英国数据源自 ipacards，遵循 GPL-3.0；与美式数据的 MIT/BSD 许可分别保留。'
        '完整原始快照、上游说明、GPL原文及可复现抽取代码随包放在 british_ipa/。')


def bundle_sources(path=SOURCE_PATH):
    load_source(path)
    bundle = ASSETS / 'british_ipa'
    bundle.mkdir(exist_ok=True)
    (bundle / 'supplement.json').write_bytes(SUPPLEMENT_PATH.read_bytes())
    (bundle / 'en_UK.txt').write_bytes(Path(path).read_bytes())
    readme = (HERE / 'data/ipa-dict-972cd67-README.md').read_bytes()
    assert hashlib.sha256(readme).hexdigest() == '7251a8159f6d1d1a981ae5e79e616e3c7181950c1b6023042a727056a47e87a0'
    (bundle / 'ipa-dict-README.md').write_bytes(readme)
    license_text = (HERE / 'licenses/IPACARDS-GPL-3.0.txt').read_bytes()
    assert hashlib.sha256(license_text).hexdigest() == '8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903'
    (bundle / 'LICENSE-GPL-3.0.txt').write_bytes(license_text)
    (bundle / 'extract_british_ipa.py').write_bytes((HERE / 'extract_british_ipa.py').read_bytes())
    (bundle / 'SOURCE-NOTICE.txt').write_text(
        'British English IPA data — original source and reproducible extraction\n\n'
        'ipa-dict en_UK (Received Pronunciation), based on ipacards by leoboiko\n'
        '(now melissaboiko). UK data retains GPL-3.0, distinct from US MIT/BSD data.\n'
        'https://github.com/open-dict-data/ipa-dict\n'
        'Pinned commit: ' + SOURCE['commit'] + '\n'
        'Original path: data/en_UK.txt\nSHA-256: ' + SOURCE['sha256'] + '\n'
        'https://github.com/melissaboiko/ipacards\n\n'
        'en_UK.txt is the complete, unmodified UTF-8 source, not a binary-only copy.\n'
        'ipa-dict-README.md reproduces the pinned upstream attribution and licensing.\n'
        'LICENSE-GPL-3.0.txt reproduces the upstream GPL-3.0 license in full.\n'
        'The app selects exact spellings from this file; pronunciation text is unchanged.\n'
        'supplement.json contains project-reviewed missing readings, with references.\n'
        'No automatic US-to-UK transformation is applied. Unattested entries are empty.\n'
        'Run: python3 extract_british_ipa.py ../vocabulary.json output.json\n'
        'Omit output.json for read-only verification of every UK field.\n'
        'The same extraction is used by tools/british_pronunciations.py and\n'
        'tools/build_vocabulary.py in the project source. The original source and\n'
        'extraction code are provided alongside the data under GPL-3.0.\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--source', type=Path, default=SOURCE_PATH)
    args = parser.parse_args()
    source = {**supplement(), **load_source(args.source)}
    vocabulary = ASSETS / 'vocabulary.json'
    words = json.loads(vocabulary.read_text(encoding='utf-8'))
    metadata_path = ASSETS / 'dictionary_sources.json'
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
    if args.check:
        source = {**completed_british(), **source}
        assert all(word['pronunciationUk'] == source.get(word['word'], source.get(word['word'].lower(), '')) for word in words)
        assert metadata['sources']['ipaUk'] == SOURCE
        assert metadata['britishSupplementSha256'] == hashlib.sha256(SUPPLEMENT_PATH.read_bytes()).hexdigest()
        assert (ASSETS / 'british_ipa/supplement.json').read_bytes() == SUPPLEMENT_PATH.read_bytes()
        assert metadata['vocabularySha256'] == hashlib.sha256(vocabulary.read_bytes()).hexdigest()
        assert metadata['britishPronunciationWordCount'] == sum(bool(word['pronunciationUk']) for word in words)
        assert metadata['missingBritishPronunciationWordCount'] == sum(not word['pronunciationUk'] for word in words)
        assert (ASSETS / 'british_ipa/en_UK.txt').read_bytes() == args.source.read_bytes()
        assert (ASSETS / 'british_ipa/extract_british_ipa.py').read_bytes() == (HERE / 'extract_british_ipa.py').read_bytes()
    else:
        enrich(words, args.source)
        extra = completed_british()
        for word in words:
            if not word['pronunciationUk']: word['pronunciationUk'] = extra.get(word['word'], '')
        update_metadata(metadata, words)
        payload = json.dumps(words, ensure_ascii=False, separators=(',', ':')) + '\n'
        vocabulary.write_text(payload, encoding='utf-8')
        metadata['vocabularySha256'] = hashlib.sha256(payload.encode('utf-8')).hexdigest()
        metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        bundle_sources(args.source)
        # Keep existing licenses and sentence-credit footers. Refresh the shared
        # attribution header and append the new license only when absent.
        notices = ASSETS / 'dictionary_licenses.txt'
        text = notices.read_text(encoding='utf-8')
        marker = '\n\n===== BRITISH IPA / ipacards GPL-3.0 =====\n\n'
        text = text.split(marker)[0]
        first_license = text.index('\n\n===== ')
        text = (HERE / 'attribution_header.txt').read_text(encoding='utf-8') + text[first_license:]
        if '===== IPACARDS-GPL-3.0.txt =====' not in text:
            text += '\n\n===== IPACARDS-GPL-3.0.txt =====\n\n'
            text += (HERE / 'licenses/IPACARDS-GPL-3.0.txt').read_text(encoding='utf-8')
        notices.write_text(text, encoding='utf-8')
    covered = sum(bool(word['pronunciationUk']) for word in words)
    print(f'UK IPA: {covered:,}/{len(words):,} source-matched or reviewed spellings; {len(words)-covered:,} empty; source verified.')


if __name__ == '__main__':
    main()
