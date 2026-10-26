#!/usr/bin/env python3
"""Build the shipped offline vocabulary from pinned, checksummed public datasets.

Python 3.9+; standard library only. Network is needed only when an input is not
provided/cached. This script is NOT run by the Android application.
"""
import argparse
import csv
import gzip
import hashlib
import json
import re
import urllib.request
import tarfile
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path
from british_pronunciations import (
    SOURCE as UK_SOURCE, SOURCE_PATH as UK_SOURCE_PATH,
    enrich as enrich_british, update_metadata as update_british_metadata,
    bundle_sources as bundle_british_sources,
)

from enrich_relations import enrich_relations
from vocabulary_levels import (classify, assign_levels, THRESHOLDS, VERSION as LEVEL_VERSION,
                               NGSL, NGSL_SOURCE)
HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / 'app' / 'src' / 'main' / 'assets'
SOURCES = {
    'ecdict': {
        'project': 'ECDICT',
        'repository': 'https://github.com/skywind3000/ECDICT',
        'commit': 'bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b',
        'path': 'ecdict.csv',
        'sha256': '1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf',
        'license': 'MIT',
        'fields': ['word', 'partOfSpeech', 'meaningZh', 'rank', 'collocations', 'derivatives.meaningZh', 'wordForms'],
    },
    'ipa': {
        'project': 'ipa-dict, en_US (General American)',
        'repository': 'https://github.com/open-dict-data/ipa-dict',
        'commit': '972cd67dd5f96bc5660e28070cde26e3928abcd7',
        'path': 'data/en_US.txt',
        'sha256': '2af6f154a5c363275f052d1f85acedef38ed185ca9745aa4314be77f6b70de67',
        'license': 'MIT; includes CMUdict-derived data (BSD-style notice retained)',
        'fields': ['pronunciationUs'],
    },
    'ipaUk': UK_SOURCE,
    'tatoeba': {
        'project': 'Tatoeba English–Mandarin sentence pairs, selected by ManyThings.org',
        'url': 'https://www.manythings.org/anki/cmn-eng.zip',
        'snapshot': '2026-02-13',
        'path': 'cmn-eng.zip',
        'sha256': 'd606361aa7312dcdfb15ef4fc51cb1e7ed8bc2dc608a7b192626cd36e8403106',
        'license': 'CC BY 2.0 FR; per-sentence owner and ID retained',
        'licenseUrl': 'https://creativecommons.org/licenses/by/2.0/fr/',
        'fields': ['examples'],
    },
    'wordnet': {
        'project': 'Princeton WordNet 3.0',
        'url': 'https://wordnetcode.princeton.edu/3.0/WNdb-3.0.tar.gz',
        'path': 'WNdb-3.0.tar.gz',
        'sha256': '658b1ba191f5f98c2e9bae3e25c186013158f30ef779f191d2a44e5d25046dc8',
        'license': 'Princeton WordNet 3.0 license',
        'licenseUrl': 'https://wordnet.princeton.edu/license-and-commercial-use',
        'fields': ['collocations', 'derivatives'],
    },
    'oewn': {
        'project': 'Open English WordNet 2025, derived from Princeton WordNet',
        'url': 'https://en-word.net/static/english-wordnet-2025.xml.gz',
        'repository': 'https://github.com/globalwordnet/english-wordnet',
        'release': '2025-edition',
        'path': 'english-wordnet-2025.xml.gz',
        'sha256': '9ca6d1dcb75f822fdd66617f7d9da48142ace38dd544d6ad5e2feca1674ad3fe',
        'license': 'CC BY 4.0; underlying Princeton WordNet 3.1 notice retained',
        'licenseUrl': 'https://creativecommons.org/licenses/by/4.0/',
        'fields': ['examples', 'derivatives'],
    },
    'tatoebaEnglish': {
        'project': 'Proofread English sentences from Tatoeba / ManyThings.org Russian–English archive (English side only)',
        'url': 'https://www.manythings.org/anki/rus-eng.zip',
        'snapshot': '2026-02-13',
        'path': 'rus-eng.zip',
        'sha256': '1534e267976f43ae97e966d2ac9dc1e9128fdd0efdf08eceee21cd88ff20682c',
        'license': 'CC BY 2.0 FR; English sentence owner and ID retained',
        'licenseUrl': 'https://creativecommons.org/licenses/by/2.0/fr/',
        'fields': ['examples'],
    },
}
FORM_LABELS = {'p': '过去式', 'd': '过去分词', 'i': '现在分词', '3': '第三人称单数',
               's': '复数', 'r': '比较级', 't': '最高级'}
POS_NAMES = {'n': 'n.', 'v': 'v.', 'vt': 'vt.', 'vi': 'vi.', 'a': 'adj.',
             'adj': 'adj.', 'ad': 'adv.', 'adv': 'adv.', 'pron': 'pron.',
             'prep': 'prep.', 'conj': 'conj.', 'aux': 'aux.', 'num': 'num.',
             'interj': 'interj.', 'int': 'interj.', 'art': 'art.', 'det': 'det.'}


def fetch(name, supplied):
    source = SOURCES[name]
    path = Path(supplied) if supplied else HERE / '.cache' / source['path'].replace('/', '_')
    if name == 'tatoeba' and not supplied:
        path = HERE / 'data' / 'cmn-eng-2026-02-13.zip'
    if name == 'tatoebaEnglish' and not supplied:
        path = HERE / 'data' / 'rus-eng-2026-02-13.zip'
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        if 'url' in source:
            url = source['url']
        else:
            slug = source['repository'].removeprefix('https://github.com/')
            url = f"https://raw.githubusercontent.com/{slug}/{source['commit']}/{source['path']}"
        print(f'Downloading {source["project"]}…')
        request = urllib.request.Request(url, headers={'User-Agent': 'LingoOfflineVocabulary/2.0'})
        with urllib.request.urlopen(request, timeout=120) as response:
            path.write_bytes(response.read())
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != source['sha256']:
        raise ValueError(f'{path}: SHA-256 mismatch; expected {source["sha256"]}, got {digest}')
    return path


