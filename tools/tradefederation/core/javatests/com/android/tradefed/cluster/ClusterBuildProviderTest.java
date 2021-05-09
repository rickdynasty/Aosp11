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
package com.android.tradefed.cluster;

import static org.junit.Assert.assertFalse;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.FuseUtil;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.ZipUtil2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link ClusterBuildProvider}. */
@RunWith(JUnit4.class)
public class ClusterBuildProviderTest {

    private static final String RESOURCE_KEY = "resource_key_1";
    private static final String EXTRA_RESOURCE_KEY = "resource_key_2";
    private static final String ZIP_KEY = "resource_key_3.zip";
    private static final String FILE_NAME_IN_ZIP = "resource.txt";

    private File mRootDir;
    private File mResourceFile;
    private String mResourceUrl;
    private Map<String, File> mDownloadCache;
    private Map<String, File> mCreatedResources;
    private TestResourceDownloader mSpyDownloader;
    private FuseUtil mMockFuseUtil;

    @Before
    public void setUp() throws IOException {
        mRootDir = FileUtil.createTempDir("ClusterBuildProvider");
        File zipDir = FileUtil.createTempDir("ClusterBuildProviderZip");
        try {
            File fileInZip = new File(zipDir, FILE_NAME_IN_ZIP);
            fileInZip.createNewFile();
            mResourceFile = ZipUtil.createZip(Arrays.asList(fileInZip));
        } finally {
            FileUtil.recursiveDelete(zipDir);
        }
        mResourceUrl = mResourceFile.toURI().toURL().toString();
        mDownloadCache = ClusterBuildProvider.sDownloadCache.get();
        mCreatedResources = ClusterBuildProvider.sCreatedResources.get();
        mSpyDownloader = Mockito.spy(new TestResourceDownloader());
        mMockFuseUtil = Mockito.mock(FuseUtil.class);
        Mockito.when(mMockFuseUtil.canMountZip()).thenReturn(false);
    }

    @After
    public void tearDown() {
        if (mDownloadCache != null) {
            mDownloadCache.clear();
        }
        if (mCreatedResources != null) {
            mCreatedResources.clear();
        }
        FileUtil.deleteFile(mResourceFile);
        FileUtil.recursiveDelete(mRootDir);
    }

    private ClusterBuildProvider createClusterBuildProvider() {
        ClusterBuildProvider provider =
                new ClusterBuildProvider() {
                    @Override
                    TestResourceDownloader createTestResourceDownloader() {
                        return mSpyDownloader;
                    }

                    @Override
                    FuseUtil getFuseUtil() {
                        return mMockFuseUtil;
                    }
                };
        provider.setRootDir(mRootDir);
        provider.getTestResources().put(RESOURCE_KEY, mResourceUrl);
        return provider;
    }

    private void verifyDownloadedResource() throws IOException {
        File file = new File(mRootDir, RESOURCE_KEY);
        Assert.assertTrue(file.isFile());
        Assert.assertEquals(file, mDownloadCache.get(mResourceUrl));
        Mockito.verify(mSpyDownloader).download(Mockito.any(), Mockito.eq(file));
    }

    /** Test one provider with two identical URLs. */
    @Test
    public void testGetBuild_multipleTestResources() throws BuildRetrievalError, IOException {
        ClusterBuildProvider provider = createClusterBuildProvider();
        provider.getTestResources().put(ZIP_KEY, mResourceUrl);
        provider.getBuild();

        verifyDownloadedResource();
        Assert.assertEquals(1, mDownloadCache.size());
        Assert.assertEquals(2, mCreatedResources.size());
        File zipFile = new File(mRootDir, ZIP_KEY);
        Assert.assertTrue(zipFile.isFile());
        Assert.assertTrue(new File(mRootDir, FILE_NAME_IN_ZIP).isFile());
        Mockito.verify(mSpyDownloader, Mockito.never())
                .download(Mockito.any(), Mockito.eq(zipFile));
    }

    /** Test one provider with different decompress directories. */
    @Test
    public void testGetBuild_decompressTestResources() throws BuildRetrievalError, IOException {
        final String url = mResourceFile.toURI().toURL().toString();
        ClusterBuildProvider provider = createClusterBuildProvider();
        provider.getTestResources().put(ZIP_KEY, url);
        provider.getDecompressTestResources().put(RESOURCE_KEY, "dir1");
        provider.getDecompressTestResources().put(ZIP_KEY, "dir2");
        provider.getBuild();

        verifyDownloadedResource();
        Assert.assertEquals(1, mDownloadCache.size());
        Assert.assertEquals(2, mCreatedResources.size());
        File file = new File(mRootDir, ZIP_KEY);
        Assert.assertTrue(file.isFile());
        assertFalse(FileUtil.getFileForPath(mRootDir, FILE_NAME_IN_ZIP).isFile());
        Assert.assertTrue(FileUtil.getFileForPath(mRootDir, "dir1", FILE_NAME_IN_ZIP).isFile());
        Assert.assertTrue(FileUtil.getFileForPath(mRootDir, "dir2", FILE_NAME_IN_ZIP).isFile());
        Mockito.verify(mSpyDownloader, Mockito.never()).download(Mockito.any(), Mockito.eq(file));
    }

