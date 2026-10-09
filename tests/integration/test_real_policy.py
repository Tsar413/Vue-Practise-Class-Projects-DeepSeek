"""Offline safety checks: no application/provider requests or credential reads."""
import tempfile,unittest
from pathlib import Path
from real_policy import Budget,validate_settings

class RealPolicyTests(unittest.TestCase):
    def test_exact_https_allowlist(self):
        base={'AI_TUTOR_MODEL':'test-model','AI_TUTOR_API_KEY':'fictional-unit-value'}
        for url in ['https://api.deepseek.com','https://api.deepseek.com/v1']:
            self.assertEqual(url,validate_settings(dict(base,AI_TUTOR_BASE_URL=url))['AI_TUTOR_BASE_URL'])
        for url in ['http://api.deepseek.com','https://api.deepseek.com.evil.invalid','https://api.deepseek.com@evil.invalid','https://api.deepseek.com:444','https://api.deepseek.com/?url=https://evil.invalid','https://api.deepseek.com/other','http://127.0.0.1:18091','https://example.com']:
            with self.subTest(url=url),self.assertRaises(AssertionError):validate_settings(dict(base,AI_TUTOR_BASE_URL=url))
    def test_conservative_persistent_budget(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'budget.json';b=Budget(path);n=b.reserve('first')
            with self.assertRaises(AssertionError):Budget(path).reserve('parallel')
            b.finish(n,{'httpStatus':503})
            with self.assertRaises(AssertionError):b.reserve('first')
            for i in range(4):
                number=Budget(path).reserve('next-'+str(i));b.finish(number,{'httpStatus':200})
            with self.assertRaises(AssertionError):Budget(path).reserve('sixth')
            self.assertEqual(5,len(b.read()['attempts']))
            self.assertEqual(0,path.stat().st_mode & 0o077)
    def test_metric_whitelist_rejects_secret_fields(self):
        with tempfile.TemporaryDirectory() as d:
            b=Budget(Path(d)/'budget.json');n=b.reserve('test')
            with self.assertRaises(AssertionError):b.finish(n,{'token':'fictional'})
            self.assertEqual('reserved',b.read()['attempts'][0]['state'])

if __name__=='__main__':unittest.main()
