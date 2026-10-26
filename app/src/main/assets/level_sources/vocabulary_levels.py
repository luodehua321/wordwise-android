"""Project learning order v5: a learner-first reordering, not a CEFR classification.

The previous model mapped raw COCA bands almost one-to-one. Because the shipped
list deliberately keeps a long, useful tail, that put too many abstract or
low-frequency words into the first few bands and made the opening experience feel
much harder than the level label suggested.

v3 keeps the public frequency rank as the backbone but adds three auditable,
deterministic learner signals:

* a curated beginner core (everyday objects, actions, people, numbers, time);
* NGSL headword bands as a *generous* promotion ceiling, never as a down-grade;
* a morphology penalty for long, derivationally complex words outside the core.

The resulting bands are deliberately not equal-sized: the first four bands are a
small, clean foundation, while the long tail is concentrated in bands 8-10. A
short reviewed floor list handles abstract/academic words that raw frequency
under-rates, plus sensitive vocabulary that should not surface early.

Nothing here claims to be CEFR or a validated vocabulary-size test.

v4 preserves that backbone and adds explicit sense-aware reviews for unranked
everyday words, reviewed spelling pairs, and attested basic inflections. These
are exact, case-sensitive corrections, not suffix rules. A reviewed correction
can lower an inflated band as well as raise it; reviewed floors still apply.

v5 adds an exact Android review table for ordinary daily words, classroom
vocabulary, verified British/American counterparts, and reviewed transparent
families. It keeps the same corpus ranks and scoring backbone. No suffix rule
equates unrelated words or lowers independent homograph senses.
"""
import hashlib
import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERSION = 'learning-order-v5'

# Raw-frequency cut points over the learner score. The score is rank-like, so
# these read as "first ~1k / ~2.2k / ... learners' order", not word counts.
THRESHOLDS = [1000, 2200, 3800, 5500, 8000, 12000, 17000, 23000, 30000]

NGSL_HASH = hashlib.sha256((HERE / 'data/NGSL-mirror.json').read_bytes()).hexdigest()
NGSL_SOURCE = {
    'project': 'New General Service List (2801-headword mirror, pre-1.2)',
    'authors': 'Charles Browne, Brent Culligan, Joseph Phillips',
    'url': 'https://www.newgeneralservicelist.com/new-general-service-list',
    'mirrorUrl': 'https://github.com/lpmi-13/machine_readable_wordlists/blob/972b751ef6bcc9bd71d0fbc4146174fc8bdfd49c/General/NGSL/NGSL.json',
    'sha256': NGSL_HASH,
    'license': 'CC BY-SA 4.0',
    'licenseUrl': 'https://creativecommons.org/licenses/by-sa/4.0/',
    'fields': ['difficulty auxiliary promotion ceiling'],
    'note': 'Mirror headword bands only; not the official 2023 NGSL1.2; not CEFR. No family arrays used.',
}
# Source checksum is fixed, so modifying the downloaded source cannot silently
# change every word's classification.
EXPECTED_NGSL_HASH = '70c1e37fb3feacc95d28aca8c1d7b6fc29960de8572b9912cc4fbbec806809d2'
assert NGSL_HASH == EXPECTED_NGSL_HASH, 'NGSL mirror source checksum mismatch'
NGSL = {word.lower(): int(band) for band, entries in json.loads(
    (HERE / 'data/NGSL-mirror.json').read_text()).items() for word in entries}

