#!/usr/bin/env python3
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import shutil
import tempfile
import unittest
import mock

from mobly import config_parser as mobly_config_parser

from acts import base_test
from acts import signals
from acts import test_decorators
from acts import test_runner
from acts.controllers.sl4a_lib import rpc_client

TEST_TRACKER_UUID = '12345'
UUID_KEY = 'test_tracker_uuid'


def return_true():
    return True


def return_false():
    return False


def raise_pass():
    raise signals.TestPass('')


def raise_failure():
    raise signals.TestFailure('')


def raise_sl4a():
    raise rpc_client.Sl4aException('')


def raise_generic():
    raise Exception('')


class TestDecoratorUnitTests(unittest.TestCase):
    def _verify_test_tracker_info(self, func):
        try:
            test_decorators.test_tracker_info(uuid=TEST_TRACKER_UUID)(func)()
            self.fail('Expected decorator to raise exception.')
        except signals.TestSignal as e:
            self.assertTrue(hasattr(e, 'extras'),
                            'Expected thrown exception to have extras.')
            self.assertTrue(UUID_KEY in e.extras,
                            'Expected extras to have %s.' % UUID_KEY)
            self.assertEqual(e.extras[UUID_KEY], TEST_TRACKER_UUID)

    def test_test_tracker_info_on_return_true(self):
        self._verify_test_tracker_info(return_true)

    def test_test_tracker_info_on_return_false(self):
        self._verify_test_tracker_info(return_false)

    def test_test_tracker_info_on_raise_pass(self):
        self._verify_test_tracker_info(raise_pass)

    def test_test_tracker_info_on_raise_failure(self):
        self._verify_test_tracker_info(raise_failure)

    def test_test_tracker_info_on_raise_sl4a(self):
        self._verify_test_tracker_info(raise_sl4a)

    def test_test_tracker_info_on_raise_generic(self):
        self._verify_test_tracker_info(raise_generic)

    def test_test_tracker_info_on_raise_generic_is_chained(self):
        @test_decorators.test_tracker_info(uuid='SOME_UID')
        def raise_generic():
            raise ValueError('I am a ValueError')

        with self.assertRaises(signals.TestError) as context:
            raise_generic()

        self.assertIsInstance(context.exception.__cause__, ValueError)

    def test_tti_returns_existing_test_pass(self):
        @test_decorators.test_tracker_info(uuid='SOME_UID')
        def test_raises_test_pass():
            raise signals.TestPass('Expected Message')

        with self.assertRaises(signals.TestPass) as context:
            test_raises_test_pass()

        self.assertEqual(context.exception.details, 'Expected Message')

    def test_tti_returns_existing_test_failure(self):
        @test_decorators.test_tracker_info(uuid='SOME_UID')
        def test_raises_test_failure():
            raise signals.TestFailure('Expected Message')

        with self.assertRaises(signals.TestFailure) as context:
            test_raises_test_failure()

        self.assertEqual(context.exception.details, 'Expected Message')

    def test_tti_returns_test_error_on_non_signal_error(self):
        expected_error = ValueError('Some Message')

        @test_decorators.test_tracker_info(uuid='SOME_UID')
        def test_raises_non_signal():
            raise expected_error

        with self.assertRaises(signals.TestError) as context:
            test_raises_non_signal()

        self.assertEqual(context.exception.details, expected_error)

    def test_tti_returns_test_pass_if_no_return_value_specified(self):
        @test_decorators.test_tracker_info(uuid='SOME_UID')
        def test_returns_nothing():
            pass

        with self.assertRaises(signals.TestPass):
            test_returns_nothing()

    def test_tti_returns_test_fail_if_return_value_is_truthy(self):
        """This is heavily frowned upon. Use signals.TestPass instead!"""
        @test_decorators.test_tracker_info(uuid='SOME_UID')
        def test_returns_truthy():
            return True

        with self.assertRaises(signals.TestPass):
            test_returns_truthy()

    def test_tti_returns_test_fail_if_return_value_is_falsy(self):
        """This is heavily frowned upon. Use signals.TestFailure instead!"""
        @test_decorators.test_tracker_info(uuid='SOME_UID')
        def test_returns_falsy_but_not_none():
            return False

        with self.assertRaises(signals.TestFailure):
            test_returns_falsy_but_not_none()

    def test_function_name(self):
        """Test test_func.__name__ returns its original unwrapped name"""
        @test_decorators.test_info(uuid='SOME_UID')
        def test_func():
            pass

        self.assertEqual(test_func.__name__, "test_func")

    def test_function_doc(self):
        """Test test_func.__doc__ returns its original unwrapped doc string"""
        @test_decorators.test_info(uuid='SOME_UID')
        def test_func():
            """DOC_STRING"""
            pass

        self.assertEqual(test_func.__doc__, "DOC_STRING")

    def test_function_module(self):
        """Test test_func.__module__ returns its original unwrapped module"""
        @test_decorators.test_info(uuid='SOME_UID')
        def test_func():
            pass

        self.assertEqual(test_func.__module__, self.__module__)


