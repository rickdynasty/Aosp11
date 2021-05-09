/*
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
package com.android.car.rotary;

import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
import static android.provider.Settings.Secure.DEFAULT_INPUT_METHOD;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED;
import static android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED;
import static android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
import static android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_ADDED;
import static android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_REMOVED;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_SELECTION;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD;
import static android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT;
import static android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION;
import static android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD;

import static com.android.car.ui.utils.RotaryConstants.ACTION_HIDE_IME;
import static com.android.car.ui.utils.RotaryConstants.ACTION_NUDGE_SHORTCUT;
import static com.android.car.ui.utils.RotaryConstants.ACTION_NUDGE_TO_ANOTHER_FOCUS_AREA;
import static com.android.car.ui.utils.RotaryConstants.ACTION_RESTORE_DEFAULT_FOCUS;
import static com.android.car.ui.utils.RotaryConstants.NUDGE_DIRECTION;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.car.Car;
import android.car.input.CarInputManager;
import android.car.input.RotaryEvent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.ui.utils.DirectManipulationHelper;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A service that can change focus based on rotary controller rotation and nudges, and perform
 * clicks based on rotary controller center button clicks.
 * <p>
 * As an {@link AccessibilityService}, this service responds to {@link KeyEvent}s (on debug builds
 * only) and {@link AccessibilityEvent}s.
 * <p>
 * On debug builds, {@link KeyEvent}s coming from the keyboard are handled by clicking the view, or
 * moving the focus, sometimes within a window and sometimes between windows.
 * <p>
 * This service listens to two types of {@link AccessibilityEvent}s: {@link
 * AccessibilityEvent#TYPE_VIEW_FOCUSED} and {@link AccessibilityEvent#TYPE_VIEW_CLICKED}. The
 * former is used to keep {@link #mFocusedNode} up to date as the focus changes. The latter is used
 * to detect when the user switches from rotary mode to touch mode and to keep {@link
 * #mLastTouchedNode} up to date.
 * <p>
 * As a {@link CarInputManager.CarInputCaptureCallback}, this service responds to {@link KeyEvent}s
 * and {@link RotaryEvent}s, both of which are coming from the controller.
 * <p>
 * {@link KeyEvent}s are handled by clicking the view, or moving the focus, sometimes within a
 * window and sometimes between windows.
 * <p>
 * {@link RotaryEvent}s are handled by moving the focus within the same {@link
 * com.android.car.ui.FocusArea}.
 * <p>
 * Note: onFoo methods are all called on the main thread so no locks are needed.
 */
