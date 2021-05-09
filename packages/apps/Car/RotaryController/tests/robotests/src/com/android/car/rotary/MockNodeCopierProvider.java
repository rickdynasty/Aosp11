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

import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

/** A class that can provide a mock {@link NodeCopier}. */
class MockNodeCopierProvider {

    private static final NodeCopier sNodeCopier = new NodeCopier() {
        // NodeCopier#copyNode() doesn't work when passed a mock node, so we create the mock method
        // which returns the passed node itself rather than a copy. As a result, nodes created by
        // the mock method shouldn't be recycled.
        @Override
        AccessibilityNodeInfo copy(@Nullable AccessibilityNodeInfo node) {
            return node;
        }
    };

    static NodeCopier get() {
        return sNodeCopier;
    }
}
