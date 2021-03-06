# Copyright (C) 2019 The Android Open Source Project
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

LOCAL_PATH := $(call my-dir)

#
# Build rule for WallpaperPicker2 tests
#
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.annotation_annotation \
    androidx.test.core \
    androidx.test.runner \
    androidx.test.rules \
    androidx.test.espresso.contrib \
    androidx.test.espresso.intents \
    mockito-target-minus-junit4 \
    androidx.test.espresso.core \
    hamcrest-library \
    hamcrest

ifneq (,$(wildcard frameworks/base))
    LOCAL_PRIVATE_PLATFORM_APIS := true
else
    LOCAL_SDK_VERSION := 29
    LOCAL_MIN_SDK_VERSION := 26
endif

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_ANDROID_LIBRARIES := WallpaperPicker2CommonDepsLib

LOCAL_FULL_LIBS_MANIFEST_FILES := $(LOCAL_PATH)/AndroidManifest.xml
LOCAL_MANIFEST_FILE := AndroidManifest.xml

LOCAL_INSTRUMENTATION_FOR := WallpaperPicker2

LOCAL_PACKAGE_NAME := WallpaperPicker2Tests
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