# A curated, deliberately concrete beginner core. These are the words a new
# learner meets first in textbooks and daily life. Membership forces band 1.
# It is intentionally small: adding a thousand abstract high-frequency words
# would recreate the problem v3 is fixing.
BASIC_CORE = set('''
i you he she it we they me him her us them my your his its our their mine yours
myself yourself himself herself itself ourselves themselves yourselves
hello hi goodbye bye yes no please thanks thank sorry okay ok welcome
mother father mum mom dad mama papa baby boy girl man woman child children family
home house school teacher student friend name age birthday
apple banana orange pear grape lemon fruit bread rice milk water egg fish meat
food eat drink hungry thirsty
cat dog bird rabbit duck chicken pig cow horse sheep goat animal pet
red blue green yellow black white brown pink purple grey gray colour color
one two three four five six seven eight nine ten zero eleven twelve thirteen
fourteen fifteen sixteen seventeen eighteen nineteen twenty
first second third fourth fifth monday tuesday wednesday thursday friday
saturday sunday january february march april may june july august september
october november december
day week month year today tomorrow yesterday morning afternoon evening night
noon weekend sun moon sky star rain snow wind cloud hot cold warm cool weather
big small long short tall good bad happy sad new old young clean dirty fast slow
easy hard go come walk run sit stand sleep wake see hear look listen speak say
read write learn study book pen pencil paper bag desk chair bed door window room
kitchen bathroom bedroom garden car bus bike bicycle train boat plane ship taxi
ticket road street station airport ball game play toy doll music song sing dance
movie film picture photo hand head face eye ear nose mouth hair tooth teeth leg
foot feet arm body finger heart wear shoe shoes sock socks shirt skirt dress
trousers pants coat hat cap shopping shop store supermarket money price buy sell
pay cheap expensive doctor nurse hospital medicine ill sick pain tired healthy
breakfast lunch dinner because and or but if when where why how what which who
whose this that these those a an the is am are be do have can not very too also
only just now then here there up down left right under on in out off over open
close start stop finish make take give put bring carry find lose keep leave meet
call ask answer love like hate want need know think understand remember forget
help work job office country city town village world people person group team
english chinese language word letter question answer computer phone internet
email online website camera television radio
'''.split())

# Derivational endings that usually mark later, academic vocabulary. Applied
# only outside the beginner core and only beyond the opening frequency range.
SUFFIX = re.compile(r'(tion|sion|ment|ity|ness|ance|ence|ism|ist|ous|ive|ally|ical|'
                    r'ic|ary|ory|ology|graphy|phy|itude|itious|esque|escent|aceous|aneous|'
                    r'ification|isation|ization|ship|hood|less|some|able|ible|ate|ify|ise|ize)$')

# A short reviewed floor. These words are abstract, academic, or sensitive
# enough that raw frequency alone puts them in a misleadingly easy band.
REVIEWED_FLOOR = {
    'accomplish': 4, 'philosophy': 4, 'psychology': 5, 'congressional': 5,
    'corporation': 4, 'accountability': 5, 'presidency': 4, 'admission': 4,
    'legislation': 4, 'constitutional': 5, 'infrastructure': 5, 'bureaucracy': 5,
    'jurisdiction': 6, 'litigation': 6, 'hypothesis': 5, 'empirical': 5,
    'algorithm': 5, 'molecule': 4, 'molecular': 5, 'genetic': 4, 'genetics': 5,
    'metabolism': 6, 'membrane': 5, 'protein': 4, 'enzyme': 5, 'antibiotic': 6,
    'diagnosis': 4, 'symptom': 4, 'therapy': 4, 'surgery': 4, 'radiation': 6,
    'thermodynamics': 8, 'quantum': 7, 'epistemology': 9, 'ontology': 9,
    'metaphysics': 9, 'jurisprudence': 9, 'photosynthesis': 8, 'cryptography': 8,
    'semiconductor': 8, 'macroeconomics': 8, 'algorithmic': 7,
    # Sensitive or adult vocabulary: keep it for advanced learners, not band 3.
    'fuck': 9, 'shit': 8, 'condom': 8, 'clitoris': 9, 'penis': 8, 'vagina': 8,
    'abortion': 6, 'catholic': 6, 'terrorism': 6, 'missile': 6, 'assault': 6,
}

LEVEL_CORRECTIONS = {}
corrections_path = HERE / 'level_corrections.json'
if corrections_path.exists():
    for word, item in json.loads(corrections_path.read_text(encoding='utf-8')).items():
        if isinstance(item, dict) and isinstance(item.get('difficulty'), int):
            LEVEL_CORRECTIONS[word] = item['difficulty']

LEVEL_REVIEWS = {}
review_path = HERE / 'level_review_v13.json'
if review_path.exists():
    LEVEL_REVIEWS = json.loads(review_path.read_text(encoding='utf-8'))['corrections']
    for word, item in LEVEL_REVIEWS.items():
        assert type(item['difficulty']) is int and 1 <= item['difficulty'] <= 10, word
        LEVEL_CORRECTIONS[word] = item['difficulty']

