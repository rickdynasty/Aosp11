/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tradefed.testtype;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;

/** Unit tests for {@link IsolatedHostTest}. */
@RunWith(JUnit4.class)
public class IsolatedHostTestTest {

    private static final String PACKAGE = "/com/android/tradefed/referencetests";
    private IsolatedHostTest mHostTest;
    private ITestInvocationListener mListener;
    private IBuildInfo mMockBuildInfo;
    private ServerSocket mMockServer;
    private File mMockTestDir;

    /**
     * (copied and altered from JarHostTestTest) Helper to read a file from the res/testtype
     * directory and return it.
     *
     * @param filename the name of the file in the resources.
     * @param parentDir dir where to put the jar. Null if in default tmp directory.
     * @param name name to use in the target directory for the jar.
     * @return the extracted jar file.
     */
    protected File getJarResource(String filename, File parentDir, String name) throws IOException {
        File jarFile = null;
        try (InputStream jarFileStream = getClass().getResourceAsStream(filename);
                InputStream qualifiedPathStream =
                        getClass().getResourceAsStream(PACKAGE + filename)) {
            if (jarFileStream == null && qualifiedPathStream == null) {
                throw new RuntimeException(String.format("Failed to read resource '%s'", filename));
            }
            jarFile = new File(parentDir, name);
            jarFile.createNewFile();
            if (jarFileStream != null) {
                FileUtil.writeToFile(jarFileStream, jarFile);
            } else {
                FileUtil.writeToFile(qualifiedPathStream, jarFile);
            }
        }
        return jarFile;
    }

