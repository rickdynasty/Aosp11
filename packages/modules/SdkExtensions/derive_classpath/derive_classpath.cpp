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

#include "derive_classpath.h"
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/strings.h>
#include <glob.h>
#include <regex>
#include <sstream>

#include "packages/modules/SdkExtensions/proto/classpaths.pb.h"

namespace android {
namespace derive_classpath {

using Filepaths = std::vector<std::string>;
using Classpaths = std::unordered_map<Classpath, Filepaths>;

static const std::regex kBindMountedApex("^/apex/[^/]+@[0-9]+/");

// Defines the order of individual fragments to be merged:
// 1. Jars in ART module always come first;
// 2. Jars defined as part of /system/etc/classpaths;
// 3. Jars defined in all non-ART apexes that expose /apex/*/etc/classpaths fragments.
//
// Note:
// - Relative order in the individual fragment files is not changed when merging.
// - If a fragment file is matched by multiple globs, the first one is used; i.e. ART module
//   fragment is only parsed once, even if there is a "/apex/*/" pattern later.
// - If there are multiple files matched for a glob pattern with wildcards, the results are sorted
//   by pathname (default glob behaviour); i.e. all fragment files are sorted within a single
//   "pattern block".
static const std::vector<std::string> kClasspathFragmentGlobPatterns = {
    // ART module is a special case and must come first before any other classpath entries.
    "/apex/com.android.art/etc/classpaths/*",
    // TODO(b/180105615): put all non /system jars after /apex jars.
    "/system/etc/classpaths/*",
    "/apex/*/etc/classpaths/*",
};

// Finds all classpath fragment files that match the glob pattern and appends them to `fragments`.
//
// If a newly found fragment is already present in `fragments`, it is skipped to avoid duplicates.
// Note that appended fragment files are sorted by pathnames, which is a default behaviour for
// glob().
bool GlobClasspathFragments(Filepaths* fragments, const std::string& pattern) {
  glob_t glob_result;
  const int ret = glob(pattern.c_str(), GLOB_MARK, nullptr, &glob_result);
  if (ret != 0 && ret != GLOB_NOMATCH) {
    globfree(&glob_result);
    LOG(ERROR) << "Failed to glob " << pattern;
    return false;
  }

  for (size_t i = 0; i < glob_result.gl_pathc; i++) {
    std::string path = glob_result.gl_pathv[i];
    // Skip <name>@<ver> dirs, as they are bind-mounted to <name>
    if (std::regex_search(path, kBindMountedApex)) {
      continue;
    }
    // Make sure we don't push duplicate fragments from previously processed patterns
    if (std::find(fragments->begin(), fragments->end(), path) == fragments->end()) {
      fragments->push_back(path);
    }
  }
  globfree(&glob_result);
  return true;
}

// Writes the contents of *CLASSPATH variables to /data in the format expected by `load_exports`
// action from init.rc. See platform/system/core/init/README.md.
bool WriteClasspathExports(Classpaths classpaths, std::string_view output_path) {
  std::stringstream out;

  out << "export BOOTCLASSPATH " << android::base::Join(classpaths[BOOTCLASSPATH], ':') << '\n';
  out << "export DEX2OATBOOTCLASSPATH "
      << android::base::Join(classpaths[DEX2OATBOOTCLASSPATH], ':') << '\n';
  out << "export SYSTEMSERVERCLASSPATH "
      << android::base::Join(classpaths[SYSTEMSERVERCLASSPATH], ':') << '\n';

  const std::string path_str(output_path);
  return android::base::WriteStringToFile(out.str(), path_str, /*follow_symlinks=*/true);
}

bool ReadClasspathFragment(ExportedClasspathsJars* fragment, const std::string& filepath) {
  std::string contents;
  if (!android::base::ReadFileToString(filepath, &contents)) {
    PLOG(ERROR) << "Failed to read " << filepath;
    return false;
  }
  if (!fragment->ParseFromString(contents)) {
    LOG(ERROR) << "Failed to parse " << filepath;
    return false;
  }
  return true;
}

// Generates /data/system/environ/classpath exports file by globing and merging individual
// classpaths.proto config fragments. The exports file is read by init.rc to setenv *CLASSPATH
// environ variables at runtime.
bool GenerateClasspathExports(std::string_view output_path) {
  // Outside of tests use actual config fragments.
  return GenerateClasspathExports("", output_path);
}

// Internal implementation of GenerateClasspathExports that allows putting config fragments in
// temporary directories. `globPatternPrefix` is appended to each glob pattern from
// kClasspathFragmentGlobPatterns, which allows adding mock configs in /data/local/tmp for example.
bool GenerateClasspathExports(const std::string& globPatternPrefix, std::string_view output_path) {
  Filepaths fragments;
  for (const auto& pattern : kClasspathFragmentGlobPatterns) {
    if (!GlobClasspathFragments(&fragments, globPatternPrefix + pattern)) {
      return false;
    }
  }

  Classpaths classpaths;
  for (const auto& path : fragments) {
    ExportedClasspathsJars exportedJars;
    if (!ReadClasspathFragment(&exportedJars, path)) {
      return false;
    }
    for (const Jar& jar : exportedJars.jars()) {
      // TODO(b/180105615): check for duplicate jars and SdkVersion ranges;
      // TODO(b/180105615): actually make the path relative for apex jars;
      classpaths[jar.classpath()].push_back(jar.relative_path());
    }
  }

  if (!WriteClasspathExports(classpaths, output_path)) {
    PLOG(ERROR) << "Failed to write " << output_path;
    return false;
  }
  return true;
}

}  // namespace derive_classpath
}  // namespace android
