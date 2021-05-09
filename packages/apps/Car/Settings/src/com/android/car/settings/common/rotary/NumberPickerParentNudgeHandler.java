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

package com.android.car.settings.common.rotary;

import android.view.KeyEvent;
import android.view.View;
import android.widget.DatePicker;
import android.widget.NumberPicker;
import android.widget.TimePicker;

/**
 * A nudge handler for the parent container of the {@link NumberPicker} (such as {@link TimePicker}
 * or {@link DatePicker}. Ensures that the first nudge correctly enters into the first child
 * {@link NumberPicker}.
 */
public class NumberPickerParentNudgeHandler implements View.OnKeyListener {

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
        if (!NumberPickerUtils.POSSIBLE_PARENT_TYPES.contains(view.getClass())) {
            return false;
        }

        switch (keyCode) {
            // Any event focuses the child number picker.
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    return NumberPickerUtils.focusChildNumberPicker(view);
                }
                return true;
            default:
                return false;
        }
    }
}