class MockTest(base_test.BaseTestClass):
    TEST_CASE_LIST = 'test_run_mock_test'
    TEST_LOGIC_ATTR = 'test_logic'

    @test_decorators.test_tracker_info(uuid=TEST_TRACKER_UUID)
    def test_run_mock_test(self):
        getattr(MockTest, MockTest.TEST_LOGIC_ATTR, None)()


class TestDecoratorIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp_dir = tempfile.mkdtemp()
        cls.MOCK_CONFIG = mobly_config_parser.TestRunConfig()
        cls.MOCK_CONFIG.testbed_name = 'SampleTestBed'
        cls.MOCK_CONFIG.log_path = cls.tmp_dir

        cls.MOCK_TEST_RUN_LIST = [(MockTest.__name__,
                                   [MockTest.TEST_CASE_LIST])]

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp_dir)

    def _run_with_test_logic(self, func):
        if hasattr(MockTest, MockTest.TEST_LOGIC_ATTR):
            delattr(MockTest, MockTest.TEST_LOGIC_ATTR)
        setattr(MockTest, MockTest.TEST_LOGIC_ATTR, func)
        self.test_runner = test_runner.TestRunner(self.MOCK_CONFIG,
                                                  self.MOCK_TEST_RUN_LIST)
        self.test_runner.run(MockTest)

    def _validate_results_has_extra(self, result, extra_key, extra_value):
        results = self.test_runner.results
        self.assertGreaterEqual(len(results.executed), 1,
                                'Expected at least one executed test.')
        record = results.executed[0]
        self.assertIsNotNone(record.extras,
                             'Expected the test record to have extras.')
        self.assertEqual(record.extras[extra_key], extra_value)

    def test_mock_test_with_raise_pass(self):
        self._run_with_test_logic(raise_pass)
        self._validate_results_has_extra(self.test_runner.results, UUID_KEY,
                                         TEST_TRACKER_UUID)

    def test_mock_test_with_raise_generic(self):
        self._run_with_test_logic(raise_generic)
        self._validate_results_has_extra(self.test_runner.results, UUID_KEY,
                                         TEST_TRACKER_UUID)


class RepeatedTestTests(unittest.TestCase):
    def test_all_error_types_count_toward_failures(self):
        def result_selector(results, _):
            self.assertIsInstance(results[0], AssertionError)
            self.assertIsInstance(results[1], signals.TestFailure)
            self.assertIsInstance(results[2], signals.TestError)
            self.assertIsInstance(results[3], IndexError)
            raise signals.TestPass('Expected failures occurred')

        @test_decorators.repeated_test(1, 3, result_selector)
        def test_case(_, attempt_number):
            if attempt_number == 1:
                raise AssertionError()
            elif attempt_number == 2:
                raise signals.TestFailure('Failed')
            elif attempt_number == 3:
                raise signals.TestError('Error')
            else:
                # Note that any Exception that does not fall into another bucket
                # is also considered a failure
                raise IndexError('Bad index')

        with self.assertRaises(signals.TestPass):
            test_case(mock.Mock())

    def test_passes_stop_repeating_the_test_case(self):
        def result_selector(results, _):
            self.assertEqual(len(results), 3)
            for result in results:
                self.assertIsInstance(result, signals.TestPass)
            raise signals.TestPass('Expected passes occurred')

        @test_decorators.repeated_test(3, 0, result_selector)
        def test_case(*_):
            raise signals.TestPass('Passed')

        with self.assertRaises(signals.TestPass):
            test_case(mock.Mock())

    def test_abort_signals_are_uncaught(self):
        @test_decorators.repeated_test(3, 0)
        def test_case(*_):
            raise signals.TestAbortClass('Abort All')

        with self.assertRaises(signals.TestAbortClass):
            test_case(mock.Mock())

    def test_keyboard_interrupt_is_uncaught(self):
        @test_decorators.repeated_test(3, 0)
        def test_case(*_):
            raise KeyboardInterrupt()

        with self.assertRaises(KeyboardInterrupt):
            test_case(mock.Mock())

    def test_teardown_and_setup_are_called_between_test_cases(self):
        mock_test_class = mock.Mock()
        @test_decorators.repeated_test(1, 1)
        def test_case(*_):
            raise signals.TestFailure('Failed')

        with self.assertRaises(signals.TestFailure):
            test_case(mock_test_class)

        self.assertTrue(mock_test_class.setup_test.called)
        self.assertTrue(mock_test_class.teardown_test.called)

    def test_result_selector_returned_value_gets_raised(self):
        def result_selector(*_):
            return signals.TestPass('Expect this to be raised.')

        @test_decorators.repeated_test(3, 0, result_selector=result_selector)
        def test_case(*_):
            raise signals.TestFailure('Result selector ignores this.')

        with self.assertRaises(signals.TestPass):
            test_case(mock.Mock())


if __name__ == '__main__':
    unittest.main()
