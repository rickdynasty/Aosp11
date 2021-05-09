/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.car.media;

import static com.android.car.arch.common.LiveDataFunctions.ifThenElse;

import android.car.content.pm.CarPackageManager;
import android.content.Context;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.apps.common.util.ViewUtils;
import com.android.car.arch.common.FutureData;
import com.android.car.media.browse.BrowseAdapter;
import com.android.car.media.browse.LimitedBrowseAdapter;
import com.android.car.media.common.GridSpacingItemDecoration;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.browse.BrowsedMediaItems;
import com.android.car.media.common.browse.MediaBrowserViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.widgets.AppBarController;
import com.android.car.ui.FocusArea;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.toolbar.Toolbar;
import com.android.car.uxr.LifeCycleObserverUxrContentLimiter;
import com.android.car.uxr.UxrContentLimiterImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Stack;

/**
 * A view controller that implements the content forward browsing experience.
 *
 * This can be used to display either search or browse results at the root level. Deeper levels will
 * be handled the same way between search and browse, using a back stack to return to the root.
 */
public class BrowseViewController extends ViewControllerBase {
    private static final String TAG = "BrowseViewController";

    private static final String REGULAR_BROWSER_VIEW_MODEL_KEY
            = "com.android.car.media.regular_browser_view_model";
    private static final String SEARCH_BROWSER_VIEW_MODEL_KEY
            = "com.android.car.media.search_browser_view_model";

    private final Callbacks mCallbacks;
    private final FocusArea mFocusArea;
    private final LifeCycleObserverUxrContentLimiter mUxrContentLimiter;
    private final RecyclerView mBrowseList;
    private final ImageView mErrorIcon;
    private final TextView mMessage;
    private final LimitedBrowseAdapter mLimitedBrowseAdapter;
    private String mSearchQuery;
    private final int mFadeDuration;
    private final int mLoadingIndicatorDelay;
    private final boolean mIsSearchController;
    private final boolean mSetFocusAreaHighlightBottom;
    private final MutableLiveData<Boolean> mShowSearchResults = new MutableLiveData<>();
    private final Handler mHandler = new Handler();
    /**
     * Stores the reference to {@link MediaActivity.ViewModel#getSearchStack} or to
     * {@link MediaActivity.ViewModel#getBrowseStack}. Updated in {@link #onMediaSourceChanged}.
     */
    private Stack<MediaItemMetadata> mBrowseStack = new Stack<>();
    private final MediaActivity.ViewModel mViewModel;
    private final MediaBrowserViewModel mRootMediaBrowserViewModel;
    private final MediaBrowserViewModel.WithMutableBrowseId mMediaBrowserViewModel;
    private final BrowseAdapter.Observer mBrowseAdapterObserver = new BrowseAdapter.Observer() {

        @Override
        protected void onPlayableItemClicked(MediaItemMetadata item) {
            hideKeyboard();
            getParent().onPlayableItemClicked(item);
        }

        @Override
        protected void onBrowsableItemClicked(MediaItemMetadata item) {
            hideKeyboard();
            navigateInto(item);
        }
    };

    private boolean mBrowseTreeHasChildren;
    private boolean mAcceptTabSelection = true;

    /**
     * Media items to display as tabs. If null, it means we haven't finished loading them yet. If
     * empty, it means there are no tabs to show
     */
    @Nullable
    private List<MediaItemMetadata> mTopItems;

    /**
     * The bottom padding of the FocusArea highlight.
     */
    private int mFocusAreaHighlightBottomPadding;

    /**
     * Callbacks (implemented by the hosting Activity)
     */
    public interface Callbacks {
        /**
         * Method invoked when the user clicks on a playable item
         *
         * @param item item to be played.
         */
        void onPlayableItemClicked(MediaItemMetadata item);

        /** Called once the list of the root node's children has been loaded. */
        void onRootLoaded();

        /** Change to a new UI mode. */
        void changeMode(MediaActivity.Mode mode);

        FragmentActivity getActivity();
    }

    /**
     * Moves the user one level up in the browse tree. Returns whether that was possible.
     */
    private boolean navigateBack() {
        boolean result = false;
        if (!isAtTopStack()) {
            mBrowseStack.pop();
            mMediaBrowserViewModel.search(mSearchQuery);
            mMediaBrowserViewModel.setCurrentBrowseId(getCurrentMediaItemId());
            updateAppBar();
            result = true;
        }
        if (isAtTopStack()) {
            mShowSearchResults.setValue(mIsSearchController);
        }
        return result;
    }