review_path_v16 = HERE / 'level_review_v16.json'
if review_path_v16.exists():
    for word, item in json.loads(review_path_v16.read_text(encoding='utf-8'))['corrections'].items():
        assert type(item['difficulty']) is int and 1 <= item['difficulty'] <= 10, word
        LEVEL_REVIEWS[word] = item
        LEVEL_CORRECTIONS[word] = item['difficulty']


def reviewed_grade(word, score):
    grade = 1 + sum(score > threshold for threshold in THRESHOLDS)
    # The old max(grade, correction) silently prevented downward corrections.
    grade = LEVEL_CORRECTIONS.get(word, grade)
    return min(10, max(1, grade, REVIEWED_FLOOR.get(word.lower(), 1)))


def learner_score(word, rank):
    """Return a rank-like learner-order score. Lower is easier.

    The score is deterministic, explainable and stable across rebuilds. It is
    not a probability and should never be shown to learners as one.
    """
    lowered = word.lower()
    safe_rank = min(int(rank) if rank else 60000, 60000)
    score = float(safe_rank)
    band = NGSL.get(lowered)
    if band:
        # Generous ceilings: knowing a word is in the first NGSL thousand is a
        # signal that it is useful, but membership alone does not make a word
        # beginner-level (e.g. "corporation" or "constitution").
        score = min(score, {1000: 1800, 2000: 3200, 3000: 4500}[band])
    if lowered in BASIC_CORE and not (word.islower() and lowered == 'august'):
        score = -1000.0
    if lowered not in BASIC_CORE and score > 1500:
        length = len(lowered)
        if length >= 9:
            score *= 1.08
        if length >= 11:
            score *= 1.10
        if length >= 13:
            score *= 1.10
        if SUFFIX.search(lowered):
            score *= 1.50
    return score


def classify(row):
    """Compatibility helper for audit/test tools: rank-based band + evidence."""
    word = row['word']
    rank = int(row['frq']) if str(row.get('frq', '')).isdigit() else 60000
    score = learner_score(word, rank)
    grade = reviewed_grade(word, score)
    evidence = [f'COCA:{rank if rank < 60000 else "unranked"}',
                f'learner-score:{score:.1f}']
    band = NGSL.get(word.lower())
    if band:
        evidence.append(f'NGSL-headword-band:{band}')
    if word.lower() in BASIC_CORE and not (word.islower() and word.lower() == 'august'):
        evidence.append('curated-beginner-core')
    if word.lower() in REVIEWED_FLOOR:
        evidence.append('reviewed-floor')
    if word in LEVEL_CORRECTIONS:
        evidence.append('explicit-reviewed-correction')
    return min(10, max(1, grade)), evidence


def assign_levels(words, rows=None):
    """Assign v5 difficulty in place and return the per-word audit report.

    `rows` is accepted for build-script compatibility. v3 intentionally uses
    only the shipped word, its public rank and the reviewed lists above, so the
    same function reproduces the assets in the repository without downloading
    the upstream CSV.
    """
    report = {}
    for entry in words:
        word = entry['word']
        rank = int(entry.get('rank') or 60000)
        score = learner_score(word, rank)
        grade = reviewed_grade(word, score)
        entry['difficulty'] = grade
        flags = []
        if word.lower() in BASIC_CORE and not (word.islower() and word.lower() == 'august'):
            flags.append('curated-beginner-core')
        if word.lower() in NGSL:
            flags.append('ngsl-band-%d' % NGSL[word.lower()])
        if word.lower() in REVIEWED_FLOOR:
            flags.append('reviewed-floor')
        if word in LEVEL_CORRECTIONS:
            flags.append('explicit-reviewed-correction')
        report[word] = {
            'word': word,
            'difficulty': grade,
            'learnerScore': round(score, 2),
            'sourceRank': rank,
            'flags': flags,
            'evidence': flags,
        }
        if word in LEVEL_REVIEWS:
            report[word]['review'] = LEVEL_REVIEWS[word]
    return report


# Compatibility alias for the older review tooling. These are the project's
# reviewed overrides, not raw corpus inference.
OVERRIDES = {word: 1 for word in BASIC_CORE}
OVERRIDES.update(REVIEWED_FLOOR)
OVERRIDES.update(LEVEL_CORRECTIONS)


def distribution(words):
    counts = {str(level): 0 for level in range(1, 11)}
    for entry in words:
        counts[str(entry['difficulty'])] += 1
    return counts
