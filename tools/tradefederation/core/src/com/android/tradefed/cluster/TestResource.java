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
package com.android.tradefed.cluster;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A class to model a TestResource message returned by TFC API. */
public class TestResource {

    private final String mName;
    private final String mUrl;
    private final boolean mDecompress;
    private final String mDecompressDir;

    TestResource(String name, String url, boolean decompress, String decompressDir) {
        mName = name;
        mUrl = url;
        mDecompress = decompress;
        mDecompressDir = decompressDir != null ? decompressDir : "";
    }

    public String getName() {
        return mName;
    }

    public String getUrl() {
        return mUrl;
    }

    public boolean getDecompress() {
        return mDecompress;
    }

    public String getDecompressDir() {
        return mDecompressDir;
    }

    public File getFile(File parentDir) {
        return new File(parentDir, mName);
    }

    public File getDecompressDir(File parentDir) {
        return new File(parentDir, mDecompressDir);
    }

    public JSONObject toJson() throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("name", mName);
        json.put("url", mUrl);
        json.put("decompress", mDecompress);
        json.put("decompress_dir", mDecompressDir);
        return json;
    }

    public static TestResource fromJson(JSONObject json) {
        return new TestResource(
                json.optString("name"),
                json.optString("url"),
                json.optBoolean("decompress"),
                json.optString("decompress_dir"));
    }

    public static List<TestResource> fromJsonArray(JSONArray jsonArray) throws JSONException {
        final List<TestResource> objs = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            objs.add(TestResource.fromJson(jsonArray.getJSONObject(i)));
        }
        return objs;
    }
}
