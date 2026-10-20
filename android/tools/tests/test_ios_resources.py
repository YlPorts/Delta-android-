import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('colors',ROOT/'android/tools/import_ios_colors.py')
colors=importlib.util.module_from_spec(spec); spec.loader.exec_module(colors)

class OriginalResources(unittest.TestCase):
    def test_exact_original_components(self):
        self.assertEqual(colors.resolve('Purple')[0],(139,40,247))
        self.assertEqual(colors.resolve('LightPurple')[0],(214,188,255))
        self.assertEqual(colors.resolve('DarkGray')[0],(38,38,38))
    def test_xcode_encodings_and_invalid_input(self):
        self.assertEqual(colors.component('0xD6'),214)
        self.assertEqual(colors.component('139'),139)
        self.assertEqual(colors.component('0.150'),38)
        for value in ['256','-1','nan','1.1','0xFFFF','garbage']:
            with self.assertRaises(ValueError): colors.component(value)
    def test_original_assets_are_byte_identical(self):
        import hashlib
        base=ROOT/'android/app/src/main'
        manifest=json.loads((base/'assets/ios/provenance.json').read_text())
        self.assertGreater(len(manifest),30)
        for destination,record in manifest.items():
            self.assertEqual(hashlib.sha256((base/destination).read_bytes()).hexdigest(),record['sha256'],destination)
            self.assertEqual((base/destination).read_bytes(),(ROOT/record['source']).read_bytes(),destination)

if __name__=='__main__': unittest.main()
