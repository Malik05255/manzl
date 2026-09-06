import unittest

from prepare_opensubtitles_tr_ar import normalize, stable_split, valid_pair


class CorpusPreparationTest(unittest.TestCase):
    def test_keeps_real_turkish_arabic_dialogue(self):
        self.assertTrue(valid_pair("Seni burada beklemiyordum.", "لم أكن أتوقع وجودك هنا."))

    def test_rejects_sound_effect_alignment(self):
        self.assertFalse(valid_pair("[MÜZİK]", "[موسيقى]"))

    def test_rejects_wrong_target_script(self):
        self.assertFalse(valid_pair("Buraya gel.", "Come here."))

    def test_normalization_removes_markup(self):
        self.assertEqual("Merhaba dünya", normalize("<i>Merhaba</i>   dünya"))

    def test_split_is_deterministic(self):
        pair = ("Neden geldin?", "لماذا أتيت؟")
        self.assertEqual(stable_split(*pair), stable_split(*pair))


if __name__ == "__main__":
    unittest.main()