public class RotaryService extends AccessibilityService implements
        CarInputManager.CarInputCaptureCallback {

    /**
     * How many detents to rotate when the user holds in shift while pressing C, V, Q, or E on a
     * debug build.
     */
    private static final int SHIFT_DETENTS = 10;

    /**
     * A value to indicate that it isn't one of the nudge directions. (i.e.
     * {@link View#FOCUS_LEFT}, {@link View#FOCUS_UP}, {@link View#FOCUS_RIGHT}, or
     * {@link View#FOCUS_DOWN}).
     */
    private static final int INVALID_NUDGE_DIRECTION = -1;

    private static final String SHARED_PREFS = "com.android.car.rotary.RotaryService";
    private static final String TOUCH_INPUT_METHOD_PREFIX = "TOUCH_INPUT_METHOD_";

    /**
     * A reference to {@link #mWindowContext} or null if one hasn't been created. This is static in
     * order to prevent the creation of multiple window contexts when this service is enabled and
     * disabled repeatedly. Android imposes a limit on the number of window contexts without a
     * corresponding surface.
     */
    @Nullable private static WeakReference<Context> sWindowContext;

    @NonNull
    private NodeCopier mNodeCopier = new NodeCopier();

    private Navigator mNavigator;

    /** Input types to capture. */
    private final int[] mInputTypes = new int[]{
            // Capture controller rotation.
            CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION,
            // Capture controller center button clicks.
            CarInputManager.INPUT_TYPE_DPAD_KEYS,
            // Capture controller nudges.
            CarInputManager.INPUT_TYPE_SYSTEM_NAVIGATE_KEYS};

    /**
     * Time interval in milliseconds to decide whether we should accelerate the rotation by 3 times
     * for a rotate event.
     */
    private int mRotationAcceleration3xMs;

    /**
     * Time interval in milliseconds to decide whether we should accelerate the rotation by 2 times
     * for a rotate event.
     */
    private int mRotationAcceleration2xMs;

    /**
     * The currently focused node, if any. This is typically set when performing {@code
     * ACTION_FOCUS} on a node. However, when performing {@code ACTION_FOCUS} on a {@code
     * FocusArea}, this is set to the {@code FocusArea} until we receive a {@code TYPE_VIEW_FOCUSED}
     * event with the descendant of the {@code FocusArea} that was actually focused. It's null if no
     * nodes are focused or a {@link com.android.car.ui.FocusParkingView} is focused.
     */
    private AccessibilityNodeInfo mFocusedNode = null;

    /**
     * The node being edited by the IME, if any. When focus moves to the IME, if it's moving from an
     * editable node, we leave it focused. This variable is used to keep track of it so that we can
     * return to it when the user nudges out of the IME.
     */
    private AccessibilityNodeInfo mEditNode = null;

    /**
     * The focus area that contains the {@link #mFocusedNode}. It's null if {@link #mFocusedNode} is
     * null.
     */
    private AccessibilityNodeInfo mFocusArea = null;

    /**
     * The last clicked node by touching the screen, if any were clicked since we last navigated.
     */
    private AccessibilityNodeInfo mLastTouchedNode = null;

    /**
     * How many milliseconds to ignore {@link AccessibilityEvent#TYPE_VIEW_CLICKED} events after
     * performing {@link AccessibilityNodeInfo#ACTION_CLICK} or injecting a {@link
     * KeyEvent#KEYCODE_DPAD_CENTER} event.
     */
    private int mIgnoreViewClickedMs;

    /**
     * When not {@code null}, {@link AccessibilityEvent#TYPE_VIEW_CLICKED} events with this node
     * are ignored if they occur within {@link #mIgnoreViewClickedMs} of {@link
     * #mLastViewClickedTime}.
     */
    private AccessibilityNodeInfo mIgnoreViewClickedNode;

    /**
     * The time of the last {@link AccessibilityEvent#TYPE_VIEW_CLICKED} event in {@link
     * SystemClock#uptimeMillis}.
     */
    private long mLastViewClickedTime;

    /** Component name of rotary IME. Empty if none. */
    @Nullable private String mRotaryInputMethod;

    /** Component name of IME used in touch mode. */
    @Nullable private String mTouchInputMethod;

    /** Observer to update {@link #mTouchInputMethod} when the user switches IMEs. */
    private ContentObserver mInputMethodObserver;

    private SharedPreferences mPrefs;
    private UserManager mUserManager;

    /**
     * The direction of the HUN. If there is no focused node, or the focused node is outside the
     * HUN, nudging to this direction will focus on a node inside the HUN.
     */
    @View.FocusRealDirection
    private int mHunNudgeDirection;

    /**
     * The direction to escape the HUN. If the focused node is inside the HUN, nudging to this
     * direction will move focus to a node outside the HUN, while nudging to other directions
     * will do nothing.
     */
    @View.FocusRealDirection
    private int mHunEscapeNudgeDirection;

    /**
     * Possible actions to do after receiving {@link AccessibilityEvent#TYPE_VIEW_SCROLLED}.
     *
     * @see #injectScrollEvent
     */
    private enum AfterScrollAction {
        /** Do nothing. */
        NONE,
        /**
         * Focus the view before the focused view in Tab order in the scrollable container, if any.
         */
        FOCUS_PREVIOUS,
        /**
         * Focus the view after the focused view in Tab order in the scrollable container, if any.
         */
        FOCUS_NEXT,
        /** Focus the first view in the scrollable container, if any. */
        FOCUS_FIRST,
        /** Focus the last view in the scrollable container, if any. */
        FOCUS_LAST,
    }

    private AfterScrollAction mAfterScrollAction = AfterScrollAction.NONE;

    /**
     * How many milliseconds to wait for a {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event after
     * scrolling.
     */
    private int mAfterScrollTimeoutMs;

    /**
     * When to give up on receiving {@link AccessibilityEvent#TYPE_VIEW_SCROLLED}, in
     * {@link SystemClock#uptimeMillis}.
     */
    private long mAfterScrollActionUntil;

    /** Whether we're in rotary mode (vs touch mode). */
    private boolean mInRotaryMode;

    /**
     * Whether we're in direct manipulation mode.
     * <p>
     * If the focused node supports rotate directly, this mode is controlled by us. Otherwise
     * this mode is controlled by the client app, which is responsible for updating the mode by
     * calling {@link DirectManipulationHelper#enableDirectManipulationMode} when needed.
     */
    private boolean mInDirectManipulationMode;

    /** The {@link SystemClock#uptimeMillis} when the last rotary rotation event occurred. */
    private long mLastRotateEventTime;

    /**
     * The repeat count of {@link KeyEvent#KEYCODE_DPAD_CENTER}. Use to prevent processing a center
     * button click when the center button is released after a long press.
     */
    private int mCenterButtonRepeatCount;

    /**
     * A context to use for fetching the {@link WindowManager} and creating the touch overlay or
     * null if one hasn't been created yet.
     */
    @Nullable private Context mWindowContext;

    private static final Map<Integer, Integer> TEST_TO_REAL_KEYCODE_MAP;

    private static final Map<Integer, Integer> DIRECTION_TO_KEYCODE_MAP;

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT);
        map.put(KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT);
        map.put(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
        map.put(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
        map.put(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_DPAD_CENTER);
        map.put(KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_BACK);
        // Legacy map
        map.put(KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT);
        map.put(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT);
        map.put(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
        map.put(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
        map.put(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_DPAD_CENTER);
        map.put(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK);

        TEST_TO_REAL_KEYCODE_MAP = Collections.unmodifiableMap(map);
    }

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(View.FOCUS_UP, KeyEvent.KEYCODE_DPAD_UP);
        map.put(View.FOCUS_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
        map.put(View.FOCUS_LEFT, KeyEvent.KEYCODE_DPAD_LEFT);
        map.put(View.FOCUS_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT);

        DIRECTION_TO_KEYCODE_MAP = Collections.unmodifiableMap(map);
    }

    private Car mCar;
    private CarInputManager mCarInputManager;
    private InputManager mInputManager;

    /** Package name of foreground app. */
    private CharSequence mForegroundApp;

    private WindowManager mWindowManager;

    private final WindowCache mWindowCache = new WindowCache();

    /**
     * The last node which has performed {@link AccessibilityNodeInfo#ACTION_FOCUS} if it hasn't
     * reported a {@link AccessibilityEvent#TYPE_VIEW_FOCUSED} event yet. Null otherwise.
     */
    @Nullable private AccessibilityNodeInfo mPendingFocusedNode;

    private long mAfterFocusTimeoutMs;

    /** Expiration time for {@link #mPendingFocusedNode} in {@link SystemClock#uptimeMillis}. */
    private long mPendingFocusedExpirationTime;

    @Override
    public void onCreate() {
        super.onCreate();
        Resources res = getResources();
        mRotationAcceleration3xMs = res.getInteger(R.integer.rotation_acceleration_3x_ms);
        mRotationAcceleration2xMs = res.getInteger(R.integer.rotation_acceleration_2x_ms);

        int hunMarginHorizontal =
                res.getDimensionPixelSize(R.dimen.notification_headsup_card_margin_horizontal);
        int hunLeft = hunMarginHorizontal;
        WindowManager windowManager = getSystemService(WindowManager.class);
        Rect displayBounds = windowManager.getCurrentWindowMetrics().getBounds();
        int displayWidth = displayBounds.width();
        int displayHeight = displayBounds.height();
        int hunRight = displayWidth - hunMarginHorizontal;
        boolean showHunOnBottom = res.getBoolean(R.bool.config_showHeadsUpNotificationOnBottom);
        mHunNudgeDirection = showHunOnBottom ? View.FOCUS_DOWN : View.FOCUS_UP;
        mHunEscapeNudgeDirection = showHunOnBottom ? View.FOCUS_UP : View.FOCUS_DOWN;

        mIgnoreViewClickedMs = res.getInteger(R.integer.ignore_view_clicked_ms);
        mAfterScrollTimeoutMs = res.getInteger(R.integer.after_scroll_timeout_ms);

        mNavigator = new Navigator(displayWidth, displayHeight, hunLeft, hunRight, showHunOnBottom);

        mPrefs = createDeviceProtectedStorageContext().getSharedPreferences(SHARED_PREFS,
                Context.MODE_PRIVATE);
        mUserManager = getSystemService(UserManager.class);

        // Verify that the component names for default_touch_input_method and rotary_input_method
        // are valid. If mTouchInputMethod or mRotaryInputMethod is empty, IMEs should not switch
        // because RotaryService won't be able to switch them back.
        String defaultTouchInputMethod = res.getString(R.string.default_touch_input_method);
        if (isValidIme(defaultTouchInputMethod)) {
            mTouchInputMethod = mPrefs.getString(
                TOUCH_INPUT_METHOD_PREFIX + mUserManager.getUserName(), defaultTouchInputMethod);
            // Set the DEFAULT_INPUT_METHOD in case Android defaults to the rotary_input_method.
            // TODO(b/169423887): Figure out how to configure the default IME through Android
            // without needing to do this.
            Settings.Secure.putString(
                    getContentResolver(), DEFAULT_INPUT_METHOD, mTouchInputMethod);
        }
        String rotaryInputMethod = res.getString(R.string.rotary_input_method);
        if (isValidIme(rotaryInputMethod)) {
            mRotaryInputMethod = rotaryInputMethod;
        }

        mAfterFocusTimeoutMs = res.getInteger(R.integer.after_focus_timeout_ms);
    }

    /**
     * {@inheritDoc}
     * <p>
     * We need to access WindowManager in onCreate() and
     * IAccessibilityServiceClientWrapper.Callbacks#init(). Since WindowManager is a visual
     * service, only Activity or other visual Context can access it. So we create a window context
     * (a visual context) and delegate getSystemService() to it.
     */
    @Override
    public Object getSystemService(@ServiceName @NonNull String name) {
        // Guarantee that we always return the same WindowManager instance.
        if (WINDOW_SERVICE.equals(name)) {
            if (mWindowManager == null) {
                Context windowContext = getWindowContext();
                mWindowManager = (WindowManager) windowContext.getSystemService(WINDOW_SERVICE);
            }
            return mWindowManager;
        }
        return super.getSystemService(name);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        mCar = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    mCar = car;
                    if (ready) {
                        mCarInputManager =
                                (CarInputManager) mCar.getCarManager(Car.CAR_INPUT_SERVICE);
                        mCarInputManager.requestInputEventCapture(this,
                                CarInputManager.TARGET_DISPLAY_TYPE_MAIN,
                                mInputTypes,
                                CarInputManager.CAPTURE_REQ_FLAGS_ALLOW_DELAYED_GRANT);
                    }
                });

        if (Build.IS_DEBUGGABLE) {
            AccessibilityServiceInfo serviceInfo = getServiceInfo();
            // Filter testing KeyEvents from a keyboard.
            serviceInfo.flags |= FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(serviceInfo);
        }

        mInputManager = getSystemService(InputManager.class);

        // Add an overlay to capture touch events.
        addTouchOverlay();

        // Register an observer to update mTouchInputMethod whenever the user switches IMEs.
        registerInputMethodObserver();
    }

    @Override
    public void onInterrupt() {
        L.v("onInterrupt()");
    }

    @Override
    public void onDestroy() {
        unregisterInputMethodObserver();
        if (mCarInputManager != null) {
            mCarInputManager.releaseInputEventCapture(CarInputManager.TARGET_DISPLAY_TYPE_MAIN);
        }
        if (mCar != null) {
            mCar.disconnect();
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        L.v("onAccessibilityEvent: " + event);
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            L.v("event source: " + source);
        }
        L.v("event window ID: " + Integer.toHexString(event.getWindowId()));

        switch (event.getEventType()) {
            case TYPE_VIEW_FOCUSED: {
                handleViewFocusedEvent(event, source);
                break;
            }
            case TYPE_VIEW_CLICKED: {
                handleViewClickedEvent(event, source);
                break;
            }
            case TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                updateDirectManipulationMode(event, true);
                break;
            }
            case TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                updateDirectManipulationMode(event, false);
                break;
            }
            case TYPE_VIEW_SCROLLED: {
                handleViewScrolledEvent(source);
                break;
            }
            case TYPE_WINDOW_STATE_CHANGED: {
                CharSequence packageName = event.getPackageName();
                onForegroundAppChanged(packageName);
                break;
            }
            case TYPE_WINDOWS_CHANGED: {
                if ((event.getWindowChanges() & WINDOWS_CHANGE_REMOVED) != 0) {
                    handleWindowRemovedEvent(event);
                }
                if ((event.getWindowChanges() & WINDOWS_CHANGE_ADDED) != 0) {
                    handleWindowAddedEvent(event);
                }
                break;
            }
            default:
                // Do nothing.
        }
        Utils.recycleNode(source);
    }

    /**
     * Callback of {@link AccessibilityService}. It allows us to observe testing {@link KeyEvent}s
     * from keyboard, including keys "C" and "V" to emulate controller rotation, keys "J" "L" "I"
     * "K" to emulate controller nudges, and key "Comma" to emulate center button clicks.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (Build.IS_DEBUGGABLE) {
            return handleKeyEvent(event);
        }
        return false;
    }

    /**
     * Callback of {@link CarInputManager.CarInputCaptureCallback}. It allows us to capture {@link
     * KeyEvent}s generated by a navigation controller, such as controller nudge and controller
     * click events.
     */
    @Override
    public void onKeyEvents(int targetDisplayId, List<KeyEvent> events) {
        if (!isValidDisplayId(targetDisplayId)) {
            return;
        }
        for (KeyEvent event : events) {
            handleKeyEvent(event);
        }
    }

    /**
     * Callback of {@link CarInputManager.CarInputCaptureCallback}. It allows us to capture {@link
     * RotaryEvent}s generated by a navigation controller.
     */
    @Override
    public void onRotaryEvents(int targetDisplayId, List<RotaryEvent> events) {
        if (!isValidDisplayId(targetDisplayId)) {
            return;
        }
        for (RotaryEvent rotaryEvent : events) {
            handleRotaryEvent(rotaryEvent);
        }
    }

    @Override
    public void onCaptureStateChanged(int targetDisplayId,
            @android.annotation.NonNull @CarInputManager.InputTypeEnum int[] activeInputTypes) {
        // Do nothing.
    }

    private Context getWindowContext() {
        if (mWindowContext == null && sWindowContext != null) {
            mWindowContext = sWindowContext.get();
            if (mWindowContext != null) {
                L.d("Reusing window context");
            }
        }
        if (mWindowContext == null) {
            // We need to set the display before creating the WindowContext.
            DisplayManager displayManager = getSystemService(DisplayManager.class);
            Display primaryDisplay = displayManager.getDisplay(DEFAULT_DISPLAY);
            updateDisplay(primaryDisplay.getDisplayId());
            L.d("Creating window context");
            mWindowContext = createWindowContext(TYPE_APPLICATION_OVERLAY, null);
            sWindowContext = new WeakReference<>(mWindowContext);
        }
        return mWindowContext;
    }

    /**
     * Adds an overlay to capture touch events. The overlay has zero width and height so
     * it doesn't prevent other windows from receiving touch events. It sets
     * {@link WindowManager.LayoutParams#FLAG_WATCH_OUTSIDE_TOUCH} so it receives
     * {@link MotionEvent#ACTION_OUTSIDE} events for touches anywhere on the screen. This
     * is used to exit rotary mode when the user touches the screen, even if the touch
     * isn't considered a click.
     */
    private void addTouchOverlay() {
        // Only views with a visual context, such as a window context, can be added by
        // WindowManager.
        FrameLayout frameLayout = new FrameLayout(getWindowContext());

        FrameLayout.LayoutParams frameLayoutParams =
                new FrameLayout.LayoutParams(/* width= */ 0, /* height= */ 0);
        frameLayout.setLayoutParams(frameLayoutParams);
        frameLayout.setOnTouchListener((view, event) -> {
            // We're trying to identify real touches from the user's fingers, but using the rotary
            // controller to press keys in the rotary IME also triggers this touch listener, so we
            // ignore these touches.
            if (mIgnoreViewClickedNode == null
                    || event.getEventTime() >= mLastViewClickedTime + mIgnoreViewClickedMs) {
                onTouchEvent();
            }
            return false;
        });
        WindowManager.LayoutParams windowLayoutParams = new WindowManager.LayoutParams(
                /* w= */ 0,
                /* h= */ 0,
                TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSPARENT);
        windowLayoutParams.gravity = Gravity.RIGHT | Gravity.TOP;
        WindowManager windowManager = getSystemService(WindowManager.class);
        windowManager.addView(frameLayout, windowLayoutParams);
    }

    private void onTouchEvent() {
        if (!mInRotaryMode) {
            return;
        }

        // Enter touch mode once the user touches the screen.
        setInRotaryMode(false);

        // Set mFocusedNode to null when user uses touch.
        if (mFocusedNode != null) {
            setFocusedNode(null);
        }
    }

    /**
     * Registers an observer to updates {@link #mTouchInputMethod} whenever the user switches IMEs.
     */
    private void registerInputMethodObserver() {
        if (mInputMethodObserver != null) {
            throw new IllegalStateException("Input method observer already registered");
        }
        ContentResolver contentResolver = getContentResolver();
        mInputMethodObserver = new ContentObserver(new Handler(Looper.myLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                // Either the user switched input methods or we did. In the former case, update
                // mTouchInputMethod and save it so we can switch back after switching to the rotary
                // input method.
                String inputMethod =
                        Settings.Secure.getString(contentResolver, DEFAULT_INPUT_METHOD);
                if (inputMethod != null && !inputMethod.equals(mRotaryInputMethod)) {
                    mTouchInputMethod = inputMethod;
                    String userName = mUserManager.getUserName();
                    mPrefs.edit()
                            .putString(TOUCH_INPUT_METHOD_PREFIX + userName, mTouchInputMethod)
                            .apply();
                }
            }
        };
        contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(DEFAULT_INPUT_METHOD),
                /* notifyForDescendants= */ false,
                mInputMethodObserver);
    }

    /** Unregisters the observer registered by {@link #registerInputMethodObserver}. */
    private void unregisterInputMethodObserver() {
        if (mInputMethodObserver != null) {
            getContentResolver().unregisterContentObserver(mInputMethodObserver);
            mInputMethodObserver = null;
        }
    }

    private static boolean isValidDisplayId(int displayId) {
        if (displayId == CarInputManager.TARGET_DISPLAY_TYPE_MAIN) {
            return true;
        }
        L.e("RotaryService shouldn't capture events from display ID " + displayId);
        return false;
    }

    /**
     * Handles key events. Returns whether the key event was consumed. To avoid invalid event stream
     * getting through to the application, if a key down event is consumed, the corresponding key up
     * event must be consumed too, and vice versa.
     */
    private boolean handleKeyEvent(KeyEvent event) {
        int action = event.getAction();
        boolean isActionDown = action == ACTION_DOWN;
        int keyCode = getKeyCode(event);
        int detents = event.isShiftPressed() ? SHIFT_DETENTS : 1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_Q:
            case KeyEvent.KEYCODE_C:
                if (isActionDown) {
                    handleRotateEvent(/* clockwise= */ false, detents,
                            event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_V:
                if (isActionDown) {
                    handleRotateEvent(/* clockwise= */ true, detents,
                            event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
                handleNudgeEvent(View.FOCUS_LEFT, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT:
                handleNudgeEvent(View.FOCUS_RIGHT, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
                handleNudgeEvent(View.FOCUS_UP, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
                handleNudgeEvent(View.FOCUS_DOWN, action);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (isActionDown) {
                    mCenterButtonRepeatCount = event.getRepeatCount();
                }
                if (mCenterButtonRepeatCount == 0) {
                    handleCenterButtonEvent(action, /* longClick= */ false);
                } else if (mCenterButtonRepeatCount == 1) {
                    handleCenterButtonEvent(action, /* longClick= */ true);
                }
                return true;
            case KeyEvent.KEYCODE_G:
                handleCenterButtonEvent(action, /* longClick= */ true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (mInDirectManipulationMode) {
                    handleBackButtonEvent(action);
                    return true;
                }
                return false;
            default:
                // Do nothing
        }
        return false;
    }

    /** Handles {@link AccessibilityEvent#TYPE_VIEW_FOCUSED} event. */
    private void handleViewFocusedEvent(@NonNull AccessibilityEvent event,
            @Nullable AccessibilityNodeInfo sourceNode) {
        // A view was focused. We ignore focus changes in touch mode. We don't use
        // TYPE_VIEW_FOCUSED to keep mLastTouchedNode up to date because most views can't be
        // focused in touch mode.
        if (!mInRotaryMode) {
            return;
        }
        if (sourceNode == null) {
            L.w("Null source node in " + event);
            return;
        }

        // Update mFocusedNode if we're not waiting for focused event caused by performing an
        // action.
        refreshPendingFocusedNode();
        if (mPendingFocusedNode == null) {
            L.d("Focus event wasn't caused by performing an action");
            // If it's a FocusParkingView, only update mFocusedNode when it's in the same window
            // with mFocusedNode.
            if (Utils.isFocusParkingView(sourceNode)) {
                if (mFocusedNode != null
                        && sourceNode.getWindowId() == mFocusedNode.getWindowId()) {
                    setFocusedNode(null);
                }
                return;
            }
            // If it's not a FocusParkingView, update mFocusedNode.
            setFocusedNode(sourceNode);
            return;
        }

        // If we're waiting for focused event but this isn't the one we're waiting for, ignore this
        // event. This event doesn't matter because focus has moved from sourceNode to
        // mPendingFocusedNode.
        if (!sourceNode.equals(mPendingFocusedNode)) {
            L.d("Ignoring focus event because focus has since moved");
            return;
        }

        // The event we're waiting for has arrived, so reset mPendingFocusedNode.
        L.d("Ignoring focus event caused by performing an action");
        setPendingFocusedNode(null);
    }

    /** Handles {@link AccessibilityEvent#TYPE_VIEW_CLICKED} event. */
    private void handleViewClickedEvent(@NonNull AccessibilityEvent event,
            @Nullable AccessibilityNodeInfo sourceNode) {
        // A view was clicked. If we triggered the click via performAction(ACTION_CLICK) or
        // by injecting KEYCODE_DPAD_CENTER, we ignore it. Otherwise, we assume the user
        // touched the screen. In this case, we update mLastTouchedNode, and clear the focus
        // if the user touched a view in a different window.
        // To decide whether the click was triggered by us, we can compare the source node
        // in the event with mIgnoreViewClickedNode. If they're equal, the click was
        // triggered by us. But there is a corner case. If a dialog shows up after we
        // clicked the view, the window containing the view will be removed. We still
        // receive click event (TYPE_VIEW_CLICKED) but the source node in the event will be
        // null.
        // Note: there is no way to tell whether the window is removed in click event
        // because window remove event (TYPE_WINDOWS_CHANGED with type
        // WINDOWS_CHANGE_REMOVED) comes AFTER click event.
        if (mIgnoreViewClickedNode != null
                && event.getEventTime() < mLastViewClickedTime + mIgnoreViewClickedMs
                && ((sourceNode == null) || mIgnoreViewClickedNode.equals(sourceNode))) {
            setIgnoreViewClickedNode(null);
            return;
        }

        // When a view is clicked causing a new window to show up, the window containing the clicked
        // view will be removed. We still receive TYPE_VIEW_CLICKED event, but the source node can
        // be null. In that case we need to set mFocusedNode to null.
        if (sourceNode == null) {
            if (mFocusedNode != null) {
                setFocusedNode(null);
            }
            return;
        }

        // Update mLastTouchedNode if the clicked view can take focus. If a view can't take focus,
        // performing focus action on it or calling focusSearch() on it will fail.
        if (!sourceNode.equals(mLastTouchedNode) && Utils.canTakeFocus(sourceNode)) {
            setLastTouchedNode(sourceNode);
        }
    }

    /** Handles {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event. */
    private void handleViewScrolledEvent(@Nullable AccessibilityNodeInfo sourceNode) {
        if (mAfterScrollAction == AfterScrollAction.NONE
                || SystemClock.uptimeMillis() >= mAfterScrollActionUntil) {
            return;
        }
        if (sourceNode == null || !Utils.isScrollableContainer(sourceNode)) {
            return;
        }
        switch (mAfterScrollAction) {
            case FOCUS_PREVIOUS:
            case FOCUS_NEXT: {
                if (mFocusedNode.equals(sourceNode)) {
                    break;
                }
                AccessibilityNodeInfo target = mNavigator.findFocusableDescendantInDirection(
                        sourceNode, mFocusedNode,
                        mAfterScrollAction == AfterScrollAction.FOCUS_PREVIOUS
                                ? View.FOCUS_BACKWARD
                                : View.FOCUS_FORWARD);
                if (target == null) {
                    break;
                }
                L.d("Focusing "
                        + (mAfterScrollAction == AfterScrollAction.FOCUS_PREVIOUS
                            ? "previous" : "next")
                        + " after scroll");
                if (performFocusAction(target)) {
                    mAfterScrollAction = AfterScrollAction.NONE;
                }
                Utils.recycleNode(target);
                break;
            }
            case FOCUS_FIRST:
            case FOCUS_LAST: {
                AccessibilityNodeInfo target =
                        mAfterScrollAction == AfterScrollAction.FOCUS_FIRST
                                ? mNavigator.findFirstFocusableDescendant(sourceNode)
                                : mNavigator.findLastFocusableDescendant(sourceNode);
                if (target == null) {
                    break;
                }
                L.d("Focusing "
                        + (mAfterScrollAction == AfterScrollAction.FOCUS_FIRST ? "first" : "last")
                        + " after scroll");
                if (performFocusAction(target)) {
                    mAfterScrollAction = AfterScrollAction.NONE;
                }
                Utils.recycleNode(target);
                break;
            }
            default:
                throw new IllegalStateException(
                        "Unknown after scroll action: " + mAfterScrollAction);
        }
    }

    /**
     * Handles a {@link AccessibilityEvent#TYPE_WINDOWS_CHANGED} event indicating that a window was
     * removed. Attempts to restore the most recent focus when the window containing
     * {@link #mFocusedNode} is not an application window and it's removed.
     */
    private void handleWindowRemovedEvent(@NonNull AccessibilityEvent event) {
        int windowId = event.getWindowId();
        // Get the window type. The window was removed, so we can only get it from the cache.
        Integer type = mWindowCache.getWindowType(windowId);
        if (type != null) {
            mWindowCache.remove(windowId);
            // No longer need to keep track of the node being edited if the IME window was closed.
            if (type.intValue() == TYPE_INPUT_METHOD) {
                setEditNode(null);
            }
            // No need to restore the focus if it's an application window. When an application
            // window is removed, another window will gain focus shortly and the FocusParkingView
            // in that window will restore the focus.
            if (type.intValue() == TYPE_APPLICATION) {
                return;
            }
        } else {
            L.w("No window type found in cache for window ID: " + windowId);
        }

        // Nothing more to do if we're in touch mode.
        if (!mInRotaryMode) {
            return;
        }

        // We only care about this event when the window that was removed contains the focused node.
        // Ignore other events.
        if (mFocusedNode == null || mFocusedNode.getWindowId() != windowId) {
            return;
        }

        // Restore focus to the last focused node in the last focused window.
        AccessibilityNodeInfo recentFocus = mWindowCache.getMostRecentFocusedNode();
        if (recentFocus != null) {
            performFocusAction(recentFocus);
            recentFocus.recycle();
        }
    }

    /**
     * Handles a {@link AccessibilityEvent#TYPE_WINDOWS_CHANGED} event indicating that a window was
     * added. Moves focus to the IME window when it appears.
     */
    private void handleWindowAddedEvent(@NonNull AccessibilityEvent event) {
        // Save the window type by window ID.
        int windowId = event.getWindowId();
        List<AccessibilityWindowInfo> windows = getWindows();
        AccessibilityWindowInfo window = Utils.findWindowWithId(windows, windowId);
        if (window == null) {
            Utils.recycleWindows(windows);
            return;
        }
        mWindowCache.saveWindowType(windowId, window.getType());

        // Nothing more to do if we're in touch mode.
        if (!mInRotaryMode) {
            Utils.recycleWindows(windows);
            return;
        }

        // We only care about this event when the window that was added doesn't contains the focused
        // node. Ignore other events.
        if (mFocusedNode != null && mFocusedNode.getWindowId() == windowId) {
            Utils.recycleWindows(windows);
            return;
        }

        // No need to move focus for non-IME window here, because in most cases Android will focus
        // the FocusParkingView in the added window, and we'll move focus when handling it.
        if (window.getType() != TYPE_INPUT_METHOD) {
            Utils.recycleWindows(windows);
            return;
        }

        // If the new window is an IME, move focus to the IME.
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) {
            L.w("No root node in " + window);
            Utils.recycleWindows(windows);
            return;
        }
        Utils.recycleWindows(windows);

        // If the focused node is editable, save it so that we can return to it when the user
        // nudges out of the IME.
        if (mFocusedNode != null && mFocusedNode.isEditable()) {
            setEditNode(mFocusedNode);
        }

        boolean success = restoreDefaultFocus(root);
        if (!success) {
            L.d("Failed to restore default focus in " + root);
        }
        root.recycle();
    }

    private boolean restoreDefaultFocus(@NonNull AccessibilityNodeInfo node) {
        AccessibilityNodeInfo fpv = mNavigator.findFocusParkingView(node);

        // Refresh the node to ensure the focused state is up to date. The node came directly from
        // the node tree but it could have been cached by the accessibility framework.
        fpv = Utils.refreshNode(fpv);

        if (fpv == null) {
            L.e("No FocusParkingView in the window containing " + node);
        } else if (Utils.isCarUiFocusParkingView(fpv)
                    && fpv.performAction(ACTION_RESTORE_DEFAULT_FOCUS)) {
            fpv.recycle();
            findFocusedNode(node);
            return true;
        }
        Utils.recycleNode(fpv);

        AccessibilityWindowInfo window = node.getWindow();
        if (window == null) {
            L.e("No window found for the generic FocusParkingView");
            return false;
        }
        AccessibilityNodeInfo root = window.getRoot();
        window.recycle();
        if (root == null) {
            L.w("No root node in " + window);
            return false;
        }
        AccessibilityNodeInfo firstFocusable = mNavigator.findFirstFocusableDescendant(root);
        root.recycle();
        if (firstFocusable == null) {
            L.e("No focusable element in the window containing the generic FocusParkingView");
            return false;
        }
        boolean success = performFocusAction(firstFocusable);
        firstFocusable.recycle();
        return success;
    }

    private static int getKeyCode(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (Build.IS_DEBUGGABLE) {
            Integer mappingKeyCode = TEST_TO_REAL_KEYCODE_MAP.get(keyCode);
            if (mappingKeyCode != null) {
                keyCode = mappingKeyCode;
            }
        }
        return keyCode;
    }

    /** Handles controller center button event. */
    private void handleCenterButtonEvent(int action, boolean longClick) {
        if (!isValidAction(action)) {
            return;
        }
        if (initFocus()) {
            return;
        }
        // Case 1: the focused node supports rotate directly. We should ignore ACTION_DOWN event,
        // and enter direct manipulation mode on ACTION_UP event.
        if (DirectManipulationHelper.supportRotateDirectly(mFocusedNode)) {
            if (action == ACTION_DOWN) {
                return;
            }
            if (!mInDirectManipulationMode) {
                mInDirectManipulationMode = true;
                boolean result = mFocusedNode.performAction(ACTION_SELECT);
                if (!result) {
                    L.w("Failed to perform ACTION_SELECT on " + mFocusedNode);
                }
                L.d("Enter direct manipulation mode because focused node is clicked.");
            }
            return;
        }

        // Case 2: the focused node doesn't support rotate directly and it's in application window.
        // We should inject KEYCODE_DPAD_CENTER event (or KEYCODE_ENTER in a WebView), then the
        // application will handle the injected event.
        if (isInApplicationWindow(mFocusedNode)) {
            int keyCode = mNavigator.isInWebView(mFocusedNode)
                    ? KeyEvent.KEYCODE_ENTER
                    : KeyEvent.KEYCODE_DPAD_CENTER;
            injectKeyEvent(keyCode, action);
            setIgnoreViewClickedNode(mFocusedNode);
            return;
        }

        // Case 3: the focus node doesn't support rotate directly and it's not in application window
        // (e.g., in system window). We should ignore ACTION_DOWN event, and click or long click
        // the focused node on ACTION_UP event.
        if (action == ACTION_DOWN) {
            return;
        }
        boolean result = mFocusedNode.performAction(longClick ? ACTION_LONG_CLICK : ACTION_CLICK);
        if (!result) {
            L.w("Failed to perform " + (longClick ? "ACTION_LONG_CLICK" : "ACTION_CLICK")
                    + " on " + mFocusedNode);
        }
        if (!longClick) {
            setIgnoreViewClickedNode(mFocusedNode);
        }
    }

    private void handleNudgeEvent(int direction, int action) {
        if (!isValidAction(action)) {
            return;
        }

        // If the focused node is in direct manipulation mode, manipulate it directly.
        if (mInDirectManipulationMode) {
            if (DirectManipulationHelper.supportRotateDirectly(mFocusedNode)) {
                L.d("Ignore nudge events because we're in DM mode and the focused node only "
                        + "supports rotate directly");
            } else {
                injectKeyEventForDirection(direction, action);
            }
            return;
        }

        // We're done with ACTION_UP event.
        if (action == ACTION_UP) {
            return;
        }

        List<AccessibilityWindowInfo> windows = getWindows();

        // Don't call initFocus() when handling ACTION_UP nudge events as this event will typically
        // arrive before the TYPE_VIEW_FOCUSED event when we delegate focusing to a FocusArea, and
        // will cause us to focus a nearby view when we discover that mFocusedNode is no longer
        // focused.
        if (initFocus(windows, direction)) {
            Utils.recycleWindows(windows);
            return;
        }

        // If the HUN is currently focused, we should only handle nudge events that are in the
        // opposite direction of the HUN nudge direction.
        if (mNavigator.isHunWindow(mFocusedNode.getWindow())
                && direction != mHunEscapeNudgeDirection) {
            Utils.recycleWindows(windows);
            return;
        }

        // If the focused node is not in direct manipulation mode, try to move the focus to another
        // node.
        boolean success = nudgeTo(windows, direction);
        Utils.recycleWindows(windows);

        // If the user is nudging out of the IME to the node being edited, we no longer need
        // to keep track of the node being edited.
        if (success) {
            mEditNode = Utils.refreshNode(mEditNode);
            if (mEditNode != null && mEditNode.isFocused()) {
                setEditNode(null);
            }
        }
    }

    private boolean nudgeTo(@NonNull List<AccessibilityWindowInfo> windows, int direction) {
        // If the HUN is in the nudge direction, nudge to it.
        boolean hunFocusResult = focusHunsWindow(windows, direction);
        if (hunFocusResult) {
            L.d("Nudge to HUN successful");
            return true;
        }

        // Try to move the focus to the shortcut node.
        if (mFocusArea == null) {
            L.e("mFocusArea shouldn't be null");
            return false;
        }
        Bundle arguments = new Bundle();
        arguments.putInt(NUDGE_DIRECTION, direction);
        if (mFocusArea.performAction(ACTION_NUDGE_SHORTCUT, arguments)) {
            L.d("Nudge to shortcut view");
            AccessibilityNodeInfo root = Utils.getRoot(mFocusArea);
            if (root != null) {
                findFocusedNode(root);
                root.recycle();
            }
            return true;
        }

        // No shortcut node, so move the focus in the given direction.
        // First, try to perform ACTION_NUDGE on mFocusArea to nudge to another FocusArea.
        arguments.clear();
        arguments.putInt(NUDGE_DIRECTION, direction);
        if (mFocusArea.performAction(ACTION_NUDGE_TO_ANOTHER_FOCUS_AREA, arguments)) {
            L.d("Nudge to user specified FocusArea");
            AccessibilityNodeInfo root = Utils.getRoot(mFocusArea);
            if (root != null) {
                findFocusedNode(root);
                root.recycle();
            }
            return true;
        }

        // No specified FocusArea or cached FocusArea in the direction, so mFocusArea doesn't know
        // what FocusArea to nudge to. In this case, we'll find a target FocusArea using geometry.
        AccessibilityNodeInfo targetFocusArea =
                mNavigator.findNudgeTargetFocusArea(windows, mFocusedNode, mFocusArea, direction);
        if (targetFocusArea == null) {
            L.d("Failed to find a target FocusArea for the nudge");
            return false;
        }
        if (Utils.isFocusArea(targetFocusArea)) {
            arguments.clear();
            arguments.putInt(NUDGE_DIRECTION, direction);
            boolean success = performFocusAction(targetFocusArea, arguments);
            L.d("Nudging to the nearest FocusArea "
                    + (success ? "succeeded" : "failed: " + targetFocusArea));
            targetFocusArea.recycle();
            return success;
        }

        // targetFocusArea is an implicit FocusArea (i.e., the root node of a window without any
        // FocusAreas), so restore the focus in it.
        boolean success = restoreDefaultFocus(targetFocusArea);
        L.d("Nudging to the nearest implicit focus area "
                + (success ? "succeeded" : "failed: " + targetFocusArea));
        targetFocusArea.recycle();
        return success;
    }

    private void handleRotaryEvent(RotaryEvent rotaryEvent) {
        if (rotaryEvent.getInputType() != CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION) {
            return;
        }
        boolean clockwise = rotaryEvent.isClockwise();
        int count = rotaryEvent.getNumberOfClicks();
        // TODO(b/153195148): Use the first eventTime for now. We'll need to improve it later.
        long eventTime = rotaryEvent.getUptimeMillisForClick(0);
        handleRotateEvent(clockwise, count, eventTime);
    }

    private void handleRotateEvent(boolean clockwise, int count, long eventTime) {
        if (initFocus()) {
            return;
        }

        int rotationCount = getRotateAcceleration(count, eventTime);

        // If the focused node is in direct manipulation mode, manipulate it directly.
        if (mInDirectManipulationMode) {
            if (DirectManipulationHelper.supportRotateDirectly(mFocusedNode)) {
                performScrollAction(mFocusedNode, clockwise);
            } else {
                AccessibilityWindowInfo window = mFocusedNode.getWindow();
                if (window == null) {
                    L.w("Failed to get window of " + mFocusedNode);
                    return;
                }
                int displayId = window.getDisplayId();
                window.recycle();
                // TODO(b/155823126): Add config to let OEMs determine the mapping.
                injectMotionEvent(displayId, MotionEvent.AXIS_SCROLL,
                        clockwise ? rotationCount : -rotationCount);
            }
            return;
        }

        // If the focused node is not in direct manipulation mode, move the focus.
        int remainingRotationCount = rotationCount;
        int direction = clockwise ? View.FOCUS_FORWARD : View.FOCUS_BACKWARD;
        Navigator.FindRotateTargetResult result =
                mNavigator.findRotateTarget(mFocusedNode, direction, rotationCount);
        if (result != null) {
            if (performFocusAction(result.node)) {
                remainingRotationCount -= result.advancedCount;
            }
            Utils.recycleNode(result.node);
        } else {
            L.w("Failed to find rotate target from " + mFocusedNode);
        }

        // If navigation didn't consume all of rotationCount and the focused node either is a
        // scrollable container or is a descendant of one, scroll it. The former happens when no
        // focusable views are visible in the scrollable container. The latter happens when there
        // are focusable views but they're in the wrong direction. Inject a MotionEvent rather than
        // performing an action so that the application can control the amount it scrolls. Scrolling
        // is only supported in the application window because injected events always go to the
        // application window. We don't bother checking whether the scrollable container can
        // currently scroll because there's nothing else to do if it can't.
        if (remainingRotationCount > 0 && isInApplicationWindow(mFocusedNode)) {
            AccessibilityNodeInfo scrollableContainer =
                    mNavigator.findScrollableContainer(mFocusedNode);
            if (scrollableContainer != null) {
                injectScrollEvent(scrollableContainer, clockwise, remainingRotationCount);
                scrollableContainer.recycle();
            }
        }
    }

    /** Handles Back button event. */
    private void handleBackButtonEvent(int action) {
        if (!isValidAction(action)) {
            return;
        }
        // If the focused node doesn't support rotate directly, inject Back button event, then the
        // application will handle the injected event.
        if (!DirectManipulationHelper.supportRotateDirectly(mFocusedNode)) {
            injectKeyEvent(KeyEvent.KEYCODE_BACK, action);
            return;
        }

        // Otherwise exit direct manipulation mode on ACTION_UP event.
        if (action == ACTION_DOWN) {
            return;
        }
        L.d("Exit direct manipulation mode on back button event");
        mInDirectManipulationMode = false;
        boolean result = mFocusedNode.performAction(ACTION_CLEAR_SELECTION);
        if (!result) {
            L.w("Failed to perform ACTION_CLEAR_SELECTION on " + mFocusedNode);
        }
    }

    private void onForegroundAppChanged(CharSequence packageName) {
        if (TextUtils.equals(mForegroundApp, packageName)) {
            return;
        }
        mForegroundApp = packageName;
        if (mInDirectManipulationMode) {
            L.d("Exit direct manipulation mode because the foreground app has changed");
            mInDirectManipulationMode = false;
        }
    }

    private static boolean isValidAction(int action) {
        if (action != ACTION_DOWN && action != ACTION_UP) {
            L.w("Invalid action " + action);
            return false;
        }
        return true;
    }

    /** Performs scroll action on the given {@code targetNode} if it supports scroll action. */
    private static void performScrollAction(@NonNull AccessibilityNodeInfo targetNode,
            boolean clockwise) {
        // TODO(b/155823126): Add config to let OEMs determine the mapping.
        AccessibilityNodeInfo.AccessibilityAction actionToPerform =
                clockwise ? ACTION_SCROLL_FORWARD : ACTION_SCROLL_BACKWARD;
        if (!targetNode.getActionList().contains(actionToPerform)) {
            L.w("Node " + targetNode + " doesn't support action " + actionToPerform);
            return;
        }
        boolean result = targetNode.performAction(actionToPerform.getId());
        if (!result) {
            L.w("Failed to perform action " + actionToPerform + " on " + targetNode);
        }
    }

    /** Returns whether the given {@code node} is in the application window. */
    private static boolean isInApplicationWindow(@NonNull AccessibilityNodeInfo node) {
        AccessibilityWindowInfo window = node.getWindow();
        if (window == null) {
            L.w("Failed to get window of " + node);
            return false;
        }
        boolean result = window.getType() == TYPE_APPLICATION;
        Utils.recycleWindow(window);
        return result;
    }

    private void updateDirectManipulationMode(@NonNull AccessibilityEvent event, boolean enable) {
        if (!mInRotaryMode || !DirectManipulationHelper.isDirectManipulation(event)) {
            return;
        }
        if (enable) {
            mFocusedNode = Utils.refreshNode(mFocusedNode);
            if (mFocusedNode == null) {
                L.w("Failed to enter direct manipulation mode because mFocusedNode is no longer "
                        + "in view tree.");
                return;
            }
            if (!mFocusedNode.isFocused()) {
                L.w("Failed to enter direct manipulation mode because mFocusedNode is no longer "
                        + "focused.");
                return;
            }
        }
        if (mInDirectManipulationMode != enable) {
            // Toggle direct manipulation mode upon app's request.
            mInDirectManipulationMode = enable;
            L.d((enable ? "Enter" : "Exit") + " direct manipulation mode upon app's request");
        }
    }

    /**
     * Injects a {@link MotionEvent} to scroll {@code scrollableContainer} by {@code rotationCount}
     * steps. The direction depends on the value of {@code clockwise}. Sets
     * {@link #mAfterScrollAction} to move the focus once the scroll occurs, as follows:<ul>
     *     <li>If the user is spinning the rotary controller quickly, focuses the first or last
     *         focusable descendant so that the next rotation event will scroll immediately.
     *     <li>If the user is spinning slowly and there are no focusable descendants visible,
     *         focuses the first focusable descendant to scroll into view. This will be the last
     *         focusable descendant when scrolling up.
     *     <li>If the user is spinning slowly and there are focusable descendants visible, focuses
     *         the next or previous focusable descendant.
     * </ul>
     */
    private void injectScrollEvent(@NonNull AccessibilityNodeInfo scrollableContainer,
            boolean clockwise, int rotationCount) {
        // TODO(b/155823126): Add config to let OEMs determine the mappings.
        if (rotationCount > 1) {
            // Focus last when quickly scrolling down so the next event scrolls.
            mAfterScrollAction = clockwise
                    ? AfterScrollAction.FOCUS_LAST
                    : AfterScrollAction.FOCUS_FIRST;
        } else {
            if (Utils.isScrollableContainer(mFocusedNode)) {
                // Focus first when scrolling down while no focusable descendants are visible.
                mAfterScrollAction = clockwise
                        ? AfterScrollAction.FOCUS_FIRST
                        : AfterScrollAction.FOCUS_LAST;
            } else {
                // Focus next when scrolling down with a focused descendant.
                mAfterScrollAction = clockwise
                        ? AfterScrollAction.FOCUS_NEXT
                        : AfterScrollAction.FOCUS_PREVIOUS;
            }
        }
        mAfterScrollActionUntil = SystemClock.uptimeMillis() + mAfterScrollTimeoutMs;
        int axis = Utils.isHorizontallyScrollableContainer(scrollableContainer)
                ? MotionEvent.AXIS_HSCROLL
                : MotionEvent.AXIS_VSCROLL;
        AccessibilityWindowInfo window = scrollableContainer.getWindow();
        if (window == null) {
            L.w("Failed to get window of " + scrollableContainer);
            return;
        }
        int displayId = window.getDisplayId();
        window.recycle();
        injectMotionEvent(displayId, axis, clockwise ? -rotationCount : rotationCount);
    }

    private void injectMotionEvent(int displayId, int axis, int axisValue) {
        long upTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0; // Any integer value but -1 (INVALID_POINTER_ID) is fine.
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        // No need to set X,Y coordinates. We use a non-pointer source so the event will be routed
        // to the focused view.
        coords[0].setAxisValue(axis, axisValue);
        MotionEvent motionEvent = MotionEvent.obtain(/* downTime= */ upTime,
                /* eventTime= */ upTime,
                MotionEvent.ACTION_SCROLL,
                /* pointerCount= */ 1,
                properties,
                coords,
                /* metaState= */ 0,
                /* buttonState= */ 0,
                /* xPrecision= */ 1.0f,
                /* yPrecision= */ 1.0f,
                /* deviceId= */ 0,
                /* edgeFlags= */ 0,
                InputDevice.SOURCE_ROTARY_ENCODER,
                displayId,
                /* flags= */ 0);

        if (motionEvent != null) {
            mInputManager.injectInputEvent(motionEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } else {
            L.w("Unable to obtain MotionEvent");
        }
    }

    private boolean injectKeyEventForDirection(int direction, int action) {
        Integer keyCode = DIRECTION_TO_KEYCODE_MAP.get(direction);
        if (keyCode == null) {
            throw new IllegalArgumentException("direction must be one of "
                    + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
        }
        return injectKeyEvent(keyCode, action);
    }

    private boolean injectKeyEvent(int keyCode, int action) {
        long upTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(
                /* downTime= */ upTime, /* eventTime= */ upTime, action, keyCode, /* repeat= */ 0);
        return mInputManager.injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    /**
     * Updates saved nodes in case the {@link View}s represented by them are no longer in the view
     * tree.
     */
    private void refreshSavedNodes() {
        mFocusedNode = Utils.refreshNode(mFocusedNode);
        mEditNode = Utils.refreshNode(mEditNode);
        mLastTouchedNode = Utils.refreshNode(mLastTouchedNode);
        mFocusArea = Utils.refreshNode(mFocusArea);
        mIgnoreViewClickedNode = Utils.refreshNode(mIgnoreViewClickedNode);
    }

    /**
     * This method should be called when receiving an event from a rotary controller. It does the
     * following:<ol>
     *     <li>If {@link #mFocusedNode} isn't null and represents a view that still exists, does
     *         nothing. The event isn't consumed in this case. This is the normal case.
     *     <li>If there is a non-FocusParkingView focused in any window, set mFocusedNode to that
     *         view.
     *     <li>If {@link #mLastTouchedNode} isn't null and represents a view that still exists,
     *         focuses it. The event is consumed in this case. This happens when the user switches
     *         from touch to rotary.
     *     <li>Otherwise focuses the best target in the node tree and consumes the event.
     * </ol>
     *
     * @return whether the event was consumed by this method. When {@code false},
     *         {@link #mFocusedNode} is guaranteed to not be {@code null}.
     */
    private boolean initFocus() {
        List<AccessibilityWindowInfo> windows = getWindows();
        boolean consumed = initFocus(windows, INVALID_NUDGE_DIRECTION);
        Utils.recycleWindows(windows);
        return consumed;
    }

    /**
     * Similar to above, but also checks for heads-up notifications if given a valid nudge direction
     * which may be relevant when we're trying to focus the HUNs when coming from touch mode.
     *
     * @param windows the windows currently available to the Accessibility Service
     * @param direction the direction of the nudge that was received (can be
     *                  {@link #INVALID_NUDGE_DIRECTION})
     * @return whether the event was consumed by this method. When {@code false},
     *         {@link #mFocusedNode} is guaranteed to not be {@code null}.
     */
    private boolean initFocus(@NonNull List<AccessibilityWindowInfo> windows, int direction) {
        boolean prevInRotaryMode = mInRotaryMode;
        refreshSavedNodes();
        setInRotaryMode(true);
        if (mFocusedNode != null) {
            // If mFocusedNode is focused, we're in a good state and can proceed with whatever
            // action the user requested.
            if (mFocusedNode.isFocused()) {
                return false;
            }
            // If the focused node represents an HTML element in a WebView, we just assume the focus
            // is already initialized here, and we'll handle it properly when the user uses the
            // controller next time.
            if (mNavigator.isInWebView(mFocusedNode)) {
                return false;
            }
        }

        // If we were not in rotary mode before and we can focus the HUNs window for the given
        // nudge, focus the window and ensure that there is no previously touched node.
        if (!prevInRotaryMode && windows != null && focusHunsWindow(windows, direction)) {
            setLastTouchedNode(null);
            return true;
        }

        // If there is a non-FocusParkingView focused in any window, set mFocusedNode to that view.
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                AccessibilityNodeInfo focusedNode = root.findFocus(FOCUS_INPUT);
                root.recycle();
                if (focusedNode != null && !Utils.isFocusParkingView(focusedNode)) {
                    setFocusedNode(focusedNode);
                    focusedNode.recycle();
                    return false;
                }
            }
        }

        if (mLastTouchedNode != null && focusLastTouchedNode()) {
            return true;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            restoreDefaultFocus(root);
            Utils.recycleNode(root);
        }
        return true;
    }

    /**
     * Clears the current rotary focus if {@code targetFocus} is null, or in a different window
     * unless focus is moving from an editable field to the IME.
     * <p>
     * Note: only {@link #setFocusedNode} can call this method, otherwise {@link #mFocusedNode}
     * might go out of sync.
     */
    private void maybeClearFocusInCurrentWindow(@Nullable AccessibilityNodeInfo targetFocus) {
        mFocusedNode = Utils.refreshNode(mFocusedNode);
        if (mFocusedNode == null || !mFocusedNode.isFocused()
                || (targetFocus != null
                        && mFocusedNode.getWindowId() == targetFocus.getWindowId())) {
            return;
        }

        // If we're moving from an editable node to the IME, don't clear focus, but save the
        // editable node so that we can return to it when the user nudges out of the IME.
        if (mFocusedNode.isEditable() && targetFocus != null) {
            int targetWindowId = targetFocus.getWindowId();
            Integer windowType = mWindowCache.getWindowType(targetWindowId);
            if (windowType != null && windowType == TYPE_INPUT_METHOD) {
                L.d("Leaving editable field focused");
                setEditNode(mFocusedNode);
                return;
            }
        }

        clearFocusInCurrentWindow();
    }

    /**
     * Clears the current rotary focus.
     * <p>
     * If we really clear focus in the current window, Android will re-focus a view in the current
     * window automatically, resulting in the current window and the target window being focused
     * simultaneously. To avoid that we don't really clear the focus. Instead, we "park" the focus
     * on a FocusParkingView in the current window. FocusParkingView is transparent no matter
     * whether it's focused or not, so it's invisible to the user.
     *
     * @return whether the FocusParkingView was focused successfully
     */
    private boolean clearFocusInCurrentWindow() {
        if (mFocusedNode == null) {
            L.e("Don't call clearFocusInCurrentWindow() when mFocusedNode is null");
            return false;
        }
        AccessibilityNodeInfo fpv = mNavigator.findFocusParkingView(mFocusedNode);

        // Refresh the node to ensure the focused state is up to date. The node came directly from
        // the node tree but it could have been cached by the accessibility framework.
        fpv = Utils.refreshNode(fpv);

        if (fpv == null) {
            L.e("No FocusParkingView in the window that contains " + mFocusedNode);
            return false;
        }
        if (fpv.isFocused()) {
            L.d("FocusParkingView is already focused " + fpv);
            return true;
        }
        boolean result = performFocusAction(fpv);
        if (!result) {
            L.w("Failed to perform ACTION_FOCUS on " + fpv);
        }
        fpv.recycle();
        return result;
    }

    private boolean focusHunsWindow(@NonNull List<AccessibilityWindowInfo> windows, int direction) {
        if (direction != mHunNudgeDirection) {
            return false;
        }

        AccessibilityWindowInfo hunWindow = mNavigator.findHunWindow(windows);
        if (hunWindow == null) {
            L.d("No HUN window to focus");
            return false;
        }

        AccessibilityNodeInfo hunRoot = hunWindow.getRoot();
        if (hunRoot == null) {
            L.d("No root in HUN Window to focus");
            return false;
        }

        boolean success = restoreDefaultFocus(hunRoot);
        hunRoot.recycle();
        L.d("HUN window focus " + (success ? "successful" : "failed"));
        return success;
    }

    /**
     * Focuses the last touched node, if any.
     *
     * @return {@code true} if {@link #mLastTouchedNode} isn't {@code null} and it was
     *         successfully focused
     */
    private boolean focusLastTouchedNode() {
        boolean lastTouchedNodeFocused = false;
        if (mLastTouchedNode != null) {
            lastTouchedNodeFocused = performFocusAction(mLastTouchedNode);
            if (mLastTouchedNode != null) {
                setLastTouchedNode(null);
            }
        }
        return lastTouchedNodeFocused;
    }

    /**
     * Sets {@link #mFocusedNode} to a copy of the given node, and clears {@link #mLastTouchedNode}.
     */
    private void setFocusedNode(@Nullable AccessibilityNodeInfo focusedNode) {
        // Android doesn't clear focus automatically when focus is set in another window, so we need
        // to do it explicitly.
        maybeClearFocusInCurrentWindow(focusedNode);

        setFocusedNodeInternal(focusedNode);
        if (mFocusedNode != null && mLastTouchedNode != null) {
            setLastTouchedNodeInternal(null);
        }
    }

    private void setFocusedNodeInternal(@Nullable AccessibilityNodeInfo focusedNode) {
        if ((mFocusedNode == null && focusedNode == null) ||
                (mFocusedNode != null && mFocusedNode.equals(focusedNode))) {
            L.d("Don't reset mFocusedNode since it stays the same: " + mFocusedNode);
            return;
        }
        if (mInDirectManipulationMode && focusedNode == null) {
            // Toggle off direct manipulation mode since there is no focused node.
            mInDirectManipulationMode = false;
            L.d("Exit direct manipulation mode since there is no focused node");
        }

        // Close the IME when navigating from an editable view to a non-editable view.
        maybeCloseIme(focusedNode);

        Utils.recycleNode(mFocusedNode);
        mFocusedNode = copyNode(focusedNode);
        L.d("mFocusedNode set to: " + mFocusedNode);

        Utils.recycleNode(mFocusArea);
        mFocusArea = mFocusedNode == null ? null : mNavigator.getAncestorFocusArea(mFocusedNode);

        if (mFocusedNode != null) {
            mWindowCache.saveFocusedNode(mFocusedNode.getWindowId(), mFocusedNode);
        }
    }

    private void refreshPendingFocusedNode() {
        if (mPendingFocusedNode != null) {
            if (SystemClock.uptimeMillis() > mPendingFocusedExpirationTime) {
                setPendingFocusedNode(null);
            } else {
                mPendingFocusedNode = Utils.refreshNode(mPendingFocusedNode);
            }
        }
    }

    private void setPendingFocusedNode(@Nullable AccessibilityNodeInfo node) {
        Utils.recycleNode(mPendingFocusedNode);
        mPendingFocusedNode = copyNode(node);
        mPendingFocusedExpirationTime = SystemClock.uptimeMillis() + mAfterFocusTimeoutMs;
    }

    private void setEditNode(@Nullable AccessibilityNodeInfo editNode) {
        if ((mEditNode == null && editNode == null) ||
                (mEditNode != null && mEditNode.equals(editNode))) {
            return;
        }
        Utils.recycleNode(mEditNode);
        mEditNode = copyNode(editNode);
    }

    /**
     * Closes the IME if {@code newFocusedNode} isn't editable and isn't in the IME, and the
     * previously focused node is editable.
     */
    private void maybeCloseIme(@Nullable AccessibilityNodeInfo newFocusedNode) {
        // Don't close the IME unless we're moving from an editable view to a non-editable view.
        if (mFocusedNode == null || newFocusedNode == null
                || !mFocusedNode.isEditable() || newFocusedNode.isEditable()) {
            return;
        }

        // Don't close the IME if we're navigating to the IME.
        AccessibilityWindowInfo nextWindow = newFocusedNode.getWindow();
        if (nextWindow != null && nextWindow.getType() == TYPE_INPUT_METHOD) {
            Utils.recycleWindow(nextWindow);
            return;
        }
        Utils.recycleWindow(nextWindow);

        // To close the IME, we'll ask the FocusParkingView in the previous window to perform
        // ACTION_HIDE_IME.
        AccessibilityNodeInfo fpv = mNavigator.findFocusParkingView(mFocusedNode);
        if (fpv == null) {
            return;
        }
        if (!fpv.performAction(ACTION_HIDE_IME)) {
            L.w("Failed to close IME");
        }
        fpv.recycle();
    }

    /**
     * Sets {@link #mLastTouchedNode} to a copy of the given node, and clears {@link #mFocusedNode}.
     */
    private void setLastTouchedNode(@Nullable AccessibilityNodeInfo lastTouchedNode) {
        setLastTouchedNodeInternal(lastTouchedNode);
        if (mLastTouchedNode != null && mFocusedNode != null) {
            setFocusedNodeInternal(null);
        }
    }

    private void setLastTouchedNodeInternal(@Nullable AccessibilityNodeInfo lastTouchedNode) {
        if ((mLastTouchedNode == null && lastTouchedNode == null)
                || (mLastTouchedNode != null && mLastTouchedNode.equals(lastTouchedNode))) {
            L.d("Don't reset mLastTouchedNode since it stays the same: " + mLastTouchedNode);
            return;
        }

        Utils.recycleNode(mLastTouchedNode);
        mLastTouchedNode = copyNode(lastTouchedNode);
    }

    private void setIgnoreViewClickedNode(@Nullable AccessibilityNodeInfo node) {
        if (mIgnoreViewClickedNode != null) {
            mIgnoreViewClickedNode.recycle();
        }
        mIgnoreViewClickedNode = copyNode(node);
        if (node != null) {
            mLastViewClickedTime = SystemClock.uptimeMillis();
        }
    }

    private void setInRotaryMode(boolean inRotaryMode) {
        if (inRotaryMode == mInRotaryMode) {
            return;
        }
        mInRotaryMode = inRotaryMode;

        // If we're controlling direct manipulation mode (i.e., the focused node supports rotate
        // directly), exit the mode when the user touches the screen.
        if (!inRotaryMode && mInDirectManipulationMode) {
            if (mFocusedNode == null) {
                L.e("mFocused is null in direct manipulation mode");
            } else if (DirectManipulationHelper.supportRotateDirectly(mFocusedNode)) {
                L.d("Exit direct manipulation mode on user touch");
                mInDirectManipulationMode = false;
                boolean result = mFocusedNode.performAction(ACTION_CLEAR_SELECTION);
                if (!result) {
                    L.w("Failed to perform ACTION_CLEAR_SELECTION on " + mFocusedNode);
                }
            } else {
                L.d("The client app should exit direct manipulation mode");
            }
        }

        // Update IME.
        if (TextUtils.isEmpty(mRotaryInputMethod)) {
            L.w("No rotary IME configured");
            return;
        }
        if (TextUtils.isEmpty(mTouchInputMethod)) {
            L.w("No touch IME configured");
            return;
        }
        if (!inRotaryMode) {
            setEditNode(null);
        }
        // Switch to the rotary IME or the touch IME.
        String newIme = inRotaryMode ? mRotaryInputMethod : mTouchInputMethod;
        if (!isValidIme(newIme)) {
            L.w("Invalid IME: " + newIme);
            return;
        }
        boolean result =
                Settings.Secure.putString(getContentResolver(), DEFAULT_INPUT_METHOD, newIme);
        if (!result) {
            L.w("Failed to switch IME: " + newIme);
        }
    }

    /**
     * Performs {@link AccessibilityNodeInfo#ACTION_FOCUS} on a copy of the given {@code
     * targetNode}.
     *
     * @param targetNode the node to perform action on
     *
     * @return true if {@code targetNode} was focused already or became focused after performing
     *         {@link AccessibilityNodeInfo#ACTION_FOCUS}
     */
    private boolean performFocusAction(@NonNull AccessibilityNodeInfo targetNode) {
        return performFocusAction(targetNode, /* arguments= */ null);
    }

    /**
     * Performs {@link AccessibilityNodeInfo#ACTION_FOCUS} on a copy of the given {@code
     * targetNode}.
     *
     * @param targetNode the node to perform action on
     * @param arguments optional bundle with additional arguments
     *
     * @return true if {@code targetNode} was focused already or became focused after performing
     *         {@link AccessibilityNodeInfo#ACTION_FOCUS}
     */
    private boolean performFocusAction(
            @NonNull AccessibilityNodeInfo targetNode, @Nullable Bundle arguments) {
        // If performFocusActionInternal is called on a reference to a saved node, for example
        // mFocusedNode, mFocusedNode might get recycled. If we use mFocusedNode later, it might
        // cause a crash. So let's pass a copy here.
        AccessibilityNodeInfo copyNode = copyNode(targetNode);
        boolean success = performFocusActionInternal(copyNode, arguments);
        copyNode.recycle();
        return success;
    }

    /**
     * Performs {@link AccessibilityNodeInfo#ACTION_FOCUS} on the given {@code targetNode}.
     * <p>
     * Note: Only {@link #performFocusAction(AccessibilityNodeInfo, Bundle)} can call this method.
     */
    private boolean performFocusActionInternal(
            @NonNull AccessibilityNodeInfo targetNode, @Nullable Bundle arguments) {
        if (targetNode.equals(mFocusedNode)) {
            L.d("No need to focus on targetNode because it's already focused: " + targetNode);
            return true;
        }
        boolean isInWebView = mNavigator.isInWebView(targetNode);
        if (!Utils.isFocusArea(targetNode) && Utils.hasFocus(targetNode) && !isInWebView) {
            // One of targetNode's descendants is already focused, so we can't perform ACTION_FOCUS
            // on targetNode directly unless it's a FocusArea. The workaround is to clear the focus
            // first (by focusing on the FocusParkingView), then focus on targetNode. The
            // prohibition on focusing a node that has focus doesn't apply in WebViews.
            L.d("One of targetNode's descendants is already focused: " + targetNode);
            if (!clearFocusInCurrentWindow()) {
                return false;
            }
        }

        // Now we can perform ACTION_FOCUS on targetNode since it doesn't have focus, its
        // descendant's focus has been cleared, or it's a FocusArea.
        boolean result = targetNode.performAction(ACTION_FOCUS, arguments);
        if (!result) {
            L.w("Failed to perform ACTION_FOCUS on node " + targetNode);
            return false;
        }
        L.d("Performed ACTION_FOCUS on node " + targetNode);

        // If we performed ACTION_FOCUS on a FocusArea, find the descendant that was focused as a
        // result.
        if (Utils.isFocusArea(targetNode)) {
            if (findFocusedNode(targetNode)) {
                return true;
            } else {
                L.w("Unable to find focus after performing ACTION_FOCUS on a FocusArea");
            }
        }

        // Update mFocusedNode and mPendingFocusedNode.
        setFocusedNode(Utils.isFocusParkingView(targetNode) ? null : targetNode);
        setPendingFocusedNode(targetNode);
        return true;
    }

    /**
     * Searches {@code node} and its descendants for the focused node. If found, sets
     * {@link #mFocusedNode} and {@link #mPendingFocusedNode}. Returns whether the focus was found.
     * This method should be called after performing an action which changes the focus where we
     * can't predict which node will be focused.
     */
    private boolean findFocusedNode(@NonNull AccessibilityNodeInfo node) {
        AccessibilityNodeInfo foundFocus = node.findFocus(FOCUS_INPUT);
        if (foundFocus == null) {
            L.w("Failed to find focused node in " + node);
            return false;
        }
        L.d("Found focused node " + foundFocus);
        setFocusedNode(foundFocus);
        setPendingFocusedNode(foundFocus);
        foundFocus.recycle();
        return true;
    }

    /**
     * Returns the number of "ticks" to rotate for a single rotate event with the given detent
     * {@code count} at the given time. Uses and updates {@link #mLastRotateEventTime}. The result
     * will be one, two, or three times the given detent {@code count} depending on the interval
     * between the current event and the previous event and the detent {@code count}.
     *
     * @param count     the number of detents the user rotated
     * @param eventTime the {@link SystemClock#uptimeMillis} when the event occurred
     * @return the number of "ticks" to rotate
     */
    private int getRotateAcceleration(int count, long eventTime) {
        // count is 0 when testing key "C" or "V" is pressed.
        if (count <= 0) {
            count = 1;
        }
        int result = count;
        // TODO(b/153195148): This method can be improved once we've plumbed through the VHAL
        //  changes. We'll get timestamps for each detent.
        long delta = (eventTime - mLastRotateEventTime) / count;  // Assume constant speed.
        if (delta <= mRotationAcceleration3xMs) {
            result = count * 3;
        } else if (delta <= mRotationAcceleration2xMs) {
            result = count * 2;
        }
        mLastRotateEventTime = eventTime;
        return result;
    }

    private AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return mNodeCopier.copy(node);
    }

    /**
     * Checks if the {@code componentName} is an enabled input method.
     * The string should be in the format {@code "PackageName/.ClassName"}.
     * Example: {@code "com.android.inputmethod.latin/.CarLatinIME"}.
     */
    private boolean isValidIme(String componentName) {
        if (TextUtils.isEmpty(componentName)) {
            return false;
        }
        String enabledInputMethods = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
        return enabledInputMethods != null && enabledInputMethods.contains(componentName);
    }
}
