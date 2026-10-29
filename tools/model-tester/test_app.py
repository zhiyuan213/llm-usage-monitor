import unittest
from unittest.mock import patch
from app import events, App, open_request, error_message


class TesterContracts(unittest.TestCase):
    def test_https_uses_bundled_ca_and_still_verifies_certificate(self):
        import ssl
        import urllib.request
        with patch('app.urllib.request.urlopen') as transport:
            open_request(urllib.request.Request('https://example.test'), 3)
            context = transport.call_args.kwargs['context']
            self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
            self.assertTrue(context.check_hostname)
            self.assertGreater(len(context.get_ca_certs()), 0)

    def test_http_error_shows_status_without_exposing_url_or_key(self):
        import urllib.error
        exc = urllib.error.HTTPError('https://example.test/?key=secret', 401, 'Unauthorized', {}, None)
        self.assertIn('401', error_message(exc))
        self.assertNotIn('secret', error_message(exc))

    def test_real_usage_and_association(self):
        result = events({'model': 'actual-model', 'usage': {'prompt_tokens': 14,
            'completion_tokens': 79, 'prompt_cache_hit_tokens': 0}}, 'deepseek', 'requested', 'now', 2470)
        self.assertEqual(result[0]['traceId'], result[1]['traceId'])
        self.assertNotEqual(result[0]['eventId'], result[1]['eventId'])
        self.assertEqual(16, len(result[1]['spanId']))
        self.assertEqual('actual-model', result[1]['data']['model'])
        self.assertEqual({'inputTokens': 14, 'outputTokens': 79, 'cachedInputTokens': 0}, result[1]['data']['usage'])

    def test_unknown_usage_is_not_zero(self):
        self.assertEqual(dict.fromkeys(['inputTokens', 'outputTokens', 'cachedInputTokens']),
            events({}, 'private', 'test', 'now', 1)[1]['data']['usage'])

    def test_openai_cached_usage(self):
        result = events({'usage': {'prompt_tokens': 100, 'completion_tokens': 20,
            'prompt_tokens_details': {'cached_tokens': 80}}}, 'openai', 'test', 'now', 1)
        self.assertEqual(80, result[1]['data']['usage']['cachedInputTokens'])

    def test_submit_failure_preserves_original_events(self):
        app = App.__new__(App)
        import queue
        app.messages = queue.Queue()
        original = events({}, 'private', 'test', '2026-10-29T00:00:00.000Z', 1)
        app.pending = [{'destination': ('https://example.test', 'fake-key'), 'events': original}]
        app.outbox = None
        with patch('app.post', side_effect=TimeoutError):
            with self.assertRaises(TimeoutError):
                app.do_upload()
        self.assertEqual(original, app.outbox['body']['events'])
        self.assertEqual(1, len(app.pending))
        self.assertIsNone(app.outbox['batchId'])


if __name__ == '__main__':
    unittest.main()