def part_of_speech(translation, raw):
    values = re.findall(r'(?:^|\n)\s*(n|v|vt|vi|a|adj|ad|adv|pron|prep|conj|aux|num|interj|int|art|det)\.', translation)
    result = list(dict.fromkeys(POS_NAMES[value] for value in values))
    return ' / '.join(result) or raw.strip()


def clean_meaning(text):
    # ECDICT stores literal escaped newlines. Preserve sense breaks and remove
    # domain-specific trailing entries, which are distracting for beginners.
    text = text.replace('\\r\\n', '\n').replace('\\n', '\n').replace('\\r', '\n')
    lines = [line.strip() for line in text.splitlines()
             if line.strip() and not line.lstrip().startswith('[')]
    return '\n'.join(lines)


def parse_word_forms(exchange, word):
    """Read ECDICT's explicit inflection list; never guess forms from spelling."""
    result = {}
    for pair in (exchange or '').split('/'):
        if ':' not in pair:
            continue
        key, values = pair.split(':', 1)
        label = FORM_LABELS.get(key)
        if not label:
            continue
        for value in values.split(','):
            value = value.strip()
            if re.fullmatch('[a-z]+', value):
                labels = result.setdefault(value, [])
                if label not in labels:
                    labels.append(label)
    return [{'word': value, 'meaningZh': ' / '.join(labels)} for value, labels in result.items()][:6]


def level(row):
    return classify(row)[0]


def learning_rows(ecdict_path):
    review = json.loads((HERE / 'content_review_v10.json').read_text())
    with Path(ecdict_path).open(encoding='utf-8', newline='') as stream:
        rows = list(csv.DictReader(stream))
    names = {r['word'] for r in rows}
    by_lower = {r['word'].lower(): r for r in rows}
    for name in review:
        if name not in names and name != name.lower() and name.lower() in by_lower:
            row = dict(by_lower[name.lower()])
            row['word'] = name
            # A homograph's corpus frequency is not the frequency of a month/name.
            row['frq'] = '0'
            row['_caseSenseClone'] = True
            rows.append(row)
    for row in rows:
        word = row['word']
        row['_sourceWord'] = word
        if word not in review and word.lower() in review:
            row['word'] = word.lower()
        yield row


def build(ecdict_path, ipa_path, count):
    ipa = {}
    with ipa_path.open(encoding='utf-8') as stream:
        for line in stream:
            if '\t' in line:
                word, pronunciation = line.strip().split('\t', 1)
                ipa[word] = pronunciation
    with (HERE / 'core_enrichment.json').open(encoding='utf-8') as stream:
        extra = json.load(stream)
    for filename in ['advanced_enrichment.json', 'practical_enrichment_v3.json', 'practical_enrichment_v4.json', 'practical_enrichment_v8.json', 'practical_enrichment_v9.json']:
        with (HERE / filename).open(encoding='utf-8') as stream:
            for word, content in json.load(stream).items():
                target = extra.setdefault(word, {})
                for key, items in content.items():
                    existing = target.setdefault(key, [])
                    for item in items:
                        identity = 'word' if key in {'derivatives', 'confusables'} else 'en'
                        if not any(previous.get(identity) == item.get(identity) for previous in existing):
                            existing.append(item)
    meaning_corrections = json.loads((HERE / 'meaning_corrections_v4.json').read_text(encoding='utf-8'))
    form_corrections = json.loads((HERE / 'word_form_corrections.json').read_text(encoding='utf-8'))
    review = json.loads((HERE / 'content_review_v10.json').read_text())
    us_review = json.loads((HERE / 'american_ipa_corrections.json').read_text())
    words = []
    source_rows = {}
    mandatory = set(review) | set(NGSL)
    retained = set(json.loads((HERE / 'data/retained-word-ids-v9.json').read_text()))
    for row in learning_rows(ecdict_path):
        source_word = row['word']
        word = (source_word if source_word in review else source_word.lower()
                if source_word.lower() in review else source_word)
        # Preserve existing spelling IDs. Only explicitly reviewed capitalized
        # learning words are admitted; proper names are not bulk imported.
        if not re.fullmatch(r'[a-z]+', word) and word not in review:
            continue
        ipa_key = word.lower()
        if ipa_key not in ipa:
            continue
        rank = int(row['frq']) if row['frq'].isdigit() else 0
        required = word in mandatory or bool({'zk', 'gk'} & set(row['tag'].split()))
        if rank <= 0 and not required:
            continue
        correction = review.get(word, {})
        meaning = correction.get('meaningZh', clean_meaning(row['translation']))
        pos = correction.get('partOfSpeech', part_of_speech(meaning, row['pos']))
        if not re.search(r'[\u4e00-\u9fff]', meaning) or not pos:
            continue
        source_rows[word] = row
        entry = {
            'id': word,
            'word': word,
            'pronunciationUs': ipa[ipa_key],
            'partOfSpeech': pos,
            'meaningZh': meaning,
            'difficulty': level(row),
            'rank': rank if rank > 0 else 1000000,
            'examples': [],
            'collocations': [],
            'derivatives': [],
            'confusables': [],
            'wordForms': parse_word_forms(row.get('exchange', ''), word),
        }
        if row.get('_caseSenseClone'):
            entry['wordForms'] = []
        if word in us_review:
            entry['pronunciationUs'] = us_review[word]['ipa']
        if word in form_corrections:
            entry['wordForms'] = form_corrections[word]['forms']
        if word in extra:
            entry.update(extra[word])
        if word in meaning_corrections:
            correction = meaning_corrections[word]
            entry['meaningZh'] = correction['meaningZh']
            if 'partOfSpeech' in correction:
                entry['partOfSpeech'] = correction['partOfSpeech']
        # Keep the same display limits for original and corpus content.
        for key, limit in [('examples', 2), ('collocations', 4), ('derivatives', 4)]:
            entry[key] = entry[key][:limit]
        # Content patches override source mistakes; difficulty is assigned
        # once below, after old enrichment has been applied.
        if word in review:
            patch = review[word]
            entry['meaningZh'] = patch['meaningZh']
            entry['partOfSpeech'] = patch['partOfSpeech']
            entry['examples'] = (patch.get('examples', []) + entry['examples'])[:2]
            entry['collocations'] = (patch.get('collocations', []) + entry['collocations'])[:4]
        words.append(entry)
    # Rank within each heuristic difficulty band. Stable tiebreaker ensures byte
    # reproducibility. Public corpus rank remains available for auditing.
    words.sort(key=lambda item: (item['rank'], item['word']))
    selected = words[:count]
    chosen = {w['word'] for w in selected}
    for entry in words[count:]:
        row = source_rows[entry['word']]
        if entry['word'] in mandatory or entry['word'] in retained or bool({'zk', 'gk'} & set(row['tag'].split())):
            selected.append(entry)
            chosen.add(entry['word'])
    selected.sort(key=lambda item: (item['rank'], item['word']))
    assign_levels(selected, source_rows)
    missing = set(extra) - {entry['word'] for entry in selected}
    if missing:
        raise ValueError(f'Enhanced entries outside selected public word list: {sorted(missing)}')
    if len(selected) < count:
        raise ValueError(f'Only {len(selected)} candidates for requested count={count}')
    return selected


