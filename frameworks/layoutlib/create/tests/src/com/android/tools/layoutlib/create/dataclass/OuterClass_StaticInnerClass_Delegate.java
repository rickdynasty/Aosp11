/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tools.layoutlib.create.dataclass;

import com.android.tools.layoutlib.create.dataclass.OuterClass.StaticInnerClass;
import com.android.tools.layoutlib.create.DelegateClassAdapterTest;

/**
 * Used by {@link DelegateClassAdapterTest}.
 */
public class OuterClass_StaticInnerClass_Delegate {
    public static void constructor_after(StaticInnerClass instance) {
        instance.mStaticInnerId = 42;
    }

    // The delegate override of Inner.get return 6 + a + b
    public static int get(StaticInnerClass inner, int a, long b) {
        return 6 + a + (int) b;
    }
}
