"""Regression checks for attested inflections and shipped pronunciation data."""
import json
import unittest
from pathlib import Path
from build_vocabulary import parse_word_forms, validate
from british_pronunciations import load_source, supplement, completed_british


class VocabularyTests(unittest.TestCase):
    def test_possessives_count_as_the_noun_without_matching_substrings(self):
        from apply_internal_review import tokens
        self.assertIn('heckler', tokens("The heckler's question was brief."))
        self.assertIn('sender', tokens('The sender’s address is missing.'))
        self.assertNotIn('he', tokens("The heckler's question was brief."))

    def test_v14_authored_content_replay_preserves_english_credits(self):
        import copy
        from complete_learning_content import apply_review
        root = Path(__file__).resolve().parents[1]
        words = json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())
        replay = apply_review(copy.deepcopy(words))
        self.assertEqual(words, replay)
        index = {w['word']: w for w in replay}
        item = next(e for e in index['national']['examples'] if e['en'] == 'We camped for two weeks in the national park.')
        self.assertTrue(item['zh'])
        self.assertTrue(item['sourceUrl'])
        self.assertIn('Tatoeba', item['attribution'])
        self.assertEqual('CC0 1.0', item['translationLicense'])
        for word in ['traveller', 'cheque', 'workbook', 'genetics', 'analytical']:
            self.assertTrue(any(e['zh'] for e in index[word]['examples']), word)

    def test_related_pronunciation_table_keeps_accents_separate_and_formats_valid(self):
        import re
        root = Path(__file__).resolve().parents[1]
        source = root / 'tools/related_pronunciations_v17.json'
        self.assertEqual(source.read_bytes(), (root / 'app/src/main/assets/related_pronunciations.json').read_bytes())
        readings = json.loads(source.read_text())
        for spelling, row in readings.items():
            for value in row.values():
                self.assertTrue(not value or re.fullmatch(r'/[^/]+/(?:, /[^/]+/)*', value), spelling)
        self.assertTrue(readings['reader']['us'])
        self.assertTrue(readings['reader']['uk'])
        self.assertNotEqual(readings['car']['us'], readings['car']['uk'])

    def test_v4_reclassifies_unranked_words_and_preserves_homographs(self):
        from vocabulary_levels import assign_levels, classify
        root = Path(__file__).resolve().parents[1]
        words = json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())
        original = {w['word']: w['difficulty'] for w in words}
        assign_levels(words)
        self.assertEqual(original, {w['word']: w['difficulty'] for w in words})
        for word, expected in [('does', 1), ('smartphone', 2), ('liked', 2),
                               ('centre', 1), ('grandparents', 2), ('grandparent', 2),
                               ('rape', 6), ('federal', 4), ('pants', 1), ('laptop', 2)]:
            self.assertEqual(expected, original[word], word)
        self.assertEqual(2, classify({'word': 'smartphone', 'frq': '1000000'})[0])
        self.assertEqual(8, original['august'])
        self.assertEqual(1, original['August'])
        self.assertGreater(original['dogged'], original['dog'])
        for british, american in [('centre', 'center'), ('neighbour', 'neighbor'),
                                  ('favourite', 'favorite'), ('yoghurt', 'yogurt'),
                                  ('pyjamas', 'pajamas'), ('apologise', 'apologize'),
                                  ('colourless', 'colorless'), ('moisturiser', 'moisturizer')]:
            self.assertEqual(original[american], original[british], british)

    def test_new_headwords_have_learning_content_and_replay_is_idempotent(self):
        import copy
        from apply_complete_review import apply_review
        root = Path(__file__).resolve().parents[1]
        words = json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())
        additions = json.loads((root / 'tools/missing_words_v13.json').read_text())['words']
        indexed = {w['word']: w for w in words}
        for candidate in additions:
            word = indexed[candidate['word']]
            self.assertTrue(any(e['zh'] for e in word['examples']), word['word'])
            self.assertTrue(word['collocations'], word['word'])
            self.assertEqual(1000000, word['rank'])
        self.assertIn('眼镜', indexed['glasses']['meaningZh'])
        self.assertIn('裤', indexed['pants']['meaningZh'])
        self.assertIn('剪刀', indexed['scissors']['meaningZh'])
        self.assertTrue(all('pants' not in e['en'] for e in indexed['pant']['examples']))
        replay = copy.deepcopy(words)
        apply_review(replay)
        self.assertEqual(words, replay)

    def test_reviewed_meanings_and_relationships_do_not_keep_confirmed_errors(self):
        root = Path(__file__).resolve().parents[1]
        indexed = {w['word']: w for w in json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())}
        self.assertNotIn('逐渐', indexed['generally']['meaningZh'])
        self.assertNotIn('基频', indexed['fundamentally']['meaningZh'])
        self.assertIn('n.', indexed['sales']['partOfSpeech'])
        self.assertNotIn('失职', indexed['jobless']['meaningZh'])
        for word in ['car', 'motor']:
            self.assertEqual('汽车', next(x['zh'] for x in indexed[word]['collocations'] if x['en'] == 'motor car'))
        self.assertNotIn('tow', [x['word'] for x in indexed['tower']['derivatives']])
        self.assertNotIn('tug', [x['word'] for x in indexed['tower']['synonyms']])
        self.assertEqual({'enrolling', 'enrolled', 'enrolls', 'enrols'},
                         {x['word'] for x in indexed['enroll']['wordForms']})
        self.assertEqual([{'word': 'earphones', 'meaningZh': '复数'}], indexed['earphone']['wordForms'])

    def test_internal_review_corrects_senses_without_importing_new_words(self):
        root = Path(__file__).resolve().parents[1]
        indexed = {w['word']: w for w in json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())}
        self.assertIn('牙菌斑', indexed['plaque']['meaningZh'])
        self.assertNotIn('血小板', indexed['plaque']['meaningZh'])
        self.assertIn('孜然', indexed['cumin']['meaningZh'])
        self.assertNotIn('莳萝', indexed['cumin']['meaningZh'])
        self.assertIn('胜任力', indexed['competency']['meaningZh'])
        self.assertIn('预计的', indexed['projected']['meaningZh'])
        self.assertIn('冠层', indexed['canopy']['meaningZh'])
        self.assertTrue(any('大部分时间' in e['zh'] for e in indexed['best']['examples']))
        self.assertTrue(any('部队' in e['zh'] for e in indexed['commander']['examples']))

    def test_homographs_do_not_inherit_wrong_examples_or_pronunciations(self):
        root = Path(__file__).resolve().parents[1]
        indexed = {w['word']: w for w in json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())}
        for word, rejected in [('mope', 'moped'), ('snoopy', 'Snoopy'), ('august', 'August'), ('march', 'March')]:
            self.assertTrue(indexed[word]['examples'], word)
            self.assertFalse(any(rejected in e['en'] for e in indexed[word]['examples']), word)
        self.assertEqual('/ˈmoʊpɛd/', indexed['moped']['pronunciationUs'])
        self.assertEqual([], indexed['known']['wordForms'])
        self.assertEqual([], indexed['grown']['wordForms'])
        self.assertFalse(any(f['word'] == 'best' for f in indexed['best']['wordForms']))
        self.assertTrue(any(f['word'] == 'read' and '过去式' in f['meaningZh'] for f in indexed['read']['wordForms']))
        self.assertEqual('复数（单复数同形）', indexed['sheep']['wordForms'][0]['meaningZh'])

    def test_synonym_chinese_gloss_matches_the_selected_sense(self):
        root = Path(__file__).resolve().parents[1]
        indexed = {w['word']: w for w in json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())}
        behind = next(s for s in indexed['can']['synonyms'] if s['word'] == 'behind')
        self.assertIn('臀部', behind['meaningZh'])
        self.assertIn('sit on', behind['senseEn'])
        improving = next(s for s in indexed['up']['synonyms'] if s['word'] == 'improving')
        self.assertIn('好转', improving['meaningZh'])
        self.assertNotIn('教育', improving['meaningZh'])

    def test_internal_review_is_idempotent_and_preserves_original_english_credits(self):
        import copy
        from apply_internal_review import apply_review
        root = Path(__file__).resolve().parents[1]
        words = json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())
        staged = copy.deepcopy(words)
        apply_review(staged)
        self.assertEqual(words, staged)
        indexed = {w['word']: w for w in staged}
        sentence = next(e for e in indexed['wisdom']['examples'] if e['en'] == 'Tom had two of his wisdom teeth pulled.')
        self.assertTrue(sentence['zh'])
        self.assertIn('Tatoeba', sentence['attribution'])
        self.assertIn('项目原创', sentence['translationCredit'])
        self.assertEqual('CC0 1.0', sentence['translationLicense'])

    def test_same_spelling_can_have_multiple_inflection_roles(self):
        self.assertEqual([{'word': 'read', 'meaningZh': '过去式 / 过去分词'},
                          {'word': 'reading', 'meaningZh': '现在分词'}],
                         parse_word_forms('p:read/d:read/i:reading', 'read'))

    def test_shared_forms_keep_all_labels_and_reject_metadata(self):
        self.assertEqual([{'word': 'walked', 'meaningZh': '过去式 / 过去分词'}],
                         parse_word_forms('p:walked/d:walked/0:walk/x:invalid/i:bad form', 'walk'))

    def test_learning_order_common_senses_and_homographs(self):
        from vocabulary_levels import classify
        for word, expected in [('hello',1),('cat',1),('dog',1),('mum',1),('agency',2),
                               ('international',3),('radium',8),('dogged',8),('august',8),('August',1)]:
            grade, _ = classify({'word':word,'frq':'18000','tag':'zk'})
            self.assertEqual(expected,grade,word)
        self.assertNotEqual(classify({'word':'unknownword','frq':'13000','tag':''})[0],
                            classify({'word':'unknownword','frq':'30000','tag':''})[0])

    def test_v3_uses_learner_signals_not_just_raw_frequency(self):
        from vocabulary_levels import assign_levels
        words = [
            {'word': 'dog', 'rank': 753, 'meaningZh': '狗', 'wordForms': []},
            {'word': 'dogged', 'rank': 19046, 'meaningZh': '顽强的', 'wordForms': []},
            {'word': 'corporation', 'rank': 2602, 'meaningZh': '公司', 'wordForms': []},
            {'word': 'apple', 'rank': 2695, 'meaningZh': '苹果', 'wordForms': []},
            {'word': 'august', 'rank': 22206, 'meaningZh': '威严的', 'wordForms': []},
            {'word': 'August', 'rank': 1000000, 'meaningZh': '八月', 'wordForms': []},
        ]
        report = assign_levels(words)
        self.assertEqual([1, 8, 4, 1, 8, 1], [w['difficulty'] for w in words])
        self.assertIn('curated-beginner-core', report['apple']['flags'])
        self.assertIn('reviewed-floor', report['corporation']['flags'])

    def test_complete_assets_match_ipa_sources(self):
        root = Path(__file__).resolve().parents[1]
        words = json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())
        validate(words)
        british = {**completed_british(), **supplement(), **load_source()}
        for word in words:
            self.assertEqual(british.get(word['word'], british.get(word['word'].lower(), '')), word['pronunciationUk'], word['word'])
        self.assertGreaterEqual(len(words), 25000)
        indexed = {w['word']: w for w in words}
        self.assertEqual(1, indexed['mum']['difficulty'])
        self.assertIn('妈妈', indexed['mum']['meaningZh'])
        for spelling in ['I','English','January','March','May','August','Monday','email','internet','smartphone','app']:
            self.assertIn(spelling,indexed)
            self.assertTrue(indexed[spelling]['examples'])
        from vocabulary_levels import NGSL
        self.assertTrue(set(NGSL) <= {w.lower() for w in indexed})
        self.assertNotIn('五月',indexed['may']['meaningZh'].split('（')[0])
        self.assertEqual([],indexed['March']['wordForms'])
        self.assertIn('ɡʌst', indexed['august']['pronunciationUs'])
        self.assertIn('ɡɪd', indexed['dogged']['pronunciationUs'])
        self.assertTrue(indexed['mum']['examples'][0]['zh'])
        self.assertEqual('/wɪnd/, /waɪnd/', indexed['wind']['pronunciationUk'])
        self.assertTrue(any(x['zh'] for x in indexed['allocation']['examples']))
        for spelling in ['sheep', 'deer', 'moose']:
            self.assertEqual([{'word': spelling, 'meaningZh': '复数（单复数同形）'}], indexed[spelling]['wordForms'])


if __name__ == '__main__':
    unittest.main()
