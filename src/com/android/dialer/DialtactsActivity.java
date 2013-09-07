/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
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

package com.android.dialer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.ActionBar;
import android.app.ActionBar.LayoutParams;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Intents.UI;
import android.provider.Settings;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupMenu;
import android.widget.SearchView;
import android.widget.SearchView.OnCloseListener;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.Toast;

import com.android.contacts.common.CallUtil;
import com.android.contacts.common.activity.TransactionSafeActivity;
import com.android.contacts.common.interactions.ImportExportDialogFragment;
import com.android.contacts.common.interactions.ImportExportDialogFragment.ExportToSimThread;
import com.android.contacts.common.list.ContactListFilterController;
import com.android.contacts.common.list.ContactListFilterController.ContactListFilterListener;
import com.android.contacts.common.list.ContactListItemView;
import com.android.contacts.common.list.OnPhoneNumberPickerActionListener;
import com.android.contacts.common.list.PhoneNumberPickerFragment;
import com.android.contacts.common.SimContactsConstants;
import com.android.contacts.common.util.AccountFilterUtil;
import com.android.contacts.common.vcard.ExportVCardActivity;
import com.android.contacts.common.vcard.VCardCommonArguments;
import com.android.dialer.calllog.CallLogFragment;
import com.android.dialer.calllog.MSimCallLogFragment;
import com.android.dialer.CustomViewPager;
import com.android.dialer.DialpadCling;
import com.android.dialer.dialpad.DialpadFragment;
import com.android.dialer.dialpad.SmartDialpadFragment;
import com.android.dialer.interactions.PhoneNumberInteraction;
import com.android.dialer.list.PhoneFavoriteFragment;
import com.android.dialer.util.OrientationUtil;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.TelephonyIntents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
/**
 * The dialer activity that has one tab with the virtual 12key
 * dialer, a tab with recent calls in it, a tab with the contacts and
 * a tab with the favorite. This is the container and the tabs are
 * embedded using intents.
 * The dialer tab's title is 'phone', a more common name (see strings.xml).
 */
public class DialtactsActivity extends TransactionSafeActivity implements View.OnClickListener {
    private static final String TAG = "DialtactsActivity";

    public static final boolean DEBUG = false;

    /** Used to open Call Setting */
    private static final String PHONE_PACKAGE = "com.android.phone";
    private static final String CALL_SETTINGS_CLASS_NAME =
            "com.android.phone.CallFeaturesSetting";
    private static final String MSIM_CALL_SETTINGS_CLASS_NAME =
            "com.android.phone.MSimCallFeaturesSetting";

    /** @see #getCallOrigin() */
    private static final String CALL_ORIGIN_DIALTACTS =
            "com.android.dialer.DialtactsActivity";

    /**
     * Just for backward compatibility. Should behave as same as {@link Intent#ACTION_DIAL}.
     */
    private static final String ACTION_TOUCH_DIALER = "com.android.phone.action.TOUCH_DIALER";

    private static final String ACTION_SEARCH = "android.intent.action.SEARCH";
    /** Used both by {@link ActionBar} and {@link ViewPagerAdapter} */
    public static final int TAB_INDEX_DIALER = 0;
    private static final int TAB_INDEX_CALL_LOG = 1;
    private static final int TAB_INDEX_FAVORITES = 2;

    private static final int TAB_INDEX_COUNT = 3;

    private static final int SUBACTIVITY_EXPORT_CONTACTS = 100;
    public SharedPreferences mPrefs;

    /** Last manually selected tab index */
    private static final String PREF_LAST_MANUALLY_SELECTED_TAB =
            "DialtactsActivity_last_manually_selected_tab";
    private static final int PREF_LAST_MANUALLY_SELECTED_TAB_DEFAULT = TAB_INDEX_DIALER;

    private static final int SUBACTIVITY_ACCOUNT_FILTER = 1;
    public static boolean mDialpadClingShowed = false;
    private ArrayList<String[]> mContactList;
    private ExportToSimThread mExportThread = null;
    private final BroadcastReceiver mExportToSimCompleteListener = new BroadcastReceiver (){
        public void onReceive(Context context, Intent intent){
            String action = intent.getAction();

            if (action.equals(SimContactsConstants.INTENT_EXPORT_COMPLETE)){
                ImportExportDialogFragment.destroyExportToSimThread();
                mExportThread = null;
            }
        }
    };

