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

#pragma once

#include <android-base/properties.h>

namespace android {
namespace modules {
namespace sdklevel {

// Return true iff the running Android SDK is at least "R".
static inline bool IsAtLeastR() {
  return android::base::GetIntProperty("ro.build.version.sdk", -1) >= 30;
}

// Returns true iff the running Android SDK is pre-release "S" or "T", built
// based on "R" SDK.
//
// If new SDK versions are added > R, then this method needs to be updated to
// recognise them (e.g. if we add SDK version for R-QPR, the current
// implementation will not recognise pre-release "S" versions built on that).
static inline bool IsAtLeastS() {
  // TODO(b/170831689) This should check SDK_INT >= S once S sdk finalised.
  // Note that removing the current conditions may lead to issues in
  // mainlinefood (and possibly public beta?).
  std::string codename =
    android::base::GetProperty("ro.build.version.codename", "");
  return android::base::GetIntProperty("ro.build.version.sdk", -1) == 30 &&
      (codename == "S" || codename == "T");
}

} // namespace utils
} // namespace modules
} // namespace android