    /** Test one provider with different decompress directories when zip mount is supported. */
    @Test
    public void testGetBuild_decompressTestResources_withMountZip()
            throws BuildRetrievalError, IOException {
        List<File> mountDirs = new ArrayList<>();
        try {
            Mockito.when(mMockFuseUtil.canMountZip()).thenReturn(true);
            Mockito.doAnswer(
                            new Answer() {
                                @Override
                                public Object answer(InvocationOnMock invocation) throws Throwable {
                                    File zipFile = (File) invocation.getArgument(0);
                                    File mountDir = (File) invocation.getArgument(1);
                                    mountDirs.add(mountDir);
                                    try (ZipFile zip = new ZipFile(zipFile)) {
                                        ZipUtil2.extractZip(zip, mountDir);
                                    }
                                    return null;
                                }
                            })
                    .when(mMockFuseUtil)
                    .mountZip(Mockito.any(File.class), Mockito.any(File.class));
            final String url = mResourceFile.toURI().toURL().toString();
            ClusterBuildProvider provider = createClusterBuildProvider();
            provider.getTestResources().put(ZIP_KEY, url);
            provider.getDecompressTestResources().put(RESOURCE_KEY, "dir1");
            provider.getDecompressTestResources().put(ZIP_KEY, "dir2");

            IBuildInfo buildInfo = provider.getBuild();

            verifyDownloadedResource();
            Assert.assertEquals(1, mDownloadCache.size());
            Assert.assertEquals(2, mCreatedResources.size());
            File file = new File(mRootDir, ZIP_KEY);
            Assert.assertTrue(file.isFile());
            assertFalse(FileUtil.getFileForPath(mRootDir, FILE_NAME_IN_ZIP).isFile());
            Assert.assertTrue(FileUtil.getFileForPath(mRootDir, "dir1", FILE_NAME_IN_ZIP).isFile());
            Assert.assertTrue(FileUtil.getFileForPath(mRootDir, "dir2", FILE_NAME_IN_ZIP).isFile());
            Mockito.verify(mSpyDownloader, Mockito.never())
                    .download(Mockito.any(), Mockito.eq(file));
        } finally {
            for (File dir : mountDirs) {
                FileUtil.recursiveDelete(dir);
            }
        }
    }

    /** Test decompress the resource outside of working directory. */
    @Test(expected = BuildRetrievalError.class)
    public void testGetBuild_invalidDecompressDirectory() throws BuildRetrievalError {
        ClusterBuildProvider provider = createClusterBuildProvider();
        provider.getDecompressTestResources().put(RESOURCE_KEY, "../out");
        provider.getBuild();
    }

    /** Test two providers downloading from the same URL. */
    @Test
    public void testGetBuild_multipleBuildProviders() throws BuildRetrievalError, IOException {
        ClusterBuildProvider provider1 = createClusterBuildProvider();
        ClusterBuildProvider provider2 = createClusterBuildProvider();
        provider1.getBuild();
        provider2.getBuild();

        verifyDownloadedResource();
        Assert.assertEquals(1, mDownloadCache.size());
        Assert.assertEquals(1, mCreatedResources.size());
    }

    /** Test {@link ClusterBuildProvider#getBuild()} in an invocation thread. */
    private void testGetBuild(File extraResourceFile) throws BuildRetrievalError, IOException {
        ClusterBuildProvider provider = createClusterBuildProvider();
        provider.getTestResources()
                .put(EXTRA_RESOURCE_KEY, extraResourceFile.toURI().toURL().toString());
        provider.getBuild();

        verifyDownloadedResource();
        Assert.assertEquals(2, mDownloadCache.size());
        Assert.assertEquals(2, mCreatedResources.size());
        File file = new File(mRootDir, EXTRA_RESOURCE_KEY);
        Assert.assertTrue(file.isFile());
        Mockito.verify(mSpyDownloader).download(Mockito.any(), Mockito.eq(file));
    }

    /** Test two invocation threads downloading twice from the same URL. */
    @Test
    public void testGetBuild_multipleInvocations()
            throws BuildRetrievalError, IOException, InterruptedException {
        File sharedResourceFile = FileUtil.createTempFile("SharedTestResource", ".txt");
        ClusterBuildProviderTest anotherTest = new ClusterBuildProviderTest();
        try {
            ArrayList<Throwable> threadExceptions = new ArrayList<>();
            Runnable runnable =
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                anotherTest.setUp();
                                anotherTest.testGetBuild(sharedResourceFile);
                            } catch (Throwable e) {
                                threadExceptions.add(e);
                            }
                        }
                    };

            ThreadGroup anotherGroup = new ThreadGroup("unit test");
            anotherGroup.setDaemon(true);
            Thread anotherThread =
                    new Thread(anotherGroup, runnable, "ClusterBuildProviderTestThread");
            // Terminate the thread when the main thread throws an exception and exits.
            anotherThread.setDaemon(true);
            anotherThread.start();
            testGetBuild(sharedResourceFile);
            anotherThread.join();
            if (threadExceptions.size() > 0) {
                throw new AssertionError(
                        anotherThread.getName() + " failed.", threadExceptions.get(0));
            }
        } finally {
            FileUtil.deleteFile(sharedResourceFile);
            anotherTest.tearDown();
        }
    }

    @Test
    public void testCleanUp() throws IOException {
        List<File> zipMounts = new ArrayList<>();
        try {
            ClusterBuildInfo buildInfo = new ClusterBuildInfo(mRootDir, "buildId", "buildName");
            for (int i = 0; i < 10; i++) {
                File dir = FileUtil.createTempDir("ClusterBuildProvider");
                buildInfo.addZipMount(dir);
                zipMounts.add(dir);
            }

            ClusterBuildProvider provider = createClusterBuildProvider();
            provider.cleanUp(buildInfo);

            for (File dir : zipMounts) {
                Mockito.verify(mMockFuseUtil).unmountZip(dir);
                assertFalse(dir.exists());
            }
        } finally {
            for (File dir : zipMounts) {
                FileUtil.recursiveDelete(dir);
            }
        }
    }
}
