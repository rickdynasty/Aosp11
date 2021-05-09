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
package com.android.car.rotary;

import static android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class WindowBuilderTest {

    @Test
    public void testSetId() {
        AccessibilityWindowInfo window = new WindowBuilder().setId(0x42).build();
        assertThat(window.getId()).isEqualTo(0x42);
    }

    @Test
    public void testSetRoot() {
        AccessibilityNodeInfo root = new NodeBuilder(new ArrayList<>()).build();
        AccessibilityWindowInfo window = new WindowBuilder().setRoot(root).build();
        assertThat(window.getRoot()).isSameInstanceAs(root);
    }

    @Test
    public void testSetBoundsInScreen() {
        Rect setBounds = new Rect(100, 200, 300, 400);
        AccessibilityWindowInfo window = new WindowBuilder().setBoundsInScreen(setBounds).build();
        Rect retrievedBounds = new Rect();
        window.getBoundsInScreen(retrievedBounds);
        assertThat(retrievedBounds).isEqualTo(setBounds);
    }

    @Test
    public void testSetType() {
        AccessibilityWindowInfo window =
                new WindowBuilder().setType(TYPE_SYSTEM).build();
        assertThat(window.getType()).isEqualTo(TYPE_SYSTEM);
    }
}