    @Before
    public void setUp() throws Exception {
        mHostTest = new IsolatedHostTest();
        mListener = EasyMock.createMock(ITestInvocationListener.class);
        mMockBuildInfo = Mockito.mock(IBuildInfo.class);
        mMockServer = Mockito.mock(ServerSocket.class);
        IInvocationContext context = new InvocationContext();
        context.addDeviceBuildInfo("device", mMockBuildInfo);
        mHostTest.setBuild(mMockBuildInfo);
        mHostTest.setServer(mMockServer);
        mMockTestDir = FileUtil.createTempDir("isolatedhosttesttest");
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mMockTestDir);
    }

    @Test
    public void testRobolectricResourcesPositive() throws Exception {
        OptionSetter setter = new OptionSetter(mHostTest);
        setter.setOptionValue("use-robolectric-resources", "true");
        doReturn(mMockTestDir).when(mMockBuildInfo).getFile(BuildInfoFileKey.HOST_LINKED_DIR);
        doReturn(36000).when(mMockServer).getLocalPort();
        doReturn(Inet4Address.getByName("localhost")).when(mMockServer).getInetAddress();

        List<String> commandArgs = mHostTest.compileCommandArgs("");
        assertTrue(commandArgs.contains("-Drobolectric.offline=true"));
        assertTrue(commandArgs.contains("-Drobolectric.logging=stdout"));
        assertTrue(commandArgs.contains("-Drobolectric.resourcesMode=binary"));
        assertTrue(commandArgs.stream().anyMatch(s -> s.contains("-Drobolectric.dependency.dir=")));
    }

    @Test
    public void testRobolectricResourcesNegative() throws Exception {
        OptionSetter setter = new OptionSetter(mHostTest);
        setter.setOptionValue("use-robolectric-resources", "false");
        doReturn(mMockTestDir).when(mMockBuildInfo).getFile(BuildInfoFileKey.HOST_LINKED_DIR);
        doReturn(36000).when(mMockServer).getLocalPort();
        doReturn(Inet4Address.getByName("localhost")).when(mMockServer).getInetAddress();

        List<String> commandArgs = mHostTest.compileCommandArgs("");
        assertFalse(commandArgs.contains("-Drobolectric.offline=true"));
        assertFalse(commandArgs.contains("-Drobolectric.logging=stdout"));
        assertFalse(commandArgs.contains("-Drobolectric.resourcesMode=binary"));
        assertFalse(
                commandArgs.stream().anyMatch(s -> s.contains("-Drobolectric.dependency.dir=")));
    }

    /**
     * TODO(murj) need to figure out a strategy with jdesprez on how to test the classpath
     * determination functionality.
     *
     * @throws Exception
     */
    @Test
    public void testRobolectricResourcesClasspathPositive() throws Exception {
        OptionSetter setter = new OptionSetter(mHostTest);
        setter.setOptionValue("use-robolectric-resources", "true");
    }

    /**
     * TODO(murj) same as above
     *
     * @throws Exception
     */
    @Test
    public void testRobolectricResourcesClasspathNegative() throws Exception {
        OptionSetter setter = new OptionSetter(mHostTest);
        setter.setOptionValue("use-robolectric-resources", "false");
    }

    private OptionSetter setUpSimpleMockJarTest(String jarName) throws Exception {
        OptionSetter setter = new OptionSetter(mHostTest);
        getJarResource("/" + jarName, mMockTestDir, jarName);
        doReturn(mMockTestDir).when(mMockBuildInfo).getFile(BuildInfoFileKey.HOST_LINKED_DIR);
        doReturn(mMockTestDir).when(mMockBuildInfo).getFile(BuildInfoFileKey.TESTDIR_IMAGE);
        setter.setOptionValue("jar", jarName);
        setter.setOptionValue("exclude-paths", "org/junit");
        setter.setOptionValue("exclude-paths", "junit");
        return setter;
    }

    @Test
    public void testSimpleFailingTestLifecycle() throws Exception {
        final String jarName = "SimpleFailingTest.jar";
        final String className = "com.android.tradefed.referencetests.SimpleFailingTest";
        setUpSimpleMockJarTest(jarName);
        TestInformation testInfo = TestInformation.newBuilder().build();
        TestDescription test = new TestDescription(className, "test2Plus2");

        // One failing test flow
        mListener.testRunStarted((String) EasyMock.anyObject(), EasyMock.eq(1));
        mListener.testStarted(EasyMock.eq(test), EasyMock.anyInt());
        mListener.testFailed(EasyMock.eq(test), (String) EasyMock.anyObject());
        mListener.testEnded(
                EasyMock.eq(test),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testSimplePassingTestLifecycle() throws Exception {
        final String jarName = "SimplePassingTest.jar";
        final String className = "com.android.tradefed.referencetests.SimplePassingTest";
        setUpSimpleMockJarTest(jarName);
        TestInformation testInfo = TestInformation.newBuilder().build();
        TestDescription test = new TestDescription(className, "test2Plus2");

        // One passing test flow
        mListener.testRunStarted((String) EasyMock.anyObject(), EasyMock.eq(1));
        mListener.testStarted(EasyMock.eq(test), EasyMock.anyInt());
        mListener.testEnded(
                EasyMock.eq(test),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testIncludeFilterByMethodLifecycle() throws Exception {
        final String jarName = "OnePassingOneFailingTest.jar";
        final String className = "com.android.tradefed.referencetests.OnePassingOneFailingTest";
        setUpSimpleMockJarTest(jarName);

        mHostTest.addIncludeFilter(className + "#test1Passing");
        TestInformation testInfo = TestInformation.newBuilder().build();
        TestDescription test = new TestDescription(className, "test1Passing");

        // One passing test flow
        mListener.testRunStarted((String) EasyMock.anyObject(), EasyMock.eq(1));
        mListener.testStarted(EasyMock.eq(test), EasyMock.anyInt());
        mListener.testEnded(
                EasyMock.eq(test),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testIncludeFilterByClassLifecycle() throws Exception {
        final String jarName = "OnePassingOneFailingTest.jar";
        final String className = "com.android.tradefed.referencetests.OnePassingOneFailingTest";
        setUpSimpleMockJarTest(jarName);

        mHostTest.addIncludeFilter(className);
        TestInformation testInfo = TestInformation.newBuilder().build();
        TestDescription test1 = new TestDescription(className, "test1Passing");
        TestDescription test2 = new TestDescription(className, "test2Failing");

        // One passing test followed by one failing test flow
        mListener.testRunStarted((String) EasyMock.anyObject(), EasyMock.eq(2));
        mListener.testStarted(EasyMock.eq(test1), EasyMock.anyInt());
        mListener.testEnded(
                EasyMock.eq(test1),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testStarted(EasyMock.eq(test2), EasyMock.anyInt());
        mListener.testFailed(EasyMock.eq(test2), (String) EasyMock.anyObject());
        mListener.testEnded(
                EasyMock.eq(test2),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testIncludeFilterByModuleLifecycle() throws Exception {
        final String jarName = "OnePassingOneFailingTest.jar";
        final String className = "com.android.tradefed.referencetests.OnePassingOneFailingTest";
        setUpSimpleMockJarTest(jarName);

        mHostTest.addIncludeFilter("com.android.tradefed.referencetests");
        TestInformation testInfo = TestInformation.newBuilder().build();
        TestDescription test1 = new TestDescription(className, "test1Passing");
        TestDescription test2 = new TestDescription(className, "test2Failing");

        // One passing test followed by one failing test flow
        mListener.testRunStarted((String) EasyMock.anyObject(), EasyMock.eq(2));
        mListener.testStarted(EasyMock.eq(test1), EasyMock.anyInt());
        mListener.testEnded(
                EasyMock.eq(test1),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testStarted(EasyMock.eq(test2), EasyMock.anyInt());
        mListener.testFailed(EasyMock.eq(test2), (String) EasyMock.anyObject());
        mListener.testEnded(
                EasyMock.eq(test2),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testExcludeFilterByMethodLifecycle() throws Exception {
        final String jarName = "OnePassingOneFailingTest.jar";
        final String className = "com.android.tradefed.referencetests.OnePassingOneFailingTest";
        setUpSimpleMockJarTest(jarName);

        mHostTest.addExcludeFilter(className + "#test2Failing");
        TestInformation testInfo = TestInformation.newBuilder().build();
        TestDescription test = new TestDescription(className, "test1Passing");

        // One passing test flow
        mListener.testRunStarted((String) EasyMock.anyObject(), EasyMock.eq(1));
        mListener.testStarted(EasyMock.eq(test), EasyMock.anyInt());
        mListener.testEnded(
                EasyMock.eq(test),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testExcludeFilterByClassLifecycle() throws Exception {
        final String jarName = "OnePassingOneFailingTest.jar";
        final String className = "com.android.tradefed.referencetests.OnePassingOneFailingTest";
        setUpSimpleMockJarTest(jarName);

        mHostTest.addExcludeFilter(className);
        TestInformation testInfo = TestInformation.newBuilder().build();

        // Typical no tests found flow
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testExcludeFilterByModuleLifecycle() throws Exception {
        final String jarName = "OnePassingOneFailingTest.jar";
        setUpSimpleMockJarTest(jarName);

        mHostTest.addExcludeFilter("com.android.tradefed.referencetests");
        TestInformation testInfo = TestInformation.newBuilder().build();

        // Typical no tests found flow
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testConflictingFilterLifecycle() throws Exception {
        final String jarName = "OnePassingOneFailingTest.jar";
        final String className = "com.android.tradefed.referencetests.OnePassingOneFailingTest";
        setUpSimpleMockJarTest(jarName);

        mHostTest.addIncludeFilter(className + "#test1Passing");
        mHostTest.addIncludeFilter(className + "#test2Failing");
        mHostTest.addExcludeFilter(className + "#test2Failing");
        TestInformation testInfo = TestInformation.newBuilder().build();
        TestDescription test = new TestDescription(className, "test1Passing");

        // One passing test flow
        mListener.testRunStarted((String) EasyMock.anyObject(), EasyMock.eq(1));
        mListener.testStarted(EasyMock.eq(test), EasyMock.anyInt());
        mListener.testEnded(
                EasyMock.eq(test),
                EasyMock.anyInt(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testConflictingFilterNoTestsLeftLifecycle() throws Exception {
        final String jarName = "OnePassingOneFailingTest.jar";
        final String className = "com.android.tradefed.referencetests.OnePassingOneFailingTest";
        setUpSimpleMockJarTest(jarName);

        mHostTest.addIncludeFilter(className + "#test2Failing");
        mHostTest.addExcludeFilter(className + "#test2Failing");
        TestInformation testInfo = TestInformation.newBuilder().build();

        // Typical no tests found flow
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testParameterizedTest() throws Exception {
        final String jarName = "OnePassOneFailParamTest.jar";
        setUpSimpleMockJarTest(jarName);
        TestInformation testInfo = TestInformation.newBuilder().build();
        String className = "com.android.tradefed.referencetests.OnePassOneFailParamTest";

        TestDescription test1 = new TestDescription(className, "testBoolean[0]");
        TestDescription test2 = new TestDescription(className, "testBoolean[1]");

        mListener.testRunStarted(EasyMock.eq(className), EasyMock.eq(2));
        mListener.testStarted(EasyMock.eq(test1), EasyMock.anyLong());
        mListener.testEnded(
                EasyMock.eq(test1),
                EasyMock.anyLong(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testStarted(EasyMock.eq(test2), EasyMock.anyLong());
        mListener.testFailed(EasyMock.eq(test2), (String) EasyMock.anyObject());
        mListener.testEnded(
                EasyMock.eq(test2),
                EasyMock.anyLong(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }

    @Test
    public void testParameterizedTest_exclude() throws Exception {
        final String jarName = "OnePassOneFailParamTest.jar";
        setUpSimpleMockJarTest(jarName);
        TestInformation testInfo = TestInformation.newBuilder().build();
        String className = "com.android.tradefed.referencetests.OnePassOneFailParamTest";

        TestDescription test1 = new TestDescription(className, "testBoolean[0]");
        mHostTest.addExcludeFilter(className + "#testBoolean[1]");

        mListener.testRunStarted(EasyMock.eq(className), EasyMock.eq(1));
        mListener.testStarted(EasyMock.eq(test1), EasyMock.anyLong());
        mListener.testEnded(
                EasyMock.eq(test1),
                EasyMock.anyLong(),
                EasyMock.<HashMap<String, Metric>>anyObject());
        mListener.testLog(
                (String) EasyMock.anyObject(), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mListener.testRunEnded(EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mListener);
        mHostTest.run(testInfo, mListener);
        EasyMock.verify(mListener);
    }
}