    public class ViewPagerAdapter extends FragmentPagerAdapter {
        public ViewPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case TAB_INDEX_DIALER:
                    if (SystemProperties.getBoolean("persist.env.phone.smartdialer", true)) {
                        return new SmartDialpadFragment();
                    } else {
                        return new DialpadFragment();
                    }
                case TAB_INDEX_CALL_LOG:
                    if (MSimTelephonyManager.getDefault().getPhoneCount() > 1) {
                        return new MSimCallLogFragment();
                    } else {
                        return new CallLogFragment();
                    }
                case TAB_INDEX_FAVORITES:
                    return new PhoneFavoriteFragment();
            }
            throw new IllegalStateException("No fragment at position " + position);
        }

        @Override
        public void setPrimaryItem(ViewGroup container, int position, Object object) {
            // The parent's setPrimaryItem() also calls setMenuVisibility(), so we want to know
            // when it happens.
            if (DEBUG) {
                Log.d(TAG, "FragmentPagerAdapter#setPrimaryItem(), position: " + position);
            }
            super.setPrimaryItem(container, position, object);
        }

        @Override
        public int getCount() {
            return TAB_INDEX_COUNT;
        }
    }

    /**
     * True when the app detects user's drag event. This variable should not become true when
     * mUserTabClick is true.
     *
     * During user's drag or tab click, we shouldn't show fake buttons but just show real
     * ActionBar at the bottom of the screen, for transition animation.
     */
    boolean mDuringSwipe = false;
    /**
     * True when the app detects user's tab click (at the top of the screen). This variable should
     * not become true when mDuringSwipe is true.
     *
     * During user's drag or tab click, we shouldn't show fake buttons but just show real
     * ActionBar at the bottom of the screen, for transition animation.
     */
    boolean mUserTabClick = false;

    public class PageChangeListener implements OnPageChangeListener {
        private int mCurrentPosition = -1;
        /**
         * Used during page migration, to remember the next position {@link #onPageSelected(int)}
         * specified.
         */
        private int mNextPosition = -1;

        @Override
        public void onPageScrolled(
                int position, float positionOffset, int positionOffsetPixels) {
        }

        @Override
        public void onPageSelected(int position) {
            if (DEBUG) Log.d(TAG, "onPageSelected: position: " + position);
            final ActionBar actionBar = getActionBar();
            if (mDialpadFragment != null) {
                if (mDuringSwipe && position == TAB_INDEX_DIALER) {
                    // TODO: Figure out if we want this or not. Right now
                    // - with this call, both fake buttons and real action bar overlap
                    // - without this call, there's tiny flicker happening to search/menu buttons.
                    // If we can reduce the flicker without this call, it would be much better.
                    // updateFakeMenuButtonsVisibility(true);
                }
            }

            if (mCurrentPosition == position) {
                Log.w(TAG, "Previous position and next position became same (" + position + ")");
            }

            actionBar.selectTab(actionBar.getTabAt(position));
            mNextPosition = position;
        }

        public void setCurrentPosition(int position) {
            mCurrentPosition = position;
        }

        public int getCurrentPosition() {
            return mCurrentPosition;
        }

        @Override
        public void onPageScrollStateChanged(int state) {
            switch (state) {
                case ViewPager.SCROLL_STATE_IDLE: {
                    if (mNextPosition == -1) {
                        // This happens when the user drags the screen just after launching the
                        // application, and settle down the same screen without actually swiping it.
                        // At that moment mNextPosition is apparently -1 yet, and we expect it
                        // being updated by onPageSelected(), which is *not* called if the user
                        // settle down the exact same tab after the dragging.
                        if (DEBUG) {
                            Log.d(TAG, "Next position is not specified correctly. Use current tab ("
                                    + mViewPager.getCurrentItem() + ")");
                        }
                        mNextPosition = mViewPager.getCurrentItem();
                    }
                    if (DEBUG) {
                        Log.d(TAG, "onPageScrollStateChanged() with SCROLL_STATE_IDLE. "
                                + "mCurrentPosition: " + mCurrentPosition
                                + ", mNextPosition: " + mNextPosition);
                    }
                    // Interpret IDLE as the end of migration (both swipe and tab click)
                    mDuringSwipe = false;
                    mUserTabClick = false;

                    updateFakeMenuButtonsVisibility(mNextPosition == TAB_INDEX_DIALER);
                    sendFragmentVisibilityChange(mCurrentPosition, false);
                    sendFragmentVisibilityChange(mNextPosition, true);

                    invalidateOptionsMenu();

                    mCurrentPosition = mNextPosition;
                    if (mCurrentPosition == TAB_INDEX_DIALER && !mDialpadClingShowed
                            && canShowDialpadCling()) {
                        ((SmartDialpadFragment)mDialpadFragment).showFirstRunDialpadCling();
                    }
                    break;
                }
                case ViewPager.SCROLL_STATE_DRAGGING: {
                    if (DEBUG) Log.d(TAG, "onPageScrollStateChanged() with SCROLL_STATE_DRAGGING");
                    mDuringSwipe = true;
                    mUserTabClick = false;
                    break;
                }
                case ViewPager.SCROLL_STATE_SETTLING: {
                    if (DEBUG) Log.d(TAG, "onPageScrollStateChanged() with SCROLL_STATE_SETTLING");
                    mDuringSwipe = true;
                    mUserTabClick = false;
                    break;
                }
                default:
                    break;
            }
        }
    }

    private String mFilterText;

    /** Enables horizontal swipe between Fragments. */
    public CustomViewPager mViewPager;
    public final PageChangeListener mPageChangeListener = new PageChangeListener();
    private DialpadFragment mDialpadFragment;
    private CallLogFragment mCallLogFragment;
    private PhoneFavoriteFragment mPhoneFavoriteFragment;

    private View mSearchButton;
    private View mMenuButton;

    private final ContactListFilterListener mContactListFilterListener =
            new ContactListFilterListener() {
        @Override
        public void onContactListFilterChanged() {
            boolean doInvalidateOptionsMenu = false;

            if (mPhoneFavoriteFragment != null && mPhoneFavoriteFragment.isAdded()) {
                mPhoneFavoriteFragment.setFilter(mContactListFilterController.getFilter());
                doInvalidateOptionsMenu = true;
            }

            if (mSearchFragment != null && mSearchFragment.isAdded()) {
                mSearchFragment.setFilter(mContactListFilterController.getFilter());
                doInvalidateOptionsMenu = true;
            } else {
                Log.w(TAG, "Search Fragment isn't available when ContactListFilter is changed");
            }

            if (doInvalidateOptionsMenu) {
                invalidateOptionsMenu();
            }
        }
    };

    private final TabListener mTabListener = new TabListener() {
        @Override
        public void onTabUnselected(Tab tab, FragmentTransaction ft) {
            if (DEBUG) Log.d(TAG, "onTabUnselected(). tab: " + tab);
        }

        @Override
        public void onTabSelected(Tab tab, FragmentTransaction ft) {
            if (DEBUG) {
                Log.d(TAG, "onTabSelected(). tab: " + tab + ", mDuringSwipe: " + mDuringSwipe);
            }
            // When the user swipes the screen horizontally, this method will be called after
            // ViewPager.SCROLL_STATE_DRAGGING and ViewPager.SCROLL_STATE_SETTLING events, while
            // when the user clicks a tab at the ActionBar at the top, this will be called before
            // them. This logic interprets the order difference as a difference of the user action.
            if (!mDuringSwipe) {
                if (DEBUG) {
                    Log.d(TAG, "Tab select. from: " + mPageChangeListener.getCurrentPosition()
                            + ", to: " + tab.getPosition());
                }
                if (mDialpadFragment != null) {
                    updateFakeMenuButtonsVisibility(tab.getPosition() == TAB_INDEX_DIALER);
                }
                mUserTabClick = true;
            }

            if (mViewPager.getCurrentItem() != tab.getPosition()) {
                mViewPager.setCurrentItem(tab.getPosition(), true);
            }

            // During the call, we don't remember the tab position.
            if (!DialpadFragment.phoneIsInUse()) {
                // Remember this tab index. This function is also called, if the tab is set
                // automatically in which case the setter (setCurrentTab) has to set this to its old
                // value afterwards
                mLastManuallySelectedFragment = tab.getPosition();
            }
        }

        @Override
        public void onTabReselected(Tab tab, FragmentTransaction ft) {
            if (DEBUG) Log.d(TAG, "onTabReselected");
        }
    };

    /**
     * Fragment for searching phone numbers. Unlike the other Fragments, this doesn't correspond
     * to tab but is shown by a search action.
     */
    private PhoneNumberPickerFragment mSearchFragment;
    /**
     * True when this Activity is in its search UI (with a {@link SearchView} and
     * {@link PhoneNumberPickerFragment}).
     */
    public boolean mInSearchUi;
    private SearchView mSearchView;

    private final OnClickListener mFilterOptionClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            final PopupMenu popupMenu = new PopupMenu(DialtactsActivity.this, view);
            final Menu menu = popupMenu.getMenu();
            popupMenu.inflate(R.menu.dialtacts_search_options);
            final MenuItem filterOptionMenuItem = menu.findItem(R.id.filter_option);
            filterOptionMenuItem.setOnMenuItemClickListener(mFilterOptionsMenuItemClickListener);
            final MenuItem addContactOptionMenuItem = menu.findItem(R.id.add_contact);
            addContactOptionMenuItem.setIntent(
                    new Intent(Intent.ACTION_INSERT, Contacts.CONTENT_URI));
            popupMenu.show();
        }
    };

    /**
     * The index of the Fragment (or, the tab) that has last been manually selected.
     * This value does not keep track of programmatically set Tabs (e.g. Call Log after a Call)
     */
    private int mLastManuallySelectedFragment;

    private ContactListFilterController mContactListFilterController;
    private OnMenuItemClickListener mFilterOptionsMenuItemClickListener =
            new OnMenuItemClickListener() {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            AccountFilterUtil.startAccountFilterActivityForResult(
                    DialtactsActivity.this, SUBACTIVITY_ACCOUNT_FILTER,
                    mContactListFilterController.getFilter());
            return true;
        }
    };

    private OnMenuItemClickListener mSearchMenuItemClickListener =
            new OnMenuItemClickListener() {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            enterSearchUi();
            return true;
        }
    };

    /**
     * Listener used when one of phone numbers in search UI is selected. This will initiate a
     * phone call using the phone number.
     */
    private final OnPhoneNumberPickerActionListener mPhoneNumberPickerActionListener =
            new OnPhoneNumberPickerActionListener() {
                @Override
                public void onPickPhoneNumberAction(Uri dataUri) {
                    // Specify call-origin so that users will see the previous tab instead of
                    // CallLog screen (search UI will be automatically exited).
                    PhoneNumberInteraction.startInteractionForPhoneCall(
                            DialtactsActivity.this, dataUri, getCallOrigin());
                }

                @Override
                public void onShortcutIntentCreated(Intent intent) {
                    Log.w(TAG, "Unsupported intent has come (" + intent + "). Ignoring.");
                }

                @Override
                public void onHomeInActionBarSelected() {
                    exitSearchUi();
                }
    };

    /**
     * Listener used to send search queries to the phone search fragment.
     */
    private final OnQueryTextListener mPhoneSearchQueryTextListener =
            new OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    View view = getCurrentFocus();
                    if (view != null) {
                        hideInputMethod(view);
                        view.clearFocus();
                    }
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    // Show search result with non-empty text. Show a bare list otherwise.
                    if (mSearchFragment != null) {
                        mSearchFragment.setQueryString(newText, true);
                    }
                    return true;
                }
    };

    /**
     * Listener used to handle the "close" button on the right side of {@link SearchView}.
     * If some text is in the search view, this will clean it up. Otherwise this will exit
     * the search UI and let users go back to usual Phone UI.
     *
     * This does _not_ handle back button.
     */
    private final OnCloseListener mPhoneSearchCloseListener =
            new OnCloseListener() {
                @Override
                public boolean onClose() {
                    if (!TextUtils.isEmpty(mSearchView.getQuery())) {
                        mSearchView.setQuery(null, true);
                    }
                    return true;
                }
    };

    private final View.OnLayoutChangeListener mFirstLayoutListener
            = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            v.removeOnLayoutChangeListener(this); // Unregister self.
            addSearchFragment();
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Intent intent = getIntent();
        fixIntent(intent);

        setContentView(R.layout.dialtacts_activity);

        mContactListFilterController = ContactListFilterController.getInstance(this);
        mContactListFilterController.addListener(mContactListFilterListener);

        findViewById(R.id.dialtacts_frame).addOnLayoutChangeListener(mFirstLayoutListener);

        mViewPager = (CustomViewPager) findViewById(R.id.pager);
        mViewPager.setAdapter(new ViewPagerAdapter(getFragmentManager()));
        mViewPager.setOnPageChangeListener(mPageChangeListener);
        mViewPager.setOffscreenPageLimit(2);

        // Do same width calculation as ActionBar does
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int minCellSize = getResources().getDimensionPixelSize(R.dimen.fake_menu_button_min_width);
        int cellCount = dm.widthPixels / minCellSize;
        int fakeMenuItemWidth = dm.widthPixels / cellCount;
        if (DEBUG) Log.d(TAG, "The size of fake menu buttons (in pixel): " + fakeMenuItemWidth);

        // Soft menu button should appear only when there's no hardware menu button.
        mMenuButton = findViewById(R.id.overflow_menu);
        if (mMenuButton != null) {
            mMenuButton.setMinimumWidth(fakeMenuItemWidth);
            if (ViewConfiguration.get(this).hasPermanentMenuKey()) {
                // This is required for dialpad button's layout, so must not use GONE here.
                mMenuButton.setVisibility(View.INVISIBLE);
            } else {
                mMenuButton.setOnClickListener(this);
            }
        }
        mSearchButton = findViewById(R.id.searchButton);
        if (mSearchButton != null) {
            mSearchButton.setMinimumWidth(fakeMenuItemWidth);
            mSearchButton.setOnClickListener(this);
        }

        // Setup the ActionBar tabs (the order matches the tab-index contants TAB_INDEX_*)
        setupDialer();
        setupCallLog();
        setupFavorites();
        getActionBar().setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
        getActionBar().setDisplayShowTitleEnabled(false);
        getActionBar().setDisplayShowHomeEnabled(false);

        // Load the last manually loaded tab
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mLastManuallySelectedFragment = mPrefs.getInt(PREF_LAST_MANUALLY_SELECTED_TAB,
                PREF_LAST_MANUALLY_SELECTED_TAB_DEFAULT);
        if (mLastManuallySelectedFragment >= TAB_INDEX_COUNT) {
            // Stored value may have exceeded the number of current tabs. Reset it.
            mLastManuallySelectedFragment = PREF_LAST_MANUALLY_SELECTED_TAB_DEFAULT;
        }

        setCurrentTab(intent);

        if (UI.FILTER_CONTACTS_ACTION.equals(intent.getAction())
                && icicle == null) {
            setupFilterText(intent);
        }

        final IntentFilter intentFilter = new IntentFilter(
                TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        final IntentFilter exportCompleteFilter = new IntentFilter(SimContactsConstants
                .INTENT_EXPORT_COMPLETE);
        registerReceiver(mReceiver, intentFilter);
        registerReceiver(mExportToSimCompleteListener, exportCompleteFilter);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mPhoneFavoriteFragment != null) {
            mPhoneFavoriteFragment.setFilter(mContactListFilterController.getFilter());
        }
        if (mSearchFragment != null) {
            mSearchFragment.setFilter(mContactListFilterController.getFilter());
        }

        if (mDuringSwipe || mUserTabClick) {
            if (DEBUG) Log.d(TAG, "reset buggy flag state..");
            mDuringSwipe = false;
            mUserTabClick = false;
        }

        final int currentPosition = mPageChangeListener.getCurrentPosition();
        if (DEBUG) {
            Log.d(TAG, "onStart(). current position: " + mPageChangeListener.getCurrentPosition()
                    + ". Reset all menu visibility state.");
        }
        updateFakeMenuButtonsVisibility(currentPosition == TAB_INDEX_DIALER && !mInSearchUi);
        for (int i = 0; i < TAB_INDEX_COUNT; i++) {
            sendFragmentVisibilityChange(i, i == currentPosition && !mInSearchUi);
        }
        if(ACTION_SEARCH.equals(this.getIntent().getAction())){
            Intent intent = new Intent(this, CallDetailActivity.class);
            String id = "0";
            if (this.getIntent().getData() != null) {
                id = this.getIntent().getData().getQueryParameter("id");
            }
            intent.putExtra(CallDetailActivity.EXTRA_CALL_LOG_IDS, new long[]{Long.decode(id)});
            startActivity(intent);
            finish();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mContactListFilterController != null) {
            mContactListFilterController.removeListener(mContactListFilterListener);
        }
        unregisterReceiver(mReceiver);
        unregisterReceiver(mExportToSimCompleteListener);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.searchButton: {
                enterSearchUi();
                break;
            }
            case R.id.overflow_menu: {
                if (mDialpadFragment != null) {
                    PopupMenu popup = mDialpadFragment.constructPopupMenu(view);
                    if (popup != null) {
                        popup.show();
                    }
                } else {
                    Log.w(TAG, "DialpadFragment is null during onClick() event for " + view);
                }
                break;
            }
            default: {
                Log.wtf(TAG, "Unexpected onClick event from " + view);
                break;
            }
        }
    }

    /**
     * Add search fragment.  Note this is called during onLayout, so there's some restrictions,
     * such as executePendingTransaction can't be used in it.
     */
    private void addSearchFragment() {
        // In order to take full advantage of "fragment deferred start", we need to create the
        // search fragment after all other fragments are created.
        // The other fragments are created by the ViewPager on the first onMeasure().
        // We use the first onLayout call, which is after onMeasure().

        // Just return if the fragment is already created, which happens after configuration
        // changes.
        if (mSearchFragment != null) return;

        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        final Fragment searchFragment = new PhoneNumberPickerFragment();

        searchFragment.setUserVisibleHint(false);
        ft.add(R.id.dialtacts_frame, searchFragment);
        ft.hide(searchFragment);
        ft.commitAllowingStateLoss();
    }

    private void prepareSearchView() {
        final View searchViewLayout =
                getLayoutInflater().inflate(R.layout.dialtacts_custom_action_bar, null);
        mSearchView = (SearchView) searchViewLayout.findViewById(R.id.search_view);
        mSearchView.setOnQueryTextListener(mPhoneSearchQueryTextListener);
        mSearchView.setOnCloseListener(mPhoneSearchCloseListener);
        // Since we're using a custom layout for showing SearchView instead of letting the
        // search menu icon do that job, we need to manually configure the View so it looks
        // "shown via search menu".
        // - it should be iconified by default
        // - it should not be iconified at this time
        // See also comments for onActionViewExpanded()/onActionViewCollapsed()
        mSearchView.setIconifiedByDefault(true);
        mSearchView.setQueryHint(getString(R.string.hint_findContacts));
        mSearchView.setIconified(false);
        mSearchView.setOnQueryTextFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) {
                    showInputMethod(view.findFocus());
                }
            }
        });

        if (!ViewConfiguration.get(this).hasPermanentMenuKey()) {
            // Filter option menu should be shown on the right side of SearchView.
            final View filterOptionView = searchViewLayout.findViewById(R.id.search_option);
            filterOptionView.setVisibility(View.VISIBLE);
            filterOptionView.setOnClickListener(mFilterOptionClickListener);
        }

        getActionBar().setCustomView(searchViewLayout,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public boolean canShowDialpadCling() {
        if (!SystemProperties.getBoolean("persist.env.phone.smartdialer", true)) {
            return false;
        }
        if (DialpadFragment.phoneIsInUse()) {
            return false;
        }
        if (!MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            return false;
        }

        int phoneCount = MSimTelephonyManager.getDefault().getPhoneCount();
        for (int i = 0; i < phoneCount; i++) {
            if (!isValidSimState(i))
                return false;
        }
        return true;
    }

    public void dismissDialpadCling(View v) {
        DialpadCling DialpadCling = (DialpadCling) findViewById(R.id.dialpad_cling);
        dismissCling(DialpadCling, DialpadCling.DIALPAD_CLING_DISMISSED_KEY,
                SmartDialpadFragment.DISMISS_CLING_DURATION);
    }

    public void dismissCling(final DialpadCling DialpadCling, final String flag, int duration) {
        if (DialpadCling != null) {
            ObjectAnimator anim = ObjectAnimator.ofFloat(DialpadCling, "alpha", 0f);
            anim.setDuration(duration);
            anim.addListener(new AnimatorListenerAdapter() {
                public void onAnimationEnd(Animator animation) {
                    DialpadCling.setVisibility(View.GONE);
                    DialpadCling.cleanup();
                    // We should update the shared preferences on a background
                    // thread
                    new Thread("dismissClingThread") {
                        public void run() {
                            SharedPreferences.Editor editor = mPrefs.edit();
                            editor.putBoolean(flag, true);
                            editor.commit();
                        }
                    }.start();
                };
            });
            anim.start();
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action) ||
                    Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                final int currentPosition = mPageChangeListener.getCurrentPosition();
                if (currentPosition == TAB_INDEX_DIALER && !mDialpadClingShowed
                        && canShowDialpadCling() && null != mDialpadFragment) {
                    ((SmartDialpadFragment)mDialpadFragment).showFirstRunDialpadCling();
                }
            }
        }
    };

    @Override
    public void onAttachFragment(Fragment fragment) {
        // This method can be called before onCreate(), at which point we cannot rely on ViewPager.
        // In that case, we will setup the "current position" soon after the ViewPager is ready.
        final int currentPosition = mViewPager != null ? mViewPager.getCurrentItem() : -1;

        if (fragment instanceof DialpadFragment) {
            mDialpadFragment = (DialpadFragment) fragment;
        } else if (fragment instanceof CallLogFragment) {
            mCallLogFragment = (CallLogFragment) fragment;
        } else if (fragment instanceof PhoneFavoriteFragment) {
            mPhoneFavoriteFragment = (PhoneFavoriteFragment) fragment;
            mPhoneFavoriteFragment.setListener(mPhoneFavoriteListener);
            if (mContactListFilterController != null
                    && mContactListFilterController.getFilter() != null) {
                mPhoneFavoriteFragment.setFilter(mContactListFilterController.getFilter());
            }
        } else if (fragment instanceof PhoneNumberPickerFragment) {
            mSearchFragment = (PhoneNumberPickerFragment) fragment;
            mSearchFragment.setOnPhoneNumberPickerActionListener(mPhoneNumberPickerActionListener);
            mSearchFragment.setQuickContactEnabled(true);
            mSearchFragment.setDarkTheme(true);
            mSearchFragment.setPhotoPosition(ContactListItemView.getDefaultPhotoPosition(
                    true /* opposite */));
            mSearchFragment.setUseCallableUri(true);
            if (mContactListFilterController != null
                    && mContactListFilterController.getFilter() != null) {
                mSearchFragment.setFilter(mContactListFilterController.getFilter());
            }
            // Here we assume that we're not on the search mode, so let's hide the fragment.
            //
            // We get here either when the fragment is created (normal case), or after configuration
            // changes.  In the former case, we're not in search mode because we can only
            // enter search mode if the fragment is created.  (see enterSearchUi())
            // In the latter case we're not in search mode either because we don't retain
            // mInSearchUi -- ideally we should but at this point it's not supported.
            mSearchFragment.setUserVisibleHint(false);
            // After configuration changes fragments will forget their "hidden" state, so make
            // sure to hide it.
            if (!mSearchFragment.isHidden()) {
                final FragmentTransaction transaction = getFragmentManager().beginTransaction();
                transaction.hide(mSearchFragment);
                transaction.commitAllowingStateLoss();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        mPrefs.edit().putInt(PREF_LAST_MANUALLY_SELECTED_TAB, mLastManuallySelectedFragment)
                .apply();
    }

    private void fixIntent(Intent intent) {
        // This should be cleaned up: the call key used to send an Intent
        // that just said to go to the recent calls list.  It now sends this
        // abstract action, but this class hasn't been rewritten to deal with it.
        if (Intent.ACTION_CALL_BUTTON.equals(intent.getAction())) {
            intent.setDataAndType(Calls.CONTENT_URI, Calls.CONTENT_TYPE);
            intent.putExtra("call_key", true);
            setIntent(intent);
        }
    }

    private void setupDialer() {
        final Tab tab = getActionBar().newTab();
        tab.setContentDescription(R.string.dialerIconLabel);
        tab.setTabListener(mTabListener);
        tab.setIcon(R.drawable.ic_tab_dialer);
        getActionBar().addTab(tab);
    }

    private void setupCallLog() {
        final Tab tab = getActionBar().newTab();
        tab.setContentDescription(R.string.recentCallsIconLabel);
        tab.setIcon(R.drawable.ic_tab_recent);
        tab.setTabListener(mTabListener);
        getActionBar().addTab(tab);
    }

    private void setupFavorites() {
        final Tab tab = getActionBar().newTab();
        tab.setContentDescription(R.string.contactsFavoritesLabel);
        tab.setIcon(R.drawable.ic_tab_all);
        tab.setTabListener(mTabListener);
        getActionBar().addTab(tab);
    }

    /**
     * Returns true if the intent is due to hitting the green send key (hardware call button:
     * KEYCODE_CALL) while in a call.
     *
     * @param intent the intent that launched this activity
     * @param recentCallsRequest true if the intent is requesting to view recent calls
     * @return true if the intent is due to hitting the green send key while in a call
     */
    private boolean isSendKeyWhileInCall(final Intent intent,
            final boolean recentCallsRequest) {
        // If there is a call in progress go to the call screen
        if (recentCallsRequest) {
            final boolean callKey = intent.getBooleanExtra("call_key", false);

            try {
                ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
                if (callKey && phone != null && phone.showCallScreen()) {
                    return true;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to handle send while in call", e);
            }
        }

        return false;
    }

    /**
     * Sets the current tab based on the intent's request type
     *
     * @param intent Intent that contains information about which tab should be selected
     */
    private void setCurrentTab(Intent intent) {
        // If we got here by hitting send and we're in call forward along to the in-call activity
        boolean recentCallsRequest = Calls.CONTENT_TYPE.equals(intent.resolveType(
            getContentResolver()));
        if (isSendKeyWhileInCall(intent, recentCallsRequest)) {
            finish();
            return;
        }

        // Remember the old manually selected tab index so that it can be restored if it is
        // overwritten by one of the programmatic tab selections
        final int savedTabIndex = mLastManuallySelectedFragment;

        final int tabIndex;
        if (DialpadFragment.phoneIsInUse() || isDialIntent(intent)) {
            tabIndex = TAB_INDEX_DIALER;
        } else if (recentCallsRequest) {
            tabIndex = TAB_INDEX_CALL_LOG;
        } else {
            tabIndex = mLastManuallySelectedFragment;
        }

        final int previousItemIndex = mViewPager.getCurrentItem();
        mViewPager.setCurrentItem(tabIndex, false /* smoothScroll */);
        if (previousItemIndex != tabIndex) {
            sendFragmentVisibilityChange(previousItemIndex, false /* not visible */ );
        }
        mPageChangeListener.setCurrentPosition(tabIndex);
        sendFragmentVisibilityChange(tabIndex, true /* visible */ );

        // Restore to the previous manual selection
        mLastManuallySelectedFragment = savedTabIndex;
        mDuringSwipe = false;
        mUserTabClick = false;
    }

    @Override
    public void onNewIntent(Intent newIntent) {
        setIntent(newIntent);
        fixIntent(newIntent);
        setCurrentTab(newIntent);
        final String action = newIntent.getAction();
        if (UI.FILTER_CONTACTS_ACTION.equals(action)) {
            setupFilterText(newIntent);
        }
        if (mInSearchUi || (mSearchFragment != null && mSearchFragment.isVisible())) {
            exitSearchUi();
        }

        if (mViewPager.getCurrentItem() == TAB_INDEX_DIALER) {
            if (mDialpadFragment != null) {
                mDialpadFragment.setStartedFromNewIntent(true);
            } else {
                Log.e(TAG, "DialpadFragment isn't ready yet when the tab is already selected.");
            }
        } else if (mViewPager.getCurrentItem() == TAB_INDEX_CALL_LOG) {
            if (mCallLogFragment != null) {
                mCallLogFragment.configureScreenFromIntent(newIntent);
            } else {
                Log.e(TAG, "CallLogFragment isn't ready yet when the tab is already selected.");
            }
        }
        invalidateOptionsMenu();
    }

    /** Returns true if the given intent contains a phone number to populate the dialer with */
    private boolean isDialIntent(Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_DIAL.equals(action) || ACTION_TOUCH_DIALER.equals(action)) {
            return true;
        }
        if (Intent.ACTION_VIEW.equals(action)) {
            final Uri data = intent.getData();
            if (data != null && CallUtil.SCHEME_TEL.equals(data.getScheme())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an appropriate call origin for this Activity. May return null when no call origin
     * should be used (e.g. when some 3rd party application launched the screen. Call origin is
     * for remembering the tab in which the user made a phone call, so the external app's DIAL
     * request should not be counted.)
     */
    public String getCallOrigin() {
        return !isDialIntent(getIntent()) ? CALL_ORIGIN_DIALTACTS : null;
    }

    /**
     * Retrieves the filter text stored in {@link #setupFilterText(Intent)}.
     * This text originally came from a FILTER_CONTACTS_ACTION intent received
     * by this activity. The stored text will then be cleared after after this
     * method returns.
     *
     * @return The stored filter text
     */
    public String getAndClearFilterText() {
        String filterText = mFilterText;
        mFilterText = null;
        return filterText;
    }

    /**
     * Stores the filter text associated with a FILTER_CONTACTS_ACTION intent.
     * This is so child activities can check if they are supposed to display a filter.
     *
     * @param intent The intent received in {@link #onNewIntent(Intent)}
     */
    private void setupFilterText(Intent intent) {
        // If the intent was relaunched from history, don't apply the filter text.
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return;
        }
        String filter = intent.getStringExtra(UI.FILTER_TEXT_EXTRA_KEY);
        if (filter != null && filter.length() > 0) {
            mFilterText = filter;
        }
    }

    @Override
    public void onBackPressed() {
        if (mInSearchUi) {
            // We should let the user go back to usual screens with tabs.
            exitSearchUi();
        } else if (isTaskRoot()) {
            // Instead of stopping, simply push this to the back of the stack.
            // This is only done when running at the top of the stack;
            // otherwise, we have been launched by someone else so need to
            // allow the user to go back to the caller.
            moveTaskToBack(false);
        } else {
            super.onBackPressed();
        }
    }

    private final PhoneFavoriteFragment.Listener mPhoneFavoriteListener =
            new PhoneFavoriteFragment.Listener() {
        @Override
        public void onContactSelected(Uri contactUri) {
            PhoneNumberInteraction.startInteractionForPhoneCall(
                    DialtactsActivity.this, contactUri, getCallOrigin());
        }

        @Override
        public void onCallNumberDirectly(String phoneNumber) {
            Intent intent = CallUtil.getCallIntent(phoneNumber, getCallOrigin());
            startActivity(intent);
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.dialtacts_options, menu);

        // set up intents and onClick listeners
        final MenuItem callSettingsMenuItem = menu.findItem(R.id.menu_call_settings);
        final MenuItem searchMenuItem = menu.findItem(R.id.search_on_action_bar);
        final MenuItem filterOptionMenuItem = menu.findItem(R.id.filter_option);

        callSettingsMenuItem.setIntent(DialtactsActivity.getCallSettingsIntent());
        searchMenuItem.setOnMenuItemClickListener(mSearchMenuItemClickListener);
        filterOptionMenuItem.setOnMenuItemClickListener(mFilterOptionsMenuItemClickListener);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mInSearchUi) {
            prepareOptionsMenuInSearchMode(menu);
        } else {
            // get reference to the currently selected tab
            final Tab tab = getActionBar().getSelectedTab();
            if (tab != null) {
                switch(tab.getPosition()) {
                    case TAB_INDEX_DIALER:
                        prepareOptionsMenuForDialerTab(menu);
                        break;
                    case TAB_INDEX_CALL_LOG:
                        prepareOptionsMenuForCallLogTab(menu);
                        break;
                    case TAB_INDEX_FAVORITES:
                        prepareOptionsMenuForFavoritesTab(menu);
                        break;
                }
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add_contact:
                try {
                    startActivity(new Intent(Intent.ACTION_INSERT, Contacts.CONTENT_URI));
                } catch (ActivityNotFoundException e) {
                    Toast toast = Toast.makeText(this, R.string.add_contact_not_available,
                            Toast.LENGTH_SHORT);
                    toast.show();
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void prepareOptionsMenuInSearchMode(Menu menu) {
        // get references to menu items
        final MenuItem searchMenuItem = menu.findItem(R.id.search_on_action_bar);
        final MenuItem filterOptionMenuItem = menu.findItem(R.id.filter_option);
        final MenuItem addContactOptionMenuItem = menu.findItem(R.id.add_contact);
        final MenuItem callSettingsMenuItem = menu.findItem(R.id.menu_call_settings);
        final MenuItem emptyRightMenuItem = menu.findItem(R.id.empty_right_menu_item);

        // prepare the menu items
        searchMenuItem.setVisible(false);
        filterOptionMenuItem.setVisible(ViewConfiguration.get(this).hasPermanentMenuKey());
        addContactOptionMenuItem.setVisible(false);
        callSettingsMenuItem.setVisible(false);
        emptyRightMenuItem.setVisible(false);
    }

    private void prepareOptionsMenuForDialerTab(Menu menu) {
        if (DEBUG) {
            Log.d(TAG, "onPrepareOptionsMenu(dialer). swipe: " + mDuringSwipe
                    + ", user tab click: " + mUserTabClick);
        }

        // get references to menu items
        final MenuItem searchMenuItem = menu.findItem(R.id.search_on_action_bar);
        final MenuItem filterOptionMenuItem = menu.findItem(R.id.filter_option);
        final MenuItem addContactOptionMenuItem = menu.findItem(R.id.add_contact);
        final MenuItem callSettingsMenuItem = menu.findItem(R.id.menu_call_settings);
        final MenuItem emptyRightMenuItem = menu.findItem(R.id.empty_right_menu_item);

        // prepare the menu items
        filterOptionMenuItem.setVisible(false);
        addContactOptionMenuItem.setVisible(false);
        if (mDuringSwipe || mUserTabClick) {
            // During horizontal movement, the real ActionBar menu items are shown
            searchMenuItem.setVisible(true);
            callSettingsMenuItem.setVisible(true);
            // When there is a permanent menu key, there is no overflow icon on the right of
            // the action bar which would force the search menu item (if it is visible) to the
            // left.  This is the purpose of showing the emptyRightMenuItem.
            emptyRightMenuItem.setVisible(ViewConfiguration.get(this).hasPermanentMenuKey());
        } else {
            // This is when the user is looking at the dialer pad.  In this case, the real
            // ActionBar is hidden and fake menu items are shown.
            // Except in landscape, in which case the real search menu item is shown.
            searchMenuItem.setVisible(OrientationUtil.isLandscape(this));
            // If a permanent menu key is available, then we need to show the call settings item
            // so that the call settings item can be invoked by the permanent menu key.
            callSettingsMenuItem.setVisible(ViewConfiguration.get(this).hasPermanentMenuKey());
            emptyRightMenuItem.setVisible(false);
        }
    }

    private void prepareOptionsMenuForCallLogTab(Menu menu) {
        // get references to menu items
        final MenuItem searchMenuItem = menu.findItem(R.id.search_on_action_bar);
        final MenuItem filterOptionMenuItem = menu.findItem(R.id.filter_option);
        final MenuItem addContactOptionMenuItem = menu.findItem(R.id.add_contact);
        final MenuItem callSettingsMenuItem = menu.findItem(R.id.menu_call_settings);
        final MenuItem emptyRightMenuItem = menu.findItem(R.id.empty_right_menu_item);

        // prepare the menu items
        searchMenuItem.setVisible(true);
        filterOptionMenuItem.setVisible(false);
        addContactOptionMenuItem.setVisible(false);
        callSettingsMenuItem.setVisible(true);
        emptyRightMenuItem.setVisible(ViewConfiguration.get(this).hasPermanentMenuKey());
    }

    private void prepareOptionsMenuForFavoritesTab(Menu menu) {
        // get references to menu items
        final MenuItem searchMenuItem = menu.findItem(R.id.search_on_action_bar);
        final MenuItem filterOptionMenuItem = menu.findItem(R.id.filter_option);
        final MenuItem addContactOptionMenuItem = menu.findItem(R.id.add_contact);
        final MenuItem callSettingsMenuItem = menu.findItem(R.id.menu_call_settings);
        final MenuItem emptyRightMenuItem = menu.findItem(R.id.empty_right_menu_item);

        // prepare the menu items
        searchMenuItem.setVisible(true);
        filterOptionMenuItem.setVisible(true);
        addContactOptionMenuItem.setVisible(true);
        callSettingsMenuItem.setVisible(true);
        emptyRightMenuItem.setVisible(false);
    }

    @Override
    public void startSearch(String initialQuery, boolean selectInitialQuery,
            Bundle appSearchData, boolean globalSearch) {
        if (mSearchFragment != null && mSearchFragment.isAdded() && !globalSearch) {
            if (mInSearchUi) {
                if (mSearchView.hasFocus()) {
                    showInputMethod(mSearchView.findFocus());
                } else {
                    mSearchView.requestFocus();
                }
            } else {
                enterSearchUi();
            }
        } else {
            super.startSearch(initialQuery, selectInitialQuery, appSearchData, globalSearch);
        }
    }

    /**
     * Hides every tab and shows search UI for phone lookup.
     */
    private void enterSearchUi() {
        if (mSearchFragment == null) {
            // We add the search fragment dynamically in the first onLayoutChange() and
            // mSearchFragment is set sometime later when the fragment transaction is actually
            // executed, which means there's a window when users are able to hit the (physical)
            // search key but mSearchFragment is still null.
            // It's quite hard to handle this case right, so let's just ignore the search key
            // in this case.  Users can just hit it again and it will work this time.
            return;
        }
        if (mSearchView == null) {
            prepareSearchView();
        }

        final ActionBar actionBar = getActionBar();

        final Tab tab = actionBar.getSelectedTab();

        // User can search during the call, but we don't want to remember the status.
        if (tab != null && !DialpadFragment.phoneIsInUse()) {
            mLastManuallySelectedFragment = tab.getPosition();
        }

        mSearchView.setQuery(null, true);

        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);

        updateFakeMenuButtonsVisibility(false);

        for (int i = 0; i < TAB_INDEX_COUNT; i++) {
            sendFragmentVisibilityChange(i, false /* not visible */ );
        }

        // Show the search fragment and hide everything else.
        mSearchFragment.setUserVisibleHint(true);
        final FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.show(mSearchFragment);
        transaction.commitAllowingStateLoss();
        mViewPager.setVisibility(View.GONE);

        // We need to call this and onActionViewCollapsed() manually, since we are using a custom
        // layout instead of asking the search menu item to take care of SearchView.
        mSearchView.onActionViewExpanded();
        mInSearchUi = true;
    }

    private void showInputMethod(View view) {
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            if (!imm.showSoftInput(view, 0)) {
                Log.w(TAG, "Failed to show soft input method.");
            }
        }
    }

    private void hideInputMethod(View view) {
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && view != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Goes back to usual Phone UI with tags. Previously selected Tag and associated Fragment
     * should be automatically focused again.
     */
    private void exitSearchUi() {
        final ActionBar actionBar = getActionBar();

        // Hide the search fragment, if exists.
        if (mSearchFragment != null) {
            mSearchFragment.setUserVisibleHint(false);

            final FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.hide(mSearchFragment);
            transaction.commitAllowingStateLoss();
        }

        // We want to hide SearchView and show Tabs. Also focus on previously selected one.
        actionBar.setDisplayShowCustomEnabled(false);
        actionBar.setDisplayShowHomeEnabled(false);
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        for (int i = 0; i < TAB_INDEX_COUNT; i++) {
            sendFragmentVisibilityChange(i, i == mViewPager.getCurrentItem());
        }

        // Before exiting the search screen, reset swipe state.
        mDuringSwipe = false;
        mUserTabClick = false;

        mViewPager.setVisibility(View.VISIBLE);

        hideInputMethod(getCurrentFocus());

        // Request to update option menu.
        invalidateOptionsMenu();

        // See comments in onActionViewExpanded()
        mSearchView.onActionViewCollapsed();
        mInSearchUi = false;
    }

    private Fragment getFragmentAt(int position) {
        switch (position) {
            case TAB_INDEX_DIALER:
                // would not show Search button in Dialer tab.
                mSearchButton.setVisibility(View.GONE);
                return mDialpadFragment;
            case TAB_INDEX_CALL_LOG:
                return mCallLogFragment;
            case TAB_INDEX_FAVORITES:
                return mPhoneFavoriteFragment;
            default:
                throw new IllegalStateException("Unknown fragment index: " + position);
        }
    }

    private void sendFragmentVisibilityChange(int position, boolean visibility) {
        if (DEBUG) {
            Log.d(TAG, "sendFragmentVisibiltyChange(). position: " + position
                    + ", visibility: " + visibility);
        }
        // Position can be -1 initially. See PageChangeListener.
        if (position >= 0) {
            final Fragment fragment = getFragmentAt(position);
            if (fragment != null) {
                fragment.setMenuVisibility(visibility);
                fragment.setUserVisibleHint(visibility);
            }
        }
    }

    /**
     * Update visibility of the search button and menu button at the bottom.
     * They should be invisible when bottom ActionBar's real items are available, and be visible
     * otherwise.
     *
     * @param visible True when visible.
     */
    public void updateFakeMenuButtonsVisibility(boolean visible) {
        // Note: Landscape mode does not have the fake menu and search buttons.
        if (DEBUG) {
            Log.d(TAG, "updateFakeMenuButtonVisibility(" + visible + ")");
        }

        if (mSearchButton != null) {
            // would not show Search button in Dialer tab.
            if (visible && mPageChangeListener.getCurrentPosition() != TAB_INDEX_DIALER) {
                mSearchButton.setVisibility(View.VISIBLE);
            } else {
                mSearchButton.setVisibility(View.INVISIBLE);
            }
        }
        if (mMenuButton != null) {
            if (visible && !ViewConfiguration.get(this).hasPermanentMenuKey()) {
                mMenuButton.setVisibility(View.VISIBLE);
            } else {
                mMenuButton.setVisibility(View.INVISIBLE);
            }
        }
    }

    /** Returns an Intent to launch Call Settings screen */
    public static Intent getCallSettingsIntent() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            intent.setClassName(PHONE_PACKAGE, MSIM_CALL_SETTINGS_CLASS_NAME);
        } else {
            intent.setClassName(PHONE_PACKAGE, CALL_SETTINGS_CLASS_NAME);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    /**
     * Returns true if the sim state is valid.
     *
     * @param slotId
     * @return
     */
    public static boolean isValidSimState(int slotId) {
        int simState = MSimTelephonyManager.getDefault().getSimState(slotId);
        if (TelephonyManager.SIM_STATE_ABSENT == simState
                || TelephonyManager.SIM_STATE_UNKNOWN == simState) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * @return the SIM name for the special subscription.
     */
    public static String getMultiSimName(Context context, int subscription) {
        if (context == null) {
            // If the context is null, return null.
            return null;
        }

        String name = Settings.System.getString(context.getContentResolver(),
                Settings.System.MULTI_SIM_NAME[subscription]);
        if (TextUtils.isEmpty(name)) {
            return context.getString(R.string.slot_name) + " " + (subscription + 1);
        }
        return name;
    }

    /**
     * @return the SIM icon for the special subscription.
     */
    public static Drawable getMultiSimIcon(Context context, int subscription) {
        if (context == null) {
            // If the context is null, return 0 as no resource found.
            return null;
        }

        TypedArray icons = context.getResources().obtainTypedArray(
                com.android.internal.R.array.sim_icons);
        String simIconIndex = Settings.System.getString(context.getContentResolver(),
                Settings.System.PREFERRED_SIM_ICON_INDEX);
        if (TextUtils.isEmpty(simIconIndex)) {
            return icons.getDrawable(subscription);
        } else {
            String[] indexs = simIconIndex.split(",");
            if (subscription >= indexs.length) {
                return null;
            }
            return icons.getDrawable(Integer.parseInt(indexs[subscription]));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }
        switch (requestCode) {
            case SUBACTIVITY_ACCOUNT_FILTER: {
                AccountFilterUtil.handleAccountFilterResult(
                        mContactListFilterController, resultCode, data);
            }
            break;
            case ImportExportDialogFragment.SUBACTIVITY_MULTI_PICK_CONTACT:
                if (resultCode == RESULT_OK) {
                    mContactList = new ArrayList<String[]>();
                    Bundle b = data.getExtras();
                    Bundle choiceSet = b.getBundle("result");
                    Set<String> set = choiceSet.keySet();
                    Iterator<String> i = set.iterator();
                    while (i.hasNext()) {
                        String contactInfo[] = choiceSet.getStringArray(i.next());
                        mContactList.add(contactInfo);
                    }
                    Log.d(TAG, "return " + mContactList.size() + " contacts");
                    if (!mContactList.isEmpty()) {
                        if (!ImportExportDialogFragment.isExportingToSIM()) {
                            ImportExportDialogFragment.destroyExportToSimThread();
                            mExportThread =
                                new ImportExportDialogFragment().createExportToSimThread(
                                ImportExportDialogFragment.ExportToSimThread.TYPE_SELECT,
                                ImportExportDialogFragment.mExportSub,mContactList,
                                DialtactsActivity.this);
                            mExportThread.start();
                        }
                        else {
                            Log.d(TAG, "ImportExportDialogFragment.SUBACTIVITY_MULTI_PICK_CONTACT");
                        }
                    } else {
                    }
                }
                break;
            case SUBACTIVITY_EXPORT_CONTACTS:
                if (resultCode == RESULT_OK) {
                    Bundle result = data.getExtras().getBundle("result");
                    Set<String> keySet = result.keySet();
                    Iterator<String> it = keySet.iterator();
                    StringBuilder selExportBuilder = new StringBuilder();
                    while (it.hasNext()) {
                        String id = it.next();
                        if (0 != selExportBuilder.length()) {
                            selExportBuilder.append(",");
                        }
                        selExportBuilder.append(id);
                    }
                    selExportBuilder.insert(0, "_id IN (");
                    selExportBuilder.append(")");
                    Intent exportIntent = new Intent(this, ExportVCardActivity.class);
                    exportIntent.putExtra("SelExport", selExportBuilder.toString());
                    exportIntent.putExtra(VCardCommonArguments.ARG_CALLING_ACTIVITY,
                            DialtactsActivity.class.getName());
                    this.startActivity(exportIntent);
                }
                break;
        }
    }
}