    private void reopenSearch() {
        if (mIsSearchController) {
            mBrowseStack.clear();
            updateAppBar();
            mShowSearchResults.setValue(true);
        } else {
            Log.e(TAG, "reopenSearch called on browse controller");
        }
    }

    @NonNull
    private Callbacks getParent() {
        return mCallbacks;
    }

    private FragmentActivity getActivity() {
        return mCallbacks.getActivity();
    }

    /**
     * @return whether the user is at the top of the browsing stack.
     */
    private boolean isAtTopStack() {
        if (mIsSearchController) {
            return mBrowseStack.isEmpty();
        } else {
            // The mBrowseStack stack includes the tab...
            return mBrowseStack.size() <= 1;
        }
    }

    /**
     * Creates a new instance of this controller meant to browse the root node.
     * @return a fully initialized {@link BrowseViewController}
     */
    public static BrowseViewController newInstance(Callbacks callbacks,
            CarPackageManager carPackageManager, ViewGroup container) {
        boolean isSearchController = false;
        return new BrowseViewController(callbacks, carPackageManager, container, isSearchController);
    }

    /**
     * Creates a new instance of this controller meant to display search results. The root browse
     * screen will be the search results for the provided query.
     *
     * @return a fully initialized {@link BrowseViewController}
     */
    static BrowseViewController newSearchInstance(Callbacks callbacks,
            CarPackageManager carPackageManager, ViewGroup container) {
        boolean isSearchController = true;
        return new BrowseViewController(callbacks, carPackageManager, container, isSearchController);
    }

    private void updateSearchQuery(@Nullable String query) {
        mSearchQuery = query;
        mMediaBrowserViewModel.search(query);
    }

    /**
     * Clears search state, removes any UI elements from previous results.
     */
    @Override
    void onMediaSourceChanged(@Nullable MediaSource mediaSource) {
        super.onMediaSourceChanged(mediaSource);

        mBrowseTreeHasChildren = false;

        if (mIsSearchController) {
            updateSearchQuery(mViewModel.getSearchQuery());
            mAppBarController.setSearchQuery(mSearchQuery);
            mBrowseStack = mViewModel.getSearchStack();
            mShowSearchResults.setValue(isAtTopStack());
        } else {
            mBrowseStack = mViewModel.getBrowseStack();
            mShowSearchResults.setValue(false);
            updateTabs((mediaSource != null) ? null : new ArrayList<>());
        }

        mLimitedBrowseAdapter.submitItems(null, null);
        stopLoadingIndicator();
        ViewUtils.hideViewAnimated(mErrorIcon, mFadeDuration);
        ViewUtils.hideViewAnimated(mMessage, mFadeDuration);

        mMediaBrowserViewModel.setCurrentBrowseId(getCurrentMediaItemId());

        updateAppBar();
    }