def load_wordnet(path):
    """Read explicit lexical compounds and '+' derivational pointers only.

    WordNet's antonyms or similar spellings are deliberately NOT presented as
    learner confusables. No suffix-based invented derivation is used.
    """
    records = {}
    compounds = set()
    with tarfile.open(path) as archive:
        for pos in ['noun', 'verb', 'adj', 'adv']:
            for line in archive.extractfile('dict/data.' + pos).read().decode().splitlines():
                if not line or not line[0].isdigit():
                    continue
                fields = line.split('|', 1)[0].split()
                offset, _, kind, count = fields[:4]
                count = int(count, 16)
                lemmas = [re.sub(r'\((?:a|p|ip)\)$', '', fields[4 + 2 * i]) for i in range(count)]
                for lemma in lemmas:
                    if re.fullmatch(r'[a-z]+(?:_[a-z]+){1,3}', lemma):
                        compounds.add(lemma.replace('_', ' '))
                cursor = 4 + 2 * count
                pointer_count = int(fields[cursor])
                pointers = []
                for i in range(pointer_count):
                    symbol, target, target_kind, lexical = fields[cursor + 1 + 4 * i:cursor + 5 + 4 * i]
                    if symbol == '+' and lexical != '0000':
                        pointers.append((target, target_kind.replace('s', 'a'), int(lexical[:2], 16), int(lexical[2:], 16)))
                records[(offset, kind.replace('s', 'a'))] = (lemmas, pointers)
    relations = defaultdict(set)
    for lemmas, pointers in records.values():
        for target, kind, source_index, target_index in pointers:
            target_lemmas = records[(target, kind)][0]
            if source_index and target_index:
                source, destination = lemmas[source_index - 1], target_lemmas[target_index - 1]
                if source != destination and re.fullmatch('[a-z]+', source) and re.fullmatch('[a-z]+', destination):
                    relations[source].add(destination)
    return compounds, relations


def load_oewn(path):
    """Keep explicit sense relations and examples, never infer spelling families.

    Pertainyms are recorded separately from derivation: e.g. medical/medicine
    is a word-family association, not a claim that a suffix can produce it.
    Reverse links let the learner see the family from either entry.
    """
    resource = ET.fromstring(gzip.decompress(path.read_bytes()))[0]
    entries = resource.findall('LexicalEntry')
    senses = {sense.get('id'): entry.find('Lemma').get('writtenForm')
              for entry in entries for sense in entry.findall('Sense')}
    sense_pos = {sense.get('id'): entry.find('Lemma').get('partOfSpeech')
                 for entry in entries for sense in entry.findall('Sense')}
    synsets = {synset.get('id'): synset for synset in resource.findall('Synset')}
    relations = defaultdict(dict)
    examples = defaultdict(list)
    for entry in entries:
        word = entry.find('Lemma').get('writtenForm')
        if not re.fullmatch('[a-z]+', word):
            continue
        for sense in entry.findall('Sense'):
            for relation in sense.findall('SenseRelation'):
                kind = relation.get('relType')
                related = senses.get(relation.get('target'))
                if kind not in {'derivation', 'pertainym'} or not related:
                    continue
                if word == related or not re.fullmatch('[a-z]+', related):
                    continue
                if kind == 'pertainym':
                    # Pertainyms can also mean semantic membership (e.g.
                    # idea/psychomotor or gold/auric), which is NOT a useful
                    # derived word family. Accept only an explicit adjective /
                    # adverb pointer with the attested -ly spelling pattern.
                    source_pos = entry.find('Lemma').get('partOfSpeech')
                    target_pos = sense_pos.get(relation.get('target'))
                    if source_pos == 'r' and target_pos in {'a', 's'}:
                        adjective, adverb = related, word
                    elif target_pos == 'r' and source_pos in {'a', 's'}:
                        adjective, adverb = word, related
                    else:
                        continue
                    attested_shapes = {adjective + 'ly', adjective[:-1] + 'ily',
                                       adjective[:-1] + 'ly', adjective[:-1] + 'y',
                                       adjective + 'ally', adjective + 'y'}
                    if adverb not in attested_shapes:
                        continue
                # Prefer an explicit derivation if the pair also has a broader
                # pertainym link. The precise relation remains in the JSON.
                if relations[word].get(related) != 'derivation':
                    relations[word][related] = kind
                    relations[related][word] = kind
            for example in synsets[sense.get('synset')].findall('Example'):
                text = example.text or ''
                tokens = re.findall(r"[a-z]+(?:'[a-z]+)?", text.lower())
                # WordNet also contains short noun phrases. Require a clause
                # cue to avoid calling a fragment such as "economic assistance
                # to depressed areas" a full sentence in the learning UI.
                clause = re.search(r"\b(?:i|you|he|she|it|we|they|is|are|was|were|has|have|had|can|could|will|would|should|may|might|must|did|does|seems?|became|found|gave|saw|went|felt|made|said|took|came|gets?|got)\b", text.lower())
                subjectless_past = re.match(r'^(?:had|has|was|were|felt|found|gave|saw|went|made|said|took|came|got)\b', text.lower())
                if 4 <= len(tokens) <= 24 and clause and not subjectless_past:
                    examples[word].append((text, sense.get('synset')))
    return relations, examples


