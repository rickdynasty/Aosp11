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

import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;

import com.android.car.rotary.Navigator.FindRotateTargetResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class NavigatorTest {

    private Rect mHunWindowBounds;
    private NodeBuilder mNodeBuilder;
    private Navigator mNavigator;

    @Before
    public void setUp() {
        mHunWindowBounds = new Rect(50, 10, 950, 200);
        mNodeBuilder = new NodeBuilder(new ArrayList<>());
        // The values of displayWidth and displayHeight don't affect the test, so just use 0.
        mNavigator = new Navigator(/* displayWidth= */ 0, /* displayHeight= */ 0,
                mHunWindowBounds.left, mHunWindowBounds.right,/* showHunOnBottom= */ false);
        mNavigator.setNodeCopier(MockNodeCopierProvider.get());
    }

    @Test
    public void testSetRootNodeForWindow() {
        AccessibilityWindowInfo window = new WindowBuilder().build();
        AccessibilityNodeInfo root = mNodeBuilder.build();
        setRootNodeForWindow(root, window);

        assertThat(window.getRoot()).isSameInstanceAs(root);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *              root
     *               |
     *           focusArea
     *          /    |    \
     *        /      |     \
     *    button1 button2 button3
     * </pre>
     */
    @Test
    public void testFindRotateTarget() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo focusArea = mNodeBuilder.setParent(root).setFocusArea().build();

        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(focusArea).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(focusArea).build();
        AccessibilityNodeInfo button3 = mNodeBuilder.setParent(focusArea).build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(null);

        // Rotate once, the focus should move from button1 to button2.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isSameInstanceAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        // Rotate twice, the focus should move from button1 to button3.
        target = mNavigator.findRotateTarget(button1, direction, 2);
        assertThat(target.node).isSameInstanceAs(button3);
        assertThat(target.advancedCount).isEqualTo(2);

        // Rotate 3 times and exceed the boundary, the focus should stay at the boundary.
        target = mNavigator.findRotateTarget(button1, direction, 3);
        assertThat(target.node).isSameInstanceAs(button3);
        assertThat(target.advancedCount).isEqualTo(2);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                     root
     *                    /    \
     *                   /      \
     *      focusParkingView   focusArea
     *                           /    \
     *                          /      \
     *                       button1  button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAround() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo focusParkingView = mNodeBuilder.setParent(root).setFpv().build();
        AccessibilityNodeInfo focusArea = mNodeBuilder.setParent(root).setFocusArea().build();

        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(focusArea).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(focusArea).build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(focusParkingView);
        when(focusParkingView.focusSearch(direction)).thenReturn(button1);

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                     root
     *                    /    \
     *                   /      \
     *           focusArea  genericFocusParkingView
     *            /    \
     *           /      \
     *       button1  button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAroundWithGenericFpv() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo focusArea = mNodeBuilder.setParent(root).setFocusArea().build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(focusArea).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(focusArea).build();

        AccessibilityNodeInfo focusParkingView = mNodeBuilder.setParent(
                root).setGenericFpv().build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(focusParkingView);
        when(focusParkingView.focusSearch(direction)).thenReturn(button1);

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *      focusParkingView   button1   button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAround2() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo focusParkingView = mNodeBuilder.setParent(root).setFpv().build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(root).build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(focusParkingView);
        when(focusParkingView.focusSearch(direction)).thenReturn(button1);

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *              button1   button2  genericFocusParkingView
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAround2WithGenericFpv() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo focusParkingView = mNodeBuilder.setParent(
                root).setGenericFpv().build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(focusParkingView);
        when(focusParkingView.focusSearch(direction)).thenReturn(button1);

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *               button1   invisible   button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetDoesNotSkipInvisibleNode() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo invisible = mNodeBuilder
                .setParent(root)
                .setVisibleToUser(false)
                .setBoundsInScreen(new Rect(0, 0, 0, 0))
                .build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(root).build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(invisible);
        when(invisible.focusSearch(direction)).thenReturn(button2);

        // Rotate from button1, it shouldn't skip the invisible view.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isSameInstanceAs(invisible);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *               button1   empty   button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetSkipNodeThatCannotPerformFocus() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo empty = mNodeBuilder
                .setParent(root)
                .setBoundsInParent(new Rect(0, 0, 0, 0))
                .build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(root).build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(empty);
        when(empty.focusSearch(direction)).thenReturn(button2);

        // Rotate from button1, it should skip the empty view.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isSameInstanceAs(button2);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *              button1   scrollable  button2
     *                       recyclerView
     *                            |
     *                      non-focusable
     * </pre>
     */
    @Test
    public void testFindRotateTargetReturnScrollableContainer() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo recyclerView = mNodeBuilder
                .setParent(root)
                .setScrollableContainer()
                .setScrollable(true)
                .build();
        AccessibilityNodeInfo nonFocusable = mNodeBuilder
                .setFocusable(false)
                .setParent(recyclerView)
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(recyclerView);
        when(recyclerView.focusSearch(direction)).thenReturn(button2);

        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isSameInstanceAs(recyclerView);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *                   /        |        \
     *             button1  non-scrollable  button2
     *                       recyclerView
     * </pre>
     */
    @Test
    public void testFindRotateTargetSkipScrollableContainer() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo recyclerView = mNodeBuilder
                .setParent(root)
                .setScrollableContainer()
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(recyclerView);
        when(recyclerView.focusSearch(direction)).thenReturn(button2);

        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isSameInstanceAs(button2);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                     root
     *                    /    \
     *                  /       \
     *    focusParkingView    scrollable
     *                       recyclerView
     *                           /    \
     *                          /      \
     *                  focusable1    focusable2
     * </pre>
     */
    @Test
    public void testFindRotateTargetSkipScrollableContainer2() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo focusParkingView = mNodeBuilder.setParent(root).setFpv().build();
        AccessibilityNodeInfo recyclerView = mNodeBuilder
                .setParent(root)
                .setScrollableContainer()
                .setScrollable(true)
                .build();
        AccessibilityNodeInfo focusable1 = mNodeBuilder.setParent(recyclerView).build();
        AccessibilityNodeInfo focusable2 = mNodeBuilder.setParent(recyclerView).build();

        int direction = View.FOCUS_BACKWARD;
        when(focusable2.focusSearch(direction)).thenReturn(focusable1);
        when(focusable1.focusSearch(direction)).thenReturn(recyclerView);
        when(recyclerView.focusSearch(direction)).thenReturn(focusParkingView);

        FindRotateTargetResult target = mNavigator.findRotateTarget(focusable2, direction, 2);
        assertThat(target.node).isSameInstanceAs(focusable1);
        assertThat(target.advancedCount).isEqualTo(1);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *             node
     * </pre>
     */
    @Test
    public void testFindRotateTargetWithOneNode() {
        AccessibilityNodeInfo node = mNodeBuilder.build();
        int direction = View.FOCUS_BACKWARD;
        when(node.focusSearch(direction)).thenReturn(node);

        FindRotateTargetResult target = mNavigator.findRotateTarget(node, direction, 1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following layout:
     * <pre>
     *     ============ focus area ============
     *     =                                  =
     *     =  ***** scrollable container **** =
     *     =  *                             * =
     *     =  *  ........ button 1 ........ * =
     *     =  *  .                        . * =
     *     =  *  .......................... * =
     *     =  *                             * =
     *     =  *  ........ button 2 ........ * =
     *     =  *  .                        . * =
     *     =  *  .......................... * =
     *     =  *                             * =
     *     =  ******************************* =
     *     =                                  =
     *     ============ focus area ============
     *
     *           ........ button 3 ........
     *           .                        .
     *           ..........................
     * </pre>
     * where {@code button 3} is not a descendant of the scrollable container.
     */
    @Test
    public void testFindRotateTargetInScrollableContainer() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo focusArea = mNodeBuilder
                .setParent(root)
                .setFocusArea()
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();
        AccessibilityNodeInfo scrollableContainer = mNodeBuilder
                .setParent(focusArea)
                .setScrollableContainer()
                .setActions(ACTION_SCROLL_FORWARD)
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();

        AccessibilityNodeInfo button1 = mNodeBuilder
                .setParent(scrollableContainer)
                .setBoundsInScreen(new Rect(0, 0, 100, 50))
                .build();
        AccessibilityNodeInfo button2 = mNodeBuilder
                .setParent(scrollableContainer)
                .setBoundsInScreen(new Rect(0, 50, 100, 100))
                .build();
        AccessibilityNodeInfo button3 = mNodeBuilder
                .setParent(root)
                .setBoundsInScreen(new Rect(0, 100, 100, 150))
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(null);

        // Rotate once, the focus should move from button1 to button2.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isSameInstanceAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        // Rotate twice, the focus should move from button1 to button2 since button3 is not a
        // descendant of the scrollable container.
        target = mNavigator.findRotateTarget(button1, direction, 2);
        assertThat(target.node).isSameInstanceAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        // Rotate three times should do the same.
        target = mNavigator.findRotateTarget(button1, direction, 3);
        assertThat(target.node).isSameInstanceAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following layout:
     * <pre>
     *     ============ focus area ============
     *     =                                  =
     *     =  ***** scrollable container **** =
     *     =  *                             * =
     *     =  *  ........ button 1 ........ * =
     *     =  *  .                        . * =
     *     =  *  .......................... * =
     *     =  *                             * =
     *     =  *  ........ button 2 ........ * =
     *     =  *  .                        . * =
     *     =  *  .......................... * =
     *     =  *                             * =
     *     =  ******************************* =
     *     =                                  =
     *     ============ focus area ============
     *
     *           ........ button 3 ........
     *           .                        .
     *           ..........................
     * </pre>
     * where {@code button 3} is off the screen.
     */
    @Test
    public void testFindRotateTargetInScrollableContainer2() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo focusArea = mNodeBuilder
                .setParent(root)
                .setFocusArea()
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();
        AccessibilityNodeInfo scrollableContainer = mNodeBuilder
                .setParent(focusArea)
                .setScrollableContainer()
                .setActions(ACTION_SCROLL_FORWARD)
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();

        AccessibilityNodeInfo button1 = mNodeBuilder
                .setParent(scrollableContainer)
                .setBoundsInScreen(new Rect(0, 0, 100, 50))
                .build();
        AccessibilityNodeInfo button2 = mNodeBuilder
                .setParent(scrollableContainer)
                .setBoundsInScreen(new Rect(0, 50, 100, 100))
                .build();
        AccessibilityNodeInfo button3 = mNodeBuilder
                .setParent(root)
                .setBoundsInScreen(new Rect(0, 0, 0, 0))
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(null);

        // Rotate once, the focus should move from button1 to button2.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isSameInstanceAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        // Rotate twice, the focus should move from button1 to button2 since button3 is off the
        // screen.
        target = mNavigator.findRotateTarget(button1, direction, 2);
        assertThat(target.node).isSameInstanceAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        // Rotate three times should do the same.
        target = mNavigator.findRotateTarget(button1, direction, 3);
        assertThat(target.node).isSameInstanceAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);
    }

    /**
     * Tests {@link Navigator#findScrollableContainer} in the following node tree:
     * <pre>
     *                root
     *                 |
     *                 |
     *             focusArea
     *              /     \
     *            /         \
     *        scrolling    button2
     *        container
     *           |
     *           |
     *       container
     *           |
     *           |
     *        button1
     * </pre>
     */
    @Test
    public void testFindScrollableContainer() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo focusArea = mNodeBuilder.setParent(root).setFocusArea().build();
        AccessibilityNodeInfo scrollableContainer = mNodeBuilder
                .setParent(focusArea)
                .setScrollableContainer()
                .build();
        AccessibilityNodeInfo container = mNodeBuilder.setParent(scrollableContainer).build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(container).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(focusArea).build();

        AccessibilityNodeInfo target = mNavigator.findScrollableContainer(button1);
        assertThat(target).isSameInstanceAs(scrollableContainer);
        target = mNavigator.findScrollableContainer(button2);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findFocusableDescendantInDirection} going
     *      * {@link View#FOCUS_BACKWARD} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     */
    @Test
    public void testFindFocusableVisibleDescendantInDirectionBackward() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo container1 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(container1).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(container1).build();
        AccessibilityNodeInfo container2 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button3 = mNodeBuilder.setParent(container2).build();
        AccessibilityNodeInfo button4 = mNodeBuilder.setParent(container2).build();

        int direction = View.FOCUS_BACKWARD;
        when(button4.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button1);
        when(button1.focusSearch(direction)).thenReturn(null);

        AccessibilityNodeInfo target = mNavigator.findFocusableDescendantInDirection(
                container2, button4, View.FOCUS_BACKWARD);
        assertThat(target).isSameInstanceAs(button3);
        target = mNavigator.findFocusableDescendantInDirection(container2, button3,
                View.FOCUS_BACKWARD);
        assertThat(target).isNull();
        target = mNavigator.findFocusableDescendantInDirection(container1, button2,
                View.FOCUS_BACKWARD);
        assertThat(target).isSameInstanceAs(button1);
        target = mNavigator.findFocusableDescendantInDirection(container1, button1,
                View.FOCUS_BACKWARD);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findFocusableDescendantInDirection} going
     * {@link View#FOCUS_FORWARD} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     */
    @Test
    public void testFindFocusableVisibleDescendantInDirectionForward() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo container1 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(container1).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(container1).build();
        AccessibilityNodeInfo container2 = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button3 = mNodeBuilder.setParent(container2).build();
        AccessibilityNodeInfo button4 = mNodeBuilder.setParent(container2).build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(button4);
        when(button4.focusSearch(direction)).thenReturn(null);

        AccessibilityNodeInfo target = mNavigator.findFocusableDescendantInDirection(
                container1, button1, View.FOCUS_FORWARD);
        assertThat(target).isSameInstanceAs(button2);
        target = mNavigator.findFocusableDescendantInDirection(container1, button2,
                View.FOCUS_FORWARD);
        assertThat(target).isNull();
        target = mNavigator.findFocusableDescendantInDirection(container2, button3,
                View.FOCUS_FORWARD);
        assertThat(target).isSameInstanceAs(button4);
        target = mNavigator.findFocusableDescendantInDirection(container2, button4,
                View.FOCUS_FORWARD);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findNextFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                      |
     *                      |
     *                  container
     *               /    /   \   \
     *            /      /     \      \
     *     button1  button2  button3  button4
     * </pre>
     * where {@code button3} and {@code button4} have empty bounds.
     */
    @Test
    public void testFindNextFocusableDescendantWithEmptyBounds() {
        AccessibilityNodeInfo root = mNodeBuilder.build();
        AccessibilityNodeInfo container = mNodeBuilder.setParent(root).build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(container).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(container).build();
        AccessibilityNodeInfo button3 = mNodeBuilder.setParent(container)
                .setBoundsInScreen(new Rect(5, 10, 5, 10)).build();
        AccessibilityNodeInfo button4 = mNodeBuilder.setParent(container)
                .setBoundsInScreen(new Rect(20, 40, 20, 40)).build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(button4);
        when(button4.focusSearch(direction)).thenReturn(button1);

        AccessibilityNodeInfo target = mNavigator.findFocusableDescendantInDirection(container,
                button1, View.FOCUS_FORWARD);
        assertThat(target).isSameInstanceAs(button2);
        target = mNavigator.findFocusableDescendantInDirection(container, button2,
                View.FOCUS_FORWARD);
        assertThat(target).isSameInstanceAs(button1);
        target = mNavigator.findFocusableDescendantInDirection(container, button3,
                View.FOCUS_FORWARD);
        assertThat(target).isSameInstanceAs(button1);
        target = mNavigator.findFocusableDescendantInDirection(container, button4,
                View.FOCUS_FORWARD);
        assertThat(target).isSameInstanceAs(button1);
    }

    /**
     * Tests {@link Navigator#findFirstFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     * where {@code button1} and {@code button2} are disabled.
     */
    @Test
    public void testFindFirstFocusableDescendant() {
        AccessibilityNodeInfo root = mNodeBuilder.setFocusable(false).build();
        AccessibilityNodeInfo container1 = mNodeBuilder
                .setParent(root)
                .setFocusable(false)
                .build();
        AccessibilityNodeInfo button1 = mNodeBuilder
                .setParent(container1)
                .setEnabled(false)
                .build();
        AccessibilityNodeInfo button2 = mNodeBuilder
                .setParent(container1)
                .setEnabled(false)
                .build();
        AccessibilityNodeInfo container2 = mNodeBuilder
                .setParent(root)
                .setFocusable(false)
                .build();
        AccessibilityNodeInfo button3 = mNodeBuilder.setParent(container2).build();
        AccessibilityNodeInfo button4 = mNodeBuilder.setParent(container2).build();

        AccessibilityNodeInfo target = mNavigator.findFirstFocusableDescendant(root);
        assertThat(target).isSameInstanceAs(button3);
    }

    /**
     * Tests {@link Navigator#findLastFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     * where {@code button3} and {@code button4} are disabled.
     */
    @Test
    public void testFindLastFocusableDescendant() {
        AccessibilityNodeInfo root = mNodeBuilder.setFocusable(false).build();
        AccessibilityNodeInfo container1 = mNodeBuilder
                .setParent(root)
                .setFocusable(false)
                .build();
        AccessibilityNodeInfo button1 = mNodeBuilder.setParent(container1).build();
        AccessibilityNodeInfo button2 = mNodeBuilder.setParent(container1).build();
        AccessibilityNodeInfo container2 = mNodeBuilder
                .setParent(root)
                .setFocusable(false)
                .build();
        AccessibilityNodeInfo button3 = mNodeBuilder
                .setParent(container2)
                .setEnabled(false)
                .build();
        AccessibilityNodeInfo button4 = mNodeBuilder
                .setParent(container2)
                .setEnabled(false)
                .build();

        AccessibilityNodeInfo target = mNavigator.findLastFocusableDescendant(root);
        assertThat(target).isSameInstanceAs(button2);
    }

    /** Sets the {@code root} node in the {@code window}'s hierarchy. */
    private void setRootNodeForWindow(@NonNull AccessibilityNodeInfo root,
            @NonNull AccessibilityWindowInfo window) {
        when(window.getRoot()).thenReturn(root);
    }
}