    private BrowseViewController(Callbacks callbacks, CarPackageManager carPackageManager,
            ViewGroup container, boolean isSearchController) {
        super(callbacks.getActivity(), carPackageManager, container, R.layout.fragment_browse);

        mCallbacks = callbacks;
        mIsSearchController = isSearchController;

        mLoadingIndicatorDelay = mContent.getContext().getResources()
                .getInteger(R.integer.progress_indicator_delay);
        mSetFocusAreaHighlightBottom = mContent.getContext().getResources().getBoolean(
                R.bool.set_browse_list_focus_area_highlight_above_minimized_control_bar);

        mAppBarController.setListener(mAppBarListener);
        mFocusArea = mContent.findViewById(R.id.focus_area);
        mBrowseList = mContent.findViewById(R.id.browse_list);
        mErrorIcon = mContent.findViewById(R.id.error_icon);
        mMessage = mContent.findViewById(R.id.error_message);
        mFadeDuration = mContent.getContext().getResources().getInteger(
                R.integer.new_album_art_fade_in_duration);


        FragmentActivity activity = callbacks.getActivity();

        mViewModel = ViewModelProviders.of(activity).get(MediaActivity.ViewModel.class);

        // Browse logic for the root node
        mRootMediaBrowserViewModel = MediaBrowserViewModel.Factory.getInstanceForBrowseRoot(
                mMediaSourceVM, ViewModelProviders.of(activity));
        mRootMediaBrowserViewModel.getBrowsedMediaItems()
                .observe(activity, futureData -> onItemsUpdate(/* forRoot */ true, futureData));

        mRootMediaBrowserViewModel.supportsSearch().observe(activity,
                mAppBarController::setSearchSupported);


        // Browse logic for current node
        mMediaBrowserViewModel = MediaBrowserViewModel.Factory.getInstanceWithMediaBrowser(
                mIsSearchController ? SEARCH_BROWSER_VIEW_MODEL_KEY : REGULAR_BROWSER_VIEW_MODEL_KEY,
                ViewModelProviders.of(activity),
                mMediaSourceVM.getConnectedMediaBrowser());

        mBrowseList.addItemDecoration(new GridSpacingItemDecoration(
                activity.getResources().getDimensionPixelSize(R.dimen.grid_item_spacing)));

        GridLayoutManager manager = (GridLayoutManager) mBrowseList.getLayoutManager();
        mLimitedBrowseAdapter = new LimitedBrowseAdapter(
                new BrowseAdapter(mBrowseList.getContext()), manager, mBrowseAdapterObserver);
        mBrowseList.setAdapter(mLimitedBrowseAdapter);

        mUxrContentLimiter = new LifeCycleObserverUxrContentLimiter(
                new UxrContentLimiterImpl(activity, R.xml.uxr_config));
        mUxrContentLimiter.setAdapter(mLimitedBrowseAdapter);
        activity.getLifecycle().addObserver(mUxrContentLimiter);

        mMediaBrowserViewModel.rootBrowsableHint().observe(activity,
                hint -> mLimitedBrowseAdapter.getBrowseAdapter().setRootBrowsableViewType(hint));
        mMediaBrowserViewModel.rootPlayableHint().observe(activity,
                hint -> mLimitedBrowseAdapter.getBrowseAdapter().setRootPlayableViewType(hint));
        LiveData<FutureData<List<MediaItemMetadata>>> mediaItems = ifThenElse(mShowSearchResults,
                mMediaBrowserViewModel.getSearchedMediaItems(),
                mMediaBrowserViewModel.getBrowsedMediaItems());

        mediaItems.observe(activity, futureData -> onItemsUpdate(/* forRoot */ false, futureData));

        updateAppBar();
    }

