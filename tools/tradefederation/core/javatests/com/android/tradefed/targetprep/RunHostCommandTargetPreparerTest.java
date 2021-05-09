/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tradefed.targetprep;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.util.FileUtil;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.OutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Unit test for {@link RunHostCommandTargetPreparer}. */
@RunWith(JUnit4.class)
public final class RunHostCommandTargetPreparerTest {

    private static final String DEVICE_SERIAL = "123456";
    private static final String FULL_COMMAND = "command    \t\t\t  \t  argument $SERIAL";
    private static final String FULL_COMMAND_EXTRA_FILE = "command argument $EXTRA_FILE(test1) $EXTRA_FILE(test2)";

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private TestInformation mTestInfo;

    @Mock private RunHostCommandTargetPreparer.BgCommandLog mBgCommandLog;
    @Mock private IRunUtil mRunUtil;
    @Mock private IDeviceManager mDeviceManager;
    private RunHostCommandTargetPreparer mPreparer;

    @Before
    public void setUp() {
        mPreparer =
                new RunHostCommandTargetPreparer() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mRunUtil;
                    }

                    @Override
                    IDeviceManager getDeviceManager() {
                        return mDeviceManager;
                    }

                    @Override
                    protected List<BgCommandLog> createBgCommandLogs() {
                        return Collections.singletonList(mBgCommandLog);
                    }
                };
        when(mTestInfo.getDevice().getSerialNumber()).thenReturn(DEVICE_SERIAL);
    }

    @Test
    public void testSetUp() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-setup-command", FULL_COMMAND);
        optionSetter.setOptionValue("host-cmd-timeout", "10");

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);

        // Verify timeout and command (split, removed whitespace, and device serial)
        mPreparer.setUp(mTestInfo);
        verify(mRunUtil).runTimedCmd(eq(10L), eq("command"), eq("argument"), eq(DEVICE_SERIAL));

        // No flashing permit taken/returned by default
        verify(mDeviceManager, never()).takeFlashingPermit();
        verify(mDeviceManager, never()).returnFlashingPermit();
    }

    @Test
    public void testSetUp_withWorkDir() throws Exception {
        final OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("work-dir", "/working/directory");
        optionSetter.setOptionValue("host-setup-command", "command");
        optionSetter.setOptionValue("host-cmd-timeout", "10");

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);

        // Verify working directory and command execution
        mPreparer.setUp(mTestInfo);
        verify(mRunUtil).setWorkingDir(any());
        verify(mRunUtil).runTimedCmd(eq(10L), eq("command"));
    }

    @Test(expected = TargetSetupError.class)
    public void testSetUp_withErrors() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-setup-command", "command");
        optionSetter.setOptionValue("host-cmd-timeout", "10");

        // Verify that failed commands will throw exception during setup
        CommandResult result = new CommandResult(CommandStatus.FAILED);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);
        mPreparer.setUp(mTestInfo);
    }

    @Test
    public void testSetUp_flashingPermit() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-setup-command", FULL_COMMAND);
        optionSetter.setOptionValue("use-flashing-permit", "true");

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);

        // Verify command ran with flashing permit
        mPreparer.setUp(mTestInfo);
        InOrder inOrder = inOrder(mRunUtil, mDeviceManager);
        inOrder.verify(mDeviceManager).takeFlashingPermit();
        inOrder.verify(mRunUtil)
                .runTimedCmd(anyLong(), eq("command"), eq("argument"), eq(DEVICE_SERIAL));
        inOrder.verify(mDeviceManager).returnFlashingPermit();
    }

    @Test
    public void testTearDown() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-teardown-command", FULL_COMMAND);
        optionSetter.setOptionValue("host-cmd-timeout", "10");

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);

        // Verify timeout and command (split, removed whitespace, and device serial)
        mPreparer.tearDown(mTestInfo, null);
        verify(mRunUtil).runTimedCmd(eq(10L), eq("command"), eq("argument"), eq(DEVICE_SERIAL));

        // No flashing permit taken/returned by default
        verify(mDeviceManager, never()).takeFlashingPermit();
        verify(mDeviceManager, never()).returnFlashingPermit();
    }

    @Test
    public void testTearDown_withError() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-teardown-command", "command");
        optionSetter.setOptionValue("host-cmd-timeout", "10");

        // Verify that failed commands will NOT throw exception during teardown
        CommandResult result = new CommandResult(CommandStatus.FAILED);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);
        mPreparer.tearDown(mTestInfo, null);
    }

    @Test
    public void testTearDown_flashingPermit() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-teardown-command", FULL_COMMAND);
        optionSetter.setOptionValue("use-flashing-permit", "true");

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);

        // Verify command ran with flashing permit
        mPreparer.tearDown(mTestInfo, null);
        InOrder inOrder = inOrder(mRunUtil, mDeviceManager);
        inOrder.verify(mDeviceManager).takeFlashingPermit();
        inOrder.verify(mRunUtil)
                .runTimedCmd(anyLong(), eq("command"), eq("argument"), eq(DEVICE_SERIAL));
        inOrder.verify(mDeviceManager).returnFlashingPermit();
    }

    @Test
    public void testBgCommand() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-background-command", FULL_COMMAND);

        when(mRunUtil.runCmdInBackground(anyList(), any())).thenReturn(mock(Process.class));
        OutputStream os = mock(OutputStream.class);
        when(mBgCommandLog.getOutputStream()).thenReturn(os);

        // Verify command (split, removed whitespace, and device serial) and output stream
        mPreparer.setUp(mTestInfo);
        verify(mRunUtil)
                .runCmdInBackground(
                        eq(Arrays.asList("command", "argument", DEVICE_SERIAL)), eq(os));
    }

    @Test
    public void testSetUp_extraFile() throws Exception {
        BuildInfo stubBuild = new BuildInfo("stub", "stub");
        File tmpDir = FileUtil.createTempDir("tmp");
        File file1 = new File(tmpDir, "test1");
        File file2 = new File(tmpDir, "test2");
        FileUtil.writeToFile("ddd", file1);
        FileUtil.writeToFile("ddd", file2);
        stubBuild.setFile("test1", file1, "0");
        stubBuild.setFile("test2", file2, "0");

        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-setup-command", FULL_COMMAND_EXTRA_FILE);
        optionSetter.setOptionValue("host-cmd-timeout", "10");

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);
        when(mTestInfo.getBuildInfo()).thenReturn(stubBuild);

        // Verify timeout and command (split, removed whitespace, and device serial)
        mPreparer.setUp(mTestInfo);
        verify(mRunUtil).runTimedCmd(eq(10L), eq("command"), eq("argument"),
                eq(file1.getAbsolutePath()), eq(file2.getAbsolutePath()));

        // No flashing permit taken/returned by default
        verify(mDeviceManager, never()).takeFlashingPermit();
        verify(mDeviceManager, never()).returnFlashingPermit();

        FileUtil.recursiveDelete(tmpDir);
    }
    @Test
    public void testSetUp_extraFileNotExist() throws Exception {
        BuildInfo stubBuild = new BuildInfo("stub", "stub");

        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue("host-setup-command", FULL_COMMAND_EXTRA_FILE);
        optionSetter.setOptionValue("host-cmd-timeout", "10");

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(anyLong(), any())).thenReturn(result);
        when(mTestInfo.getBuildInfo()).thenReturn(stubBuild);

        // Verify timeout and command (split, removed whitespace, and device serial)
        mPreparer.setUp(mTestInfo);
        verify(mRunUtil).runTimedCmd(eq(10L), eq("command"), eq("argument"),
                eq("$EXTRA_FILE(test1)"), eq("$EXTRA_FILE(test2)"));

        // No flashing permit taken/returned by default
        verify(mDeviceManager, never()).takeFlashingPermit();
        verify(mDeviceManager, never()).returnFlashingPermit();
    }
}