def enrich(words, ecdict_path, tatoeba_path, wordnet_path, oewn_path, english_path):
    selected = {entry['word']: entry for entry in words}
    # Exclude reviewed corpus mistakes, retaining original texts in the source
    # archive rather than silently rewriting and misattributing them.
    excluded_sentences = json.loads((HERE / 'sentence_exclusions.json').read_text(encoding='utf-8'))
    compounds, derivational = load_wordnet(wordnet_path)
    word_families, oewn_examples = load_oewn(oewn_path)
    phrase_corrections = json.loads((HERE / 'phrase_corrections.json').read_text(encoding='utf-8'))
    dictionary = {}
    phrases = {}
    forms = defaultdict(set)
    with ecdict_path.open(encoding='utf-8', newline='') as stream:
        for row in csv.DictReader(stream):
            word = row['word']
            meaning = clean_meaning(row['translation'])
            if re.fullmatch('[a-z]+', word) and meaning:
                dictionary[word] = {'meaning': meaning, 'pos': part_of_speech(meaning, row['pos']), 'rank': int(row['frq']) if row['frq'].isdigit() and int(row['frq']) > 0 else 999999}
            if word in selected:
                for pair in row['exchange'].split('/'):
                    if ':' in pair:
                        key, values = pair.split(':', 1)
                        if key in {'p', 'd', 's', '3', 'i', 'r', 't'}:
                            for value in values.split(','):
                                if re.fullmatch('[a-z]+', value):
                                    forms[value].add(word)
            if not re.fullmatch('[a-z]+(?: [a-z]+){1,3}', word):
                continue
            marked = row['oxford'] == '1' or bool(row['collins'])
            if word not in compounds and not marked:
                continue
            # Every phrase must be a recorded dictionary expression. Requiring
            # known components avoids obscure scientific compounds and names.
            if any(token not in selected for token in word.split()):
                continue
            if not meaning:
                meaning = re.sub(r'\[[^\]]+\]', '', row['translation'].split('\\n')[0]).strip()
            if not re.search('[\u4e00-\u9fff]', meaning):
                continue
            if re.search(r'(?:<贬>|<旧>|\b(?:arch|obs)\.)', meaning):
                continue
            meaning = phrase_corrections.get(word, meaning)
            score = (not marked, max(selected[token]['rank'] for token in word.split()), len(word.split()), word)
            phrases[word] = (score, meaning, marked)
    phrase_index = defaultdict(list)
    for phrase, (score, meaning, marked) in sorted(phrases.items(), key=lambda item: item[1][0]):
        for word in set(phrase.split()):
            phrase_index[word].append({
                'en': phrase, 'zh': meaning,
                'source': 'ECDICT 词典短语' if marked else 'WordNet 词典短语 · ECDICT 中文',
                'sourceUrl': 'https://github.com/skywind3000/ECDICT' if marked else 'https://wordnet.princeton.edu/',
            })
    for word, entry in selected.items():
        existing = {item['en'].lower() for item in entry['collocations']}
        for item in phrase_index[word]:
            if len(entry['collocations']) >= 4:
                break
            if item['en'].lower() not in existing:
                entry['collocations'].append(item)
                existing.add(item['en'].lower())
        known = {item['word'] for item in entry['derivatives']}
        candidates = [related for related in derivational[word] if related in dictionary
                      and not re.search(r'(?:的变形|的复数)', dictionary[related]['meaning'])]
        candidates.sort(key=lambda related: (dictionary[related]['rank'], related))
        for related in candidates:
            if len(entry['derivatives']) >= 4:
                break
            if related not in known:
                entry['derivatives'].append({
                    'word': related, 'meaningZh': dictionary[related]['meaning'],
                    'source': 'WordNet 派生关系 · ECDICT 中文',
                    'sourceUrl': 'https://wordnet.princeton.edu/',
                })
                known.add(related)
        # The newer explicit lexical links cover families absent from WordNet
        # 3.0, including the reverse adverb/adjective association.
        related_words = [related for related in word_families[word]
                         if related in dictionary and re.search('[\u4e00-\u9fff]', dictionary[related]['meaning'])
                         and not re.search(r'(?:的变形|的复数)', dictionary[related]['meaning'])]
        related_words.sort(key=lambda related: (
            word_families[word][related] != 'derivation', dictionary[related]['rank'], related))
        for related in related_words:
            if len(entry['derivatives']) >= 4:
                break
            if related not in known:
                relation = word_families[word][related]
                if relation == 'pertainym':
                    # Avoid a homograph's incompatible Chinese sense, e.g.
                    # multiple/multiply when ECDICT only defines the verb
                    # multiply rather than the rare adverb.
                    source_pos = dictionary[word]['pos']
                    target_pos = dictionary[related]['pos']
                    if not (('adv.' in source_pos and 'adj.' in target_pos) or
                            ('adj.' in source_pos and 'adv.' in target_pos)):
                        continue
                entry['derivatives'].append({
                    'word': related,
                    'meaningZh': ('词族关联 · ' if relation == 'pertainym' else '') + dictionary[related]['meaning'],
                    'relation': relation,
                    'source': 'Open English WordNet 2025 · ECDICT 中文',
                    'sourceUrl': 'https://en-word.net/',
                    'licenseUrl': SOURCES['oewn']['licenseUrl'],
                })
                known.add(related)
    examples = defaultdict(list)
    with zipfile.ZipFile(tatoeba_path) as archive:
        for line in archive.read('cmn.txt').decode('utf-8').splitlines():
            en, zh, attribution = line.split('\t')
            if en in excluded_sentences:
                continue
            tokens = re.findall(r"[a-z]+(?:'[a-z]+)?", en.lower())
            if not 4 <= len(tokens) <= 20 or len(zh) > 120:
                continue
            ids = re.findall(r'#(\d+) \(([^)]+)\)', attribution)
            if len(ids) != 2:
                raise ValueError('Missing original Tatoeba owners/IDs: ' + attribution)
            item = {
                'en': en, 'zh': zh, 'source': 'Tatoeba / ManyThings.org',
                'attribution': attribution,
                'sourceUrl': 'https://tatoeba.org/en/sentences/show/' + ids[0][0],
                'translationSourceUrl': 'https://tatoeba.org/en/sentences/show/' + ids[1][0],
                'licenseUrl': SOURCES['tatoeba']['licenseUrl'],
            }
            targets = set()
            for token in set(tokens):
                if token in selected:
                    targets.add(token)
                targets.update(forms[token])
            # Prefer full, reasonably short sentences with familiar surrounding
            # words. Literal matches beat inflected-form matches. Original
            # curated bilingual examples always remain first.
            unknown = sum(token not in dictionary and token not in {'i', 'tom', 'mary', 'john'} for token in tokens)
            for word in targets:
                score = (word not in tokens, unknown, abs(len(tokens) - 9), len(en), en, zh)
                examples[word].append((score, item))
    used = {}
    for word, entry in selected.items():
        existing = {item['en'] for item in entry['examples']}
        for _, item in sorted(examples[word], key=lambda pair: pair[0]):
            if len(entry['examples']) >= 2:
                break
            if item['en'] in existing:
                continue
            entry['examples'].append(item)
            existing.add(item['en'])
            used[(item['sourceUrl'], item['translationSourceUrl'])] = item
    bilingual_used = sorted(used.values(), key=lambda item: (item['sourceUrl'], item['translationSourceUrl']))

    # Bilingual content stays first. The English side of this larger, proofread
    # archive adds natural complete sentences; Russian translations are neither
    # shipped nor relabeled as Chinese. Keep just the English author's credit.
    english_examples = defaultdict(dict)
    with zipfile.ZipFile(english_path) as archive:
        for line in archive.read('rus.txt').decode('utf-8').splitlines():
            en, _, attribution = line.split('\t')
            tokens = re.findall(r"[a-z]+(?:'[a-z]+)?", en.lower())
            if en in excluded_sentences or not 4 <= len(tokens) <= 20:
                continue
            ids = re.findall(r'#(\d+) \(([^)]+)\)', attribution)
            if len(ids) != 2:
                raise ValueError('Missing Tatoeba English author/ID: ' + attribution)
            item = {
                'en': en, 'zh': '', 'source': 'Tatoeba / ManyThings.org · 英文原句',
                'attribution': f'Tatoeba #{ids[0][0]} ({ids[0][1]}) · ManyThings.org · CC BY 2.0 FR',
                'sourceUrl': 'https://tatoeba.org/en/sentences/show/' + ids[0][0],
                'licenseUrl': SOURCES['tatoebaEnglish']['licenseUrl'],
            }
            targets = set()
            for token in set(tokens):
                if token in selected:
                    targets.add(token)
                targets.update(forms[token])
            unknown = sum(token not in dictionary and token not in {'i', 'tom', 'mary', 'john'} for token in tokens)
            for word in targets:
                if len(selected[word]['examples']) >= 2:
                    continue
                score = (word not in tokens, unknown, abs(len(tokens) - 9), len(en), en, int(ids[0][0]))
                previous = english_examples[word].get(en)
                if previous is None or score < previous[0]:
                    english_examples[word][en] = (score, item)
    extra_used = {}
    for word, entry in selected.items():
        existing = {item['en'] for item in entry['examples']}
        for _, item in sorted(english_examples[word].values(), key=lambda pair: pair[0]):
            if len(entry['examples']) >= 2:
                break
            if item['en'] not in existing:
                entry['examples'].append(item)
                existing.add(item['en'])
                extra_used[(item['sourceUrl'], item['en'])] = item
        # OEWN example sentences are tied to the entry's sense. Additionally
        # require the actual lemma or an attested inflection in the sentence:
        # a shared synset alone must not attach another synonym's example.
        candidates = []
        for en, synset in oewn_examples[word]:
            tokens = re.findall(r"[a-z]+(?:'[a-z]+)?", en.lower())
            if en in excluded_sentences or not any(token == word or word in forms[token] for token in tokens):
                continue
            unknown = sum(token not in dictionary and token not in {'i', 'tom', 'mary', 'john'} for token in tokens)
            candidates.append(((word not in tokens, unknown, abs(len(tokens) - 9), en, synset), {
                'en': en, 'zh': '', 'source': 'Open English WordNet 2025',
                'attribution': 'Open English WordNet team · Princeton WordNet · ' + synset + ' · CC BY 4.0',
                'sourceUrl': 'https://github.com/globalwordnet/english-wordnet/releases/tag/2025-edition',
                'synsetId': synset, 'licenseUrl': SOURCES['oewn']['licenseUrl'],
            }))
        for _, item in sorted(candidates, key=lambda pair: pair[0]):
            if len(entry['examples']) >= 2:
                break
            if item['en'] not in existing:
                entry['examples'].append(item)
                existing.add(item['en'])
                extra_used[(item['sourceUrl'], item['en'])] = item
    return bilingual_used, sorted(extra_used.values(), key=lambda item: (item['sourceUrl'], item['en']))