    private AppBarController.AppBarListener mAppBarListener = new BasicAppBarListener() {
        @Override
        public void onTabSelected(MediaItemMetadata item) {
            if (mAcceptTabSelection) {
                showTopItem(item);
            }
        }

        @Override
        public void onSearchSelection() {
            if (mIsSearchController) {
                reopenSearch();
            } else {
                mCallbacks.changeMode(MediaActivity.Mode.SEARCHING);
            }
        }

        @Override
        public void onSearch(String query) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onSearch: " + query);
            }
            mViewModel.setSearchQuery(query);
            updateSearchQuery(query);
        }
    };


    private Runnable mLoadingIndicatorRunnable = new Runnable() {
        @Override
        public void run() {
            mMessage.setText(R.string.browser_loading);
            ViewUtils.showViewAnimated(mMessage, mFadeDuration);
        }
    };

    boolean onBackPressed() {
        boolean success = navigateBack();
        if (!success && (mIsSearchController)) {
            mCallbacks.changeMode(MediaActivity.Mode.BROWSING);
            return true;
        }
        return success;
    }

    boolean browseTreeHasChildren() {
        return mBrowseTreeHasChildren;
    }

    private void startLoadingIndicator() {
        // Display the indicator after a certain time, to avoid flashing the indicator constantly,
        // even when performance is acceptable.
        mHandler.postDelayed(mLoadingIndicatorRunnable, mLoadingIndicatorDelay);
    }

    private void stopLoadingIndicator() {
        mHandler.removeCallbacks(mLoadingIndicatorRunnable);
        ViewUtils.hideViewAnimated(mMessage, mFadeDuration);
    }

    private void navigateInto(@Nullable MediaItemMetadata item) {
        if (item != null) {
            mBrowseStack.push(item);
            mMediaBrowserViewModel.setCurrentBrowseId(item.getId());
        } else {
            mMediaBrowserViewModel.setCurrentBrowseId(null);
        }

        mShowSearchResults.setValue(false);
        updateAppBar();
    }

    /**
     * @return the current item being displayed
     */
    @Nullable
    private MediaItemMetadata getCurrentMediaItem() {
        return mBrowseStack.isEmpty() ? null : mBrowseStack.lastElement();
    }

    @Nullable
    private String getCurrentMediaItemId() {
        MediaItemMetadata currentItem = getCurrentMediaItem();
        return currentItem != null ? currentItem.getId() : null;
    }

    @Override
    public void onCarUiInsetsChanged(@NonNull Insets insets) {
        int leftPadding = mBrowseList.getPaddingLeft();
        int rightPadding = mBrowseList.getPaddingRight();
        int bottomPadding = mBrowseList.getPaddingBottom();
        mBrowseList.setPadding(leftPadding, insets.getTop(), rightPadding, bottomPadding);
        if (bottomPadding > mFocusAreaHighlightBottomPadding) {
            mFocusAreaHighlightBottomPadding = bottomPadding;
        }
        mFocusArea.setHighlightPadding(
                leftPadding, insets.getTop(), rightPadding, mFocusAreaHighlightBottomPadding);
        mFocusArea.setBoundsOffset(leftPadding, insets.getTop(), rightPadding, bottomPadding);
    }

    void onPlaybackControlsChanged(boolean visible) {
        int leftPadding = mBrowseList.getPaddingLeft();
        int topPadding = mBrowseList.getPaddingTop();
        int rightPadding = mBrowseList.getPaddingRight();
        int bottomPadding = visible
                ? getActivity().getResources().getDimensionPixelOffset(
                        R.dimen.browse_fragment_bottom_padding)
                : 0;
        mBrowseList.setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        int highlightBottomPadding = mSetFocusAreaHighlightBottom ? bottomPadding : 0;
        if (highlightBottomPadding > mFocusAreaHighlightBottomPadding) {
            mFocusAreaHighlightBottomPadding = highlightBottomPadding;
        }
        mFocusArea.setHighlightPadding(
                leftPadding, topPadding, rightPadding, mFocusAreaHighlightBottomPadding);
        // Set the bottom offset to bottomPadding regardless of mSetFocusAreaHighlightBottom so that
        // RotaryService can find the correct target when the user nudges the rotary controller.
        mFocusArea.setBoundsOffset(leftPadding, topPadding, rightPadding, bottomPadding);

        ViewGroup.MarginLayoutParams messageLayout =
                (ViewGroup.MarginLayoutParams) mMessage.getLayoutParams();
        messageLayout.bottomMargin = bottomPadding;
        mMessage.setLayoutParams(messageLayout);
    }

    private void hideKeyboard() {
        InputMethodManager in =
                (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        in.hideSoftInputFromWindow(mContent.getWindowToken(), 0);
    }

    private void showTopItem(@Nullable MediaItemMetadata item) {
        mViewModel.getBrowseStack().clear();
        navigateInto(item);
    }

    /**
     * Updates the tabs displayed on the app bar, based on the top level items on the browse tree.
     * If there is at least one browsable item, we show the browse content of that node. If there
     * are only playable items, then we show those items. If there are not items at all, we show the
     * empty message. If we receive null, we show the error message.
     *
     * @param items top level items, null if the items are still being loaded, or empty list if
     *              items couldn't be loaded.
     */
    private void updateTabs(@Nullable List<MediaItemMetadata> items) {
        if (Objects.equals(mTopItems, items)) {
            // When coming back to the app, the live data sends an update even if the list hasn't
            // changed. Updating the tabs then recreates the browse view, which produces jank
            // (b/131830876), and also resets the navigation to the top of the first tab...
            return;
        }
        mTopItems = items;

        if (mTopItems == null || mTopItems.isEmpty()) {
            mAppBarController.setItems(null);
            mAppBarController.setActiveItem(null);
            if (items != null) {
                // Only do this when not loading the tabs or we loose the saved one.
                showTopItem(null);
            }
            updateAppBar();
            return;
        }

        MediaItemMetadata oldTab = mViewModel.getSelectedTab();
        try {
            mAcceptTabSelection = false;
            mAppBarController.setItems(mTopItems.size() == 1 ? null : mTopItems);
            updateAppBar();

            if (items.contains(oldTab)) {
                mAppBarController.setActiveItem(oldTab);
            } else {
                showTopItem(items.get(0));
            }
        }  finally {
            mAcceptTabSelection = true;
        }
    }

    private void updateAppBarTitle() {
        boolean isStacked = !isAtTopStack();

        final CharSequence title;
        if (isStacked) {
            // If not at top level, show the current item as title
            title = getCurrentMediaItem().getTitle();
        } else if (mTopItems == null) {
            // If still loading the tabs, force to show an empty bar.
            title = "";
        } else if (mTopItems.size() == 1) {
            // If we finished loading tabs and there is only one, use that as title.
            title = mTopItems.get(0).getTitle();
        } else {
            // Otherwise (no tabs or more than 1 tabs), show the current media source title.
            MediaSource mediaSource = mMediaSourceVM.getPrimaryMediaSource().getValue();
            title = getAppBarDefaultTitle(mediaSource);
        }

        mAppBarController.setTitle(title);
    }

    /**
     * Update elements of the appbar that change depending on where we are in the browse.
     */
    private void updateAppBar() {
        boolean isStacked = !isAtTopStack();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "App bar is in stacked state: " + isStacked);
        }
        Toolbar.State unstackedState =
                mIsSearchController ? Toolbar.State.SEARCH : Toolbar.State.HOME;
        updateAppBarTitle();
        mAppBarController.setState(isStacked ? Toolbar.State.SUBPAGE : unstackedState);
        mAppBarController.showSearchIfSupported(!mIsSearchController || isStacked);
    }

    private String getErrorMessage(boolean forRoot) {
        if (forRoot) {
            MediaSource mediaSource = mMediaSourceVM.getPrimaryMediaSource().getValue();
            return getActivity().getString(
                    R.string.cannot_connect_to_app,
                    mediaSource != null
                            ? mediaSource.getDisplayName()
                            : getActivity().getString(
                                    R.string.unknown_media_provider_name));
        } else {
            return getActivity().getString(R.string.unknown_error);
        }
    }



    private void onItemsUpdate(boolean forRoot, FutureData<List<MediaItemMetadata>> futureData) {

        // Prevent showing loading spinner or any error messages if search is uninitialized
        if (mIsSearchController && TextUtils.isEmpty(mSearchQuery)) {
            return;
        }

        if (!forRoot && !mBrowseTreeHasChildren && !mIsSearchController) {
            // Ignore live data ghost values
            return;
        }

        if (futureData.isLoading()) {
            startLoadingIndicator();
            ViewUtils.hideViewAnimated(mErrorIcon, 0);
            ViewUtils.hideViewAnimated(mMessage, 0);
            // TODO(b/139759881) build a jank-free animation of the transition.
            mBrowseList.setAlpha(0f);
            mLimitedBrowseAdapter.submitItems(null, null);

            if (forRoot) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "Loading browse tree...");
                }
                mBrowseTreeHasChildren = false;
                updateTabs(null);
            }
            return;
        }

        stopLoadingIndicator();

        List<MediaItemMetadata> items =
                BrowsedMediaItems.filterItems(forRoot, futureData.getData());
        if (forRoot) {
            boolean browseTreeHasChildren = items != null && !items.isEmpty();
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Browse tree loaded, status (has children or not) changed: "
                        + mBrowseTreeHasChildren + " -> " + browseTreeHasChildren);
            }
            mBrowseTreeHasChildren = browseTreeHasChildren;
            mCallbacks.onRootLoaded();
            updateTabs(items != null ? items : new ArrayList<>());
        } else {
            mLimitedBrowseAdapter.submitItems(getCurrentMediaItem(), items);
        }

        int duration = forRoot ? 0 : mFadeDuration;
        if (items == null) {
            mMessage.setText(getErrorMessage(forRoot));
            ViewUtils.hideViewAnimated(mBrowseList, duration);
            ViewUtils.showViewAnimated(mMessage, duration);
            ViewUtils.showViewAnimated(mErrorIcon, duration);
        } else if (items.isEmpty()) {
            mMessage.setText(R.string.nothing_to_play);
            ViewUtils.hideViewAnimated(mBrowseList, duration);
            ViewUtils.hideViewAnimated(mErrorIcon, duration);
            ViewUtils.showViewAnimated(mMessage, duration);
        } else if (!forRoot) {
            ViewUtils.showViewAnimated(mBrowseList, duration);
            ViewUtils.hideViewAnimated(mErrorIcon, duration);
            ViewUtils.hideViewAnimated(mMessage, duration);
        }
    }
}
