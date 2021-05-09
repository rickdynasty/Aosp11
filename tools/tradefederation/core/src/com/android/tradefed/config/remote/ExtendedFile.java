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
package com.android.tradefed.config.remote;

import java.io.File;

/** A extension of standard file to carry a build related metadata. */
public class ExtendedFile extends File {

    private String mBuildId;
    private String mBuildTarget;

    ExtendedFile(String path) {
        super(path);
    }

    public ExtendedFile(File file, String buildId, String buildTarget) {
        super(file.getAbsolutePath());
        mBuildId = buildId;
        mBuildTarget = buildTarget;
    }

    /** Returns the buildid metadata. */
    public String getBuildId() {
        return mBuildId;
    }

    /** Returns the target metadata. */
    public String getBuildTarget() {
        return mBuildTarget;
    }
}