def validate(words):
    ids = set()
    for entry in words:
        assert entry['id'] == entry['word'] and entry['id'] not in ids
        ids.add(entry['id'])
        assert entry['pronunciationUs'].startswith('/')
        assert not entry['pronunciationUk'] or entry['pronunciationUk'].startswith('/')
        assert entry['meaningZh'] and entry['partOfSpeech']
        assert 1 <= entry['difficulty'] <= 10 and entry['rank'] > 0
        assert len(entry['examples']) <= 2 and len(entry['collocations']) <= 4 and len(entry['derivatives']) <= 4
        assert all({'en', 'zh'} <= set(item) and item['en'] for item in entry['examples'])
        assert all({'en', 'zh'} <= set(item) and item['en'] and item['zh'] for item in entry['collocations'])
        assert all({'word', 'meaningZh'} <= set(item) and item['word'] and item['meaningZh'] for item in entry['derivatives'])
        relatives = [item['word'] for item in entry['derivatives']]
        assert entry['word'] not in relatives and len(set(relatives)) == len(relatives)
        assert len({item['en'] for item in entry['examples']}) == len(entry['examples']), entry['word']
        for item in entry['examples']:
            if item.get('source') == 'Tatoeba / ManyThings.org':
                assert all(item[key] for key in ['attribution', 'sourceUrl', 'translationSourceUrl', 'licenseUrl'])
                assert len(re.findall(r'#\d+ \([^)]+\)', item['attribution'])) == 2
            if not item['zh']:
                assert all(item.get(key) for key in ['attribution', 'sourceUrl', 'licenseUrl'])
        assert all(set(item) == {'word', 'meaningZh', 'note'} and all(item.values()) for item in entry['confusables'])
        assert len(entry['wordForms']) <= 6
        assert all(set(item) == {'word', 'meaningZh'} and item['word'] and item['meaningZh'] for item in entry['wordForms'])
        assert len({item['word'] for item in entry['wordForms']}) == len(entry['wordForms'])
    full = sum(all(entry[key] for key in ['examples', 'collocations', 'derivatives', 'confusables']) for entry in words)
    assert full >= 120, f'Expected >=120 complete core words, got {full}'
    return full


