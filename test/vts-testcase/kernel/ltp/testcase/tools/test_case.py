#
# Copyright (C) 2020 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import os
import re

import ltp_enums
import ltp_configs


class TestCase(object):
    """Stores name, path, and param information for each test case.

    All class initiation inputs are assumed to be already validated by
    test case parser.

    Attributes:
        testsuite: string, name of testsuite to which the testcase belongs
        testname: string, name of the test case
        command: string, the command to run the test case
        _args: list of string, test case command line arguments
        requirement_state: RequirementState, enum representing requirement
                            check results
        note: string, a place to store additional note for the test case
              such as what environment requirement did not satisfy.
        is_staging: bool, whether test case is a staging test
        is_filtered: bool, whether test case is excluded by filter
    """

    def __init__(self, testsuite, testname, command):
        self.testsuite = testsuite
        self.testname = testname
        self._command = command
        self.requirement_state = ltp_enums.RequirementState.UNCHECKED
        self.note = ""
        self.is_staging = False
        self.is_mandatory = False
        self.is_filtered = False

    @property
    def note(self):
        """Get the note"""
        return self._note

    @note.setter
    def note(self, note):
        """Set the note"""
        self._note = note

    @property
    def requirement_state(self):
        """Get the requirement state"""
        return self._requirement_state

    @requirement_state.setter
    def requirement_state(self, requirement_state):
        """Set the requirement state"""
        self._requirement_state = requirement_state

    @property
    def testsuite(self):
        """Get the test suite's name."""
        return self._testsuite

    @testsuite.setter
    def testsuite(self, testsuite):
        """Set the test suite's name."""
        self._testsuite = testsuite

    @property
    def testname(self):
        """Get the test case's name."""
        return self._testname

    @testname.setter
    def testname(self, testname):
        """Set the test case's name."""
        self._testname = testname

    @property
    def command(self):
        """Get the test case's command."""
        return self._command

    @property
    def fullname(self):
        """Return full test name in <testsuite-testname> format"""
        return "%s.%s" % (self.testsuite, self.testname)

    def __str__(self):
        return self.fullname

    @property
    def is_staging(self):
        '''Whether this test is a staging test.'''
        return self._is_staging

    @is_staging.setter
    def is_staging(self, is_staging):
        '''Set whether this test is a staging test.'''
        self._is_staging = is_staging

    @property
    def is_mandatory(self):
        '''Whether this test is a mandatory test.'''
        return self._is_mandatory

    @is_mandatory.setter
    def is_mandatory(self, is_mandatory):
        '''Set whether this test is a mandatory test.'''
        self._is_mandatory = is_mandatory

    @property
    def is_filtered(self):
        '''Whether this test has been filtered out.'''
        return self._is_filtered

    @is_filtered.setter
    def is_filtered(self, is_filtered):
        '''Set whether this test has been filtered out.'''
        self._is_filtered = is_filtered
