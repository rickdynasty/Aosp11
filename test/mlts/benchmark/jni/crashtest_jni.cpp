/**
 * Copyright 2020 The Android Open Source Project
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

#include <jni.h>
#include <string>
#include <iomanip>
#include <sstream>
#include <fcntl.h>

#include <android/log.h>

#define LOG_TAG "CrashTest"

extern "C" JNIEXPORT void JNICALL
Java_com_android_nn_crashtest_core_test_CrashingCrashTest_nativeSegViolation(
    JNIEnv* env, jobject /* this */) {
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Causing NATIVE crash");

  char* bad_array = nullptr;

  bad_array[10] = 'x';

  __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                      "Looks like it didn't crash!!!");
}
