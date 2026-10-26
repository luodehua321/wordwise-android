#!/usr/bin/env python3
"""Audit complete source records and report real coverage gaps, without guessing data."""
import argparse
import hashlib
import json
from pathlib import Path
from build_vocabulary import validate, SOURCES
from british_pronunciations import load_source, supplement, completed_british


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ipa', type=Path, required=True, help='Pinned en_US.txt')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    assets = root / 'app/src/main/assets'
    raw = args.ipa.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == SOURCES['ipa']['sha256']
    us_review = json.loads((root / 'tools/american_ipa_corrections.json').read_text())
    from apply_complete_review import authored_pronunciations
    authored_american = authored_pronunciations('pronunciationUs')
    american = dict(line.split('\t', 1) for line in raw.decode().splitlines() if '\t' in line)
    british = {**completed_british(), **supplement(), **load_source()}
    payload = (assets / 'vocabulary.json').read_bytes()
    words = json.loads(payload)
    metadata = json.loads((assets / 'dictionary_sources.json').read_text())
    validate(words)
    assert len(words) == metadata['wordCount']
    assert hashlib.sha256(payload).hexdigest() == metadata['vocabularySha256']
    for word in words:
        expected_us = (us_review.get(word['word'], {}).get('ipa') or
                       authored_american.get(word['word']) or american.get(word['word'].lower()))
        assert expected_us and word['pronunciationUs'] == expected_us, word['word']
        assert word['pronunciationUk'] == british.get(word['word'], british.get(word['word'].lower(), '')), word['word']
    fields = ['examples', 'collocations', 'derivatives', 'confusables', 'wordForms', 'pronunciationUk']
    report = {'wordCount': len(words), 'sourceHash': metadata['vocabularySha256'],
              'allAmericanReadingsMatchPinnedSourceOrExplicitReview': True,
              'reviewedAmericanCorrections': sorted(us_review),
              'projectReviewedNewHeadwords': sorted(authored_american),
              'allBritishReadingsMatchSourceOrReviewedSupplement': True,
              'covered': {field: sum(bool(w[field]) for w in words) for field in fields},
              'missing': {field: [w['word'] for w in words if not w[field]] for field in fields}}
    report['covered']['bilingualExamples'] = sum(any(item['zh'] for item in w['examples']) for w in words)
    report['missing']['bilingualExamples'] = [w['word'] for w in words if not any(item['zh'] for item in w['examples'])]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'missing'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
