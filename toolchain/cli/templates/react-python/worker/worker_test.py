import unittest

from worker import hello


class HelloTest(unittest.TestCase):
    def test_hello(self):
        self.assertEqual({"message": "Hello, Ada"}, hello({"name": "Ada"}, None))
