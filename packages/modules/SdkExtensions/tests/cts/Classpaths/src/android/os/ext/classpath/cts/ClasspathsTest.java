/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.os.ext.classpath.cts;

import static android.compat.testing.Classpaths.ClasspathType.BOOTCLASSPATH;
import static android.compat.testing.Classpaths.ClasspathType.DEX2OATBOOTCLASSPATH;
import static android.compat.testing.Classpaths.ClasspathType.SYSTEMSERVERCLASSPATH;
import static android.os.ext.classpath.cts.ClasspathsTest.ClasspathSubject.assertThat;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Fact.fact;
import static com.google.common.truth.Fact.simpleFact;
import static com.google.common.truth.Truth.assertAbout;

import android.compat.testing.Classpaths;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Fact;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.IterableSubject;
import com.google.common.truth.Ordered;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the contents of *CLASSPATH environ variables on a device.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class ClasspathsTest extends BaseHostJUnit4Test {

    // A selection of jars on *CLASSPATH that cover all categories:
    // - ART apex jar
    // - Non-updatable apex jar
    // - Updatable apex jar
    // - System jar on BOOTCLASSPATH
    // - System jar on SYSTEMSERVERCLASSPATH
    private static final String FRAMEWORK_JAR = "/system/framework/framework.jar";
    private static final String ICU4J_JAR = "/apex/com.android.i18n/javalib/core-icu4j.jar";
    private static final String LIBART_JAR = "/apex/com.android.art/javalib/core-libart.jar";
    private static final String SDKEXTENSIONS_JAR =
            "/apex/com.android.sdkext/javalib/framework-sdkextensions.jar";
    private static final String SERVICES_JAR = "/system/framework/services.jar";

    @Test
    public void testBootclasspath() throws Exception {
        ImmutableList<String> jars = Classpaths.getJarsOnClasspath(getDevice(), BOOTCLASSPATH);

        assertThat(jars).containsNoDuplicates();

        assertThat(jars)
                .containsAtLeast(LIBART_JAR, FRAMEWORK_JAR, ICU4J_JAR, SDKEXTENSIONS_JAR)
                .inOrder();
        assertThat(jars)
                .doesNotContain(SERVICES_JAR);

        ImmutableList<String> expectedPrefixes = ImmutableList.of(
                "/apex/com.android.art/",
                "/system/",
                "/system_ext/",
                "/apex/com.android.i18n/",
                "/apex/");
        assertThat(jars)
                .prefixesMatch(expectedPrefixes)
                .inOrder();
    }

    @Test
    public void testDex2oatBootclasspath() throws Exception {
        ImmutableList<String> jars = Classpaths.getJarsOnClasspath(getDevice(),
                DEX2OATBOOTCLASSPATH);

        assertThat(jars).containsNoDuplicates();

        // DEX2OATBOOTCLASSPATH should only contain ART, core-icu4j, and platform system jars
        assertThat(jars)
                .containsAtLeast(LIBART_JAR, FRAMEWORK_JAR, ICU4J_JAR)
                .inOrder();
        assertThat(jars)
                .containsNoneOf(SDKEXTENSIONS_JAR, SERVICES_JAR);

        ImmutableList<String> expectedPrefixes = ImmutableList.of(
                "/apex/com.android.art/", "/system/", "/system_ext/", "/apex/com.android.i18n/");
        assertThat(jars)
                .prefixesMatch(expectedPrefixes)
                .inOrder();
    }

    @Test
    public void testSystemServerClasspath() throws Exception {
        ImmutableList<String> jars = Classpaths.getJarsOnClasspath(getDevice(),
                SYSTEMSERVERCLASSPATH);

        assertThat(jars).containsNoDuplicates();

        assertThat(jars).containsNoneOf(LIBART_JAR, FRAMEWORK_JAR, ICU4J_JAR, SDKEXTENSIONS_JAR);
        assertThat(jars).contains(SERVICES_JAR);

        ImmutableList<String> expectedPrefixes = ImmutableList.of(
                "/system/", "/system_ext/", "/apex/");
        assertThat(jars)
                .prefixesMatch(expectedPrefixes)
                .inOrder();
    }

    final static class ClasspathSubject extends IterableSubject {

        private static final Ordered EMPTY_ORDERED = () -> {
        };

        private final ImmutableList<String> actual;

        protected ClasspathSubject(FailureMetadata metadata, ImmutableList<String> iterable) {
            super(metadata, iterable);
            actual = iterable;
        }

        public static ClasspathSubject assertThat(ImmutableList<String> jars) {
            return assertAbout(ClasspathSubject::new).that(jars);
        }

        /**
         * Checks that the actual iterable contains only jars that start with the expected prefixes
         * or fails.
         *
         * <p>To also test that the prefixes appear in the given order, make a call to {@code
         * inOrder}
         * on the object returned by this method. The expected elements must appear in the given
         * order within the actual elements.
         */
        public Ordered prefixesMatch(ImmutableList<String> expected) {
            checkArgument(expected.stream().distinct().count() == expected.size(),
                    "No duplicates are allowed in expected values.");

            ImmutableList.Builder<String> unexpectedJars = ImmutableList.builder();
            boolean ordered = true;
            int currentPrefixIndex = expected.isEmpty() ? -1 : 0;
            for (String jar : actual) {
                int prefixIndex = findFirstMatchingPrefix(jar, expected);
                if (prefixIndex == -1) {
                    unexpectedJars.add(jar);
                    continue;
                }
                if (prefixIndex != currentPrefixIndex) {
                    if (prefixIndex < currentPrefixIndex) {
                        ordered = false;
                    }
                    currentPrefixIndex = prefixIndex;
                }
            }

            ImmutableList<String> unexpected = unexpectedJars.build();
            if (!unexpected.isEmpty()) {
                Fact expectedOrder = fact("expected jar filepaths to be prefixes with one of",
                        expected);
                ImmutableList.Builder<Fact> facts = ImmutableList.builder();
                for (String e : unexpected) {
                    facts.add(fact("unexpected", e));
                }
                facts.add(simpleFact("---"));
                facts.add(simpleFact(""));
                failWithoutActual(expectedOrder, facts.build().toArray(new Fact[0]));
                return EMPTY_ORDERED;
            }

            return ordered ? EMPTY_ORDERED : () -> failWithActual(
                    simpleFact("all jars have valid partitions, but the order was wrong"),
                    fact("expected order", expected)
            );
        }

        private static int findFirstMatchingPrefix(String value, ImmutableList<String> prefixes) {
            for (int i = 0; i < prefixes.size(); i++) {
                if (value.startsWith(prefixes.get(i))) {
                    return i;
                }
            }
            return -1;
        }
    }
}