def complete_attested_details(words, oewn_path, wordnet_path):
    """Use explicit OEWN GB pronunciations and noun forms only; no guessing."""
    selected = {w['word']: w for w in words}
    added = []
    british = defaultdict(list)
    with gzip.open(oewn_path) as stream:
        for _, node in ET.iterparse(stream, events=['end']):
            if node.tag != 'LexicalEntry':
                continue
            lemma = node.find('Lemma')
            if lemma is not None:
                spelling = lemma.get('writtenForm')
                entry = selected.get(spelling)
                if entry:
                    for pron in lemma.findall('Pronunciation'):
                        value = (pron.text or '').strip()
                        if pron.get('variety') == 'GB' and value and '/' not in value:
                            ipa = '/' + value + '/'
                            if ipa not in british[spelling]: british[spelling].append(ipa)
                    if lemma.get('partOfSpeech') == 'n' and 'n.' in {p.strip() for p in entry['partOfSpeech'].split('/')}:
                        for form in node.findall('Form'):
                            value = form.get('writtenForm','')
                            if re.fullmatch('[a-z]+',value) and len(entry['wordForms']) < 6 and not any(f['word']==value for f in entry['wordForms']):
                                entry['wordForms'].append({'word':value,'meaningZh':'名词词形（OEWN 明示）'})
                                added.append({'word':spelling,'field':'wordForms','value':value,'source':'Open English WordNet 2025 LexicalEntry Form'})
            node.clear()
    for spelling, values in british.items():
        entry = selected[spelling]
        if not entry['pronunciationUk']:
            entry['pronunciationUk'] = ', '.join(values)
            added.append({'word':spelling,'field':'pronunciationUk','value':entry['pronunciationUk'],'source':'Open English WordNet 2025 Pronunciation variety=GB'})
    # Princeton examples absent from OEWN: use original complete sentences only.
    with tarfile.open(wordnet_path) as archive:
        for pos in ['noun','verb','adj','adv']:
            for line in archive.extractfile('dict/data.'+pos).read().decode().splitlines():
                if not line or not line[0].isdigit() or '|' not in line: continue
                fields, gloss = line.split('|',1)
                parts = fields.split()
                lemmas = [parts[4+2*i] for i in range(int(parts[3],16))]
                for spelling in lemmas:
                    entry = selected.get(spelling)
                    if not entry or entry['examples']: continue
                    forms = {spelling} | {f['word'] for f in entry['wordForms']}
                    for sentence in re.findall(r'"([^"\n]+)"',gloss):
                        tokens = re.findall(r"[a-z]+(?:'[a-z]+)?",sentence.lower())
                        if 5 <= len(tokens) <= 24 and forms.intersection(tokens) and re.search(r'\b(?:is|are|was|were|has|have|had|he|she|they|we|i|you|it)\b',sentence.lower()):
                            entry['examples'].append({'en':sentence,'zh':'','source':'Princeton WordNet 3.0', 'attribution':'Princeton University · '+parts[0]+'-'+pos,'sourceUrl':'https://wordnet.princeton.edu/','licenseUrl':SOURCES['wordnet']['licenseUrl']})
                            added.append({'word':spelling,'field':'examples','value':sentence,'source':'Princeton WordNet 3.0 '+parts[0]+'-'+pos})
                            break
    return added


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ecdict', help='Existing ecdict.csv with the pinned SHA-256')
    parser.add_argument('--ipa', help='Existing en_US.txt with the pinned SHA-256')
    parser.add_argument('--ipa-uk', default=str(UK_SOURCE_PATH), help='Pinned en_UK.txt; complete source snapshot included with the project')
    parser.add_argument('--tatoeba', help='Existing cmn-eng.zip with the pinned SHA-256')
    parser.add_argument('--wordnet', help='Existing WNdb-3.0.tar.gz with the pinned SHA-256')
    parser.add_argument('--oewn', help='Existing english-wordnet-2025.xml.gz with the pinned SHA-256')
    parser.add_argument('--tatoeba-english', help='Existing rus-eng.zip with the pinned SHA-256 (English side only)')
    parser.add_argument('--count', type=int, default=28000)
    args = parser.parse_args()
    ecdict = fetch('ecdict', args.ecdict)
    words = build(ecdict, fetch('ipa', args.ipa), args.count)
    uk_path = fetch('ipaUk', args.ipa_uk)
    enrich_british(words, uk_path)
    used_sentences, extra_sentences = enrich(words, ecdict, fetch('tatoeba', args.tatoeba),
                                            fetch('wordnet', args.wordnet), fetch('oewn', args.oewn),
                                            fetch('tatoebaEnglish', args.tatoeba_english))
    completed_plurals = complete_attested_details(words, fetch('oewn', args.oewn), fetch('wordnet', args.wordnet))
    assign_levels(words, {r['word']:r for r in learning_rows(ecdict)})
    # A corrected primary sense must also be used in nested related-word notes;
    # otherwise a known source error can survive in the learning details.
    meaning_corrections = json.loads((HERE / 'meaning_corrections_v4.json').read_text(encoding='utf-8'))
    meaning_corrections.update(json.loads((HERE / 'content_review_v10.json').read_text()))
    for entry in words:
        for item in entry['derivatives'] + entry['confusables']:
            correction = meaning_corrections.get(item['word'])
            if correction:
                item['meaningZh'] = correction['meaningZh']
                if 'source' in item and '项目释义校订' not in item['source']:
                    item['source'] += ' · 项目释义校订'
    enrich_relations(words, fetch('wordnet', args.wordnet))
    # v2.0 learner pass: original bilingual examples, reviewed collocations and
    # concise primary senses for high-frequency polysemous words.
    from apply_learner_update import apply as apply_learner_enrichment
    learner = json.loads((HERE / 'learner_enrichment_v11.json').read_text(encoding='utf-8'))
    learner_errors = []
    apply_learner_enrichment(learner, {entry['word']: entry for entry in words}, learner_errors)
    if learner_errors:
        raise ValueError(f'Learner enrichment errors: {learner_errors[:5]}')
    # Apply the project-only audit last so rebuilding cannot restore reviewed errors.
    from apply_internal_review import apply_review, update_metadata as internal_metadata
    apply_review(words)
    from apply_complete_review import apply_review as complete_review, update_metadata as complete_metadata, bundle_inputs
    complete_review(words)
    from complete_learning_content import apply_review as final_content_review, update_metadata as final_content_metadata
    final_content_review(words)
    full = validate(words)
    ASSETS.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(words, ensure_ascii=False, separators=(',', ':')) + '\n'
    (ASSETS / 'vocabulary.json').write_text(payload, encoding='utf-8')
    metadata = {
        'schemaVersion': 1,
        'contentVersion': 14,
        'wordCount': len(words),
        'completeCoreWordCount': full,
        'sources': {**SOURCES, 'ngsl': NGSL_SOURCE},
        'difficultyDescriptionZh': 'v2.0 学习顺序：以COCA词频为骨架，叠加项目基础词核心、NGSL词头分档、构词复杂度惩罚与少量复核下限；低等级优先具体、日常、高可用词，长尾集中在高等级。仅为学习排序估计，不等同CEFR、词汇量或考试成绩。',
        'levelMethod': {'version': LEVEL_VERSION, 'descriptionZh': '词频骨架 + 项目基础词核心 + NGSL词头晋升上限 + 构词复杂度惩罚 + 复核下限；全部规则可追溯，不使用学习者隐私数据。', 'frequencyThresholds': THRESHOLDS, 'ngslPromotionCeiling': {'1000':1800,'2000':3200,'3000':4500}, 'reviewedFloorCount': 53, 'reviewFile':'tools/vocabulary_levels.py', 'enrichmentFile':'tools/learner_enrichment_v11.json', 'validatedCefr':False},
        'selectionDescriptionZh': f'按公开词频保留基础候选池，补入NGSL及中高考词头和复核基础词；允许明确复核的大写词及无词频词。共{len(words)}词，全部有真实美式IPA、中文释义和词性。无COCA词频时rank=1000000仅用于排序，不代表真实词频。',
        'enhancementDescriptionZh': '保留原创核心例句与提示；Tatoeba/ManyThings 中英原句优先，缺口以其更大公开语料的英文原句及 Open English WordNet 2025 例句补充。英文原句没有中文时明确留空，不把提示当翻译；逐句保留作者、ID和许可。词形变化直接读取 ECDICT exchange 的明确屈折关系并标注过去式、过去分词、现在分词、第三人称单数、复数、比较级、最高级。搭配采用词典短语与原创实用组合；派生与词族使用明确 derivation 关系、人工词族，以及有 -ly 形态证据且中文词性对应的显式形容词/副词 pertainym 关系，不使用其他语义所属关系。',
        'sentenceQualityDescriptionZh': 'Tatoeba / ManyThings 英文语料经过源站校对，仍可能有原句、翻译或义项差异。OEWN 例句取其原始文本，要求词条或词典屈折形实际出现，并排除未含分句线索的短语；不是逐句人工语法和义项审核。中文原文含简繁体，不自动改写。',
        'collocationDescriptionZh': '搭配含词典收录的固定短语和复合词，不等同于基于语料频率统计的完整搭配词典。',
        'tatoebaSentencePairCount': len(used_sentences),
        'additionalEnglishSentenceCount': len(extra_sentences),
        'bilingualExampleWordCount': sum(any(item['zh'] for item in entry['examples']) for entry in words),
        'englishOnlyExampleWordCount': sum(bool(entry['examples']) and not any(item['zh'] for item in entry['examples']) for entry in words),
        'derivativeDescriptionZh': '派生与词族包含明确派生关系、人工词族，以及有形态证据的显式形容词/副词 pertainym，后者标注“词族关联”；排除语义所属关系与中文词性不对应的同形异义关系。单复数及动词时态不冒充派生词。没有常见派生或未覆盖关系可为空，不保证每个词都有派生。',
        'originalEnrichmentDescriptionZh': 'v4 原创输入含 335 条项目原创中英语境、459 条搭配、111 组人工词族和 50 组常见易混词辨析；并人工修正 23 词常用中文义项及相关词提示。各词条按展示上限合并，实际覆盖数量见 enhancedCounts。项目原创内容为 CC0；人工词族中文仍取自 ECDICT（MIT）。',
        'originalInputSha256': {filename: hashlib.sha256((HERE / filename).read_bytes()).hexdigest() for filename in ['core_enrichment.json', 'advanced_enrichment.json', 'practical_enrichment_v3.json', 'practical_enrichment_v4.json', 'meaning_corrections_v4.json', 'phrase_corrections.json', 'sentence_exclusions.json', 'word_form_corrections.json', 'level_corrections.json', 'practical_enrichment_v8.json', 'practical_enrichment_v9.json', 'content_review_v10.json', 'american_ipa_corrections.json', 'learner_enrichment_v11.json', 'vocabulary_levels.py', 'data/retained-word-ids-v9.json']},
        'meaningCorrectionWordCount': len(json.loads((HERE / 'meaning_corrections_v4.json').read_text(encoding='utf-8'))),
        'learnerMeaningFixCount': len(learner.get('meaningFixes', {})),
        'learnerEnrichmentCounts': {
            'translatedSentences': sum(len(v) for v in learner.get('exampleTranslations', {}).values()),
            'addedExamples': sum(len(v) for v in learner.get('examples', {}).values()),
            'addedCollocations': sum(len(v) for v in learner.get('collocations', {}).values()),
        },
        'exampleItemCount': sum(len(entry['examples']) for entry in words),
        'collocationItemCount': sum(len(entry['collocations']) for entry in words),
        'derivativeItemCount': sum(len(entry['derivatives']) for entry in words),
        'wordFormItemCount': sum(len(entry['wordForms']) for entry in words),
        'difficultyCounts': dict(sorted(Counter(entry['difficulty'] for entry in words).items())),
        'enhancedCountsByDifficulty': {str(d): sum(bool(entry['examples']) for entry in words if entry['difficulty'] == d) for d in sorted({entry['difficulty'] for entry in words})},
        'enhancedCounts': {key: sum(bool(entry[key]) for entry in words) for key in ['examples', 'collocations', 'derivatives', 'confusables', 'wordForms']},
        'vocabularySha256': hashlib.sha256(payload.encode()).hexdigest(),
        'licenseNoticeAsset': 'dictionary_licenses.txt',
        'sentenceAttributionAsset': 'tatoeba_attribution.tsv',
        'additionalEnglishAttributionAsset': 'english_examples_attribution.tsv',
    }
    update_british_metadata(metadata, words)
    metadata['contentVersion'] = 13
    metadata['synonymWordCount'] = sum(bool(w['synonyms']) for w in words)
    metadata['relationMethodSha256'] = hashlib.sha256((HERE / 'enrich_relations.py').read_bytes()).hexdigest()
    metadata['relationDescriptionZh'] = '同义与近义词来自WordNet同一义项，保留义项释义；易混对照保留人工辨析，补充同音词与明确标注的形近候选，不冒称已验证错误率。'
    metadata['automaticDetailCompletions'] = completed_plurals
    metadata['wordFormDescriptionZh'] = 'ECDICT 明示屈折关系及项目复核；缺失名词词形另以OEWN明确Form记录补充，不按后缀猜测。'
    metadata['britishPronunciationDescriptionZh'] += '缺口另以Open English WordNet 2025中明确标记variety=GB的发音补充；不将未标地区的音标当英式。'
    (ASSETS / 'oewn_detail_completions.json').write_text(json.dumps(completed_plurals,ensure_ascii=False,indent=2)+'\n')
    bundle_british_sources(uk_path)
    (ASSETS / 'british_ipa/oewn_detail_completions.json').write_bytes((ASSETS / 'oewn_detail_completions.json').read_bytes())
    notices = (HERE / 'attribution_header.txt').read_text(encoding='utf-8')
    for license_path in sorted((HERE / 'licenses').glob('*.txt')):
        notices += '\n\n===== ' + license_path.name + ' =====\n\n' + license_path.read_text(encoding='utf-8')
    with (ASSETS / 'tatoeba_attribution.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(['en', 'zh', 'attribution', 'sourceUrl', 'translationSourceUrl', 'licenseUrl'])
        for item in used_sentences:
            writer.writerow([item[key] for key in ['en', 'zh', 'attribution', 'sourceUrl', 'translationSourceUrl', 'licenseUrl']])
    with (ASSETS / 'english_examples_attribution.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(['en', 'source', 'attribution', 'sourceUrl', 'licenseUrl', 'synsetId'])
        for item in extra_sentences:
            writer.writerow([item.get(key, '') for key in ['en', 'source', 'attribution', 'sourceUrl', 'licenseUrl', 'synsetId']])
    notices += '\n\nTatoeba individual sentence credits are preserved with every example in vocabulary.json and in the bundled tatoeba_attribution.tsv asset. Sentence texts are unchanged; selection and vocabulary indexing are by this project.\n'
    notices += '\nAdditional English-only examples retain their original texts and author/lexicon credits in vocabulary.json and english_examples_attribution.tsv. No Russian translations from the source archive are shipped. OEWN example selection and word-family indexing are by this project; OEWN and underlying Princeton licenses are reproduced above.\n'
    (ASSETS / 'dictionary_licenses.txt').write_text(notices, encoding='utf-8')
    (ASSETS / 'american_ipa_corrections.json').write_bytes((HERE / 'american_ipa_corrections.json').read_bytes())
    metadata['reviewedContentWordCount'] = len(json.loads((HERE / 'content_review_v10.json').read_text()))
    metadata['reviewedContentDescriptionZh'] = 'v10 项目原创常用义、词性、双语语境和实用搭配复核；月份与小写同形义分开。来源与原因保存在 tools/content_review_v10.json。'
    metadata['americanPronunciationCorrectionCount'] = len(json.loads((HERE / 'american_ipa_corrections.json').read_text()))
    level_bundle = ASSETS / 'level_sources'
    level_bundle.mkdir(exist_ok=True)
    for filename in ['data/NGSL-mirror.json', 'data/NGSL-README.md', 'vocabulary_levels.py']:
        destination = level_bundle / filename
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((HERE / filename).read_bytes())
    (level_bundle / 'SOURCE.json').write_text(json.dumps(NGSL_SOURCE, ensure_ascii=False, indent=2) + '\n')
    (level_bundle / 'NOTICE.txt').write_text('NGSL by Browne, Culligan and Phillips: CC BY-SA 4.0. The bundled mirror is the 2801-headword pre-1.2 list. Project adaptation: headword membership caps and project learning-order labels, not CEFR. Adapted classification data and vocabulary_levels.py: CC BY-SA 4.0. Other dictionary fields retain their respective licenses.\n')
    metadata = internal_metadata(words, metadata)
    metadata = final_content_metadata(words, complete_metadata(words, metadata))
    bundle_inputs()
    from complete_learning_content import READINGS
    (ASSETS / "related_pronunciations.json").write_bytes(READINGS.read_bytes())
    (ASSETS / 'dictionary_sources.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
