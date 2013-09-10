/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.dialer.calllog;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.ListFragment;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Intents.Insert;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ListView;
import android.widget.TextView;

import com.android.common.io.MoreCloseables;
import com.android.contacts.common.CallUtil;
import com.android.contacts.common.GeoUtil;
import com.android.contacts.common.MoreContactUtils;
import com.android.dialer.R;
import com.android.dialer.util.EmptyLoader;
import com.android.dialer.voicemail.VoicemailStatusHelper;
import com.android.dialer.voicemail.VoicemailStatusHelper.StatusMessage;
import com.android.dialer.voicemail.VoicemailStatusHelperImpl;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.PhoneConstants;
import com.google.common.annotations.VisibleForTesting;

import java.util.List;

/**
 * Displays a list of call log entries.
 */
public class CallLogFragment extends ListFragment
        implements CallLogQueryHandler.Listener, CallLogAdapter.CallFetcher {
    private static final String TAG = "CallLogFragment";
    private Context mContext;

    /**
     * ID of the empty loader to defer other fragments.
     */
    private static final int EMPTY_LOADER_ID = 0;

    protected CallLogAdapter mAdapter;
    protected CallLogQueryHandler mCallLogQueryHandler;

    private boolean mScrollToTop;

    /** Whether there is at least one voicemail source installed. */
    protected boolean mVoicemailSourcesAvailable = false;

    protected VoicemailStatusHelper mVoicemailStatusHelper;
    protected View mStatusMessageView;
    protected TextView mStatusMessageText;
    protected TextView mStatusMessageAction;

    private PhoneNumberHelper mPhoneNumberHelper;
    Resources mResources;
    private String mNumber = null;

    private TextView mFilterStatusView;
    private KeyguardManager mKeyguardManager;

    private boolean mEmptyLoaderRunning;
    private boolean mCallLogFetched;
    private boolean mVoicemailStatusFetched;

    protected final Handler mHandler = new Handler();

    protected TelephonyManager mTelephonyManager;
    protected PhoneStateListener mPhoneStateListener;

    private class CustomContentObserver extends ContentObserver {
        public CustomContentObserver() {
            super(mHandler);
        }
        @Override
        public void onChange(boolean selfChange) {
            mRefreshDataRequired = true;
        }
    }

    private class DataContentObserver extends ContentObserver {
        public DataContentObserver() {
            super(mHandler);
        }
        @Override
        public void onChange(boolean selfChange) {
            if (mAdapter != null) {
                mAdapter.invalidateCache();
            }
        }
    }

    // See issue 6363009
    private final ContentObserver mCallLogObserver = new CustomContentObserver();
    private final ContentObserver mContactsObserver = new CustomContentObserver();
    private final ContentObserver mDataObserver = new DataContentObserver();
    private boolean mRefreshDataRequired = true;

    // Exactly same variable is in Fragment as a package private.
    private boolean mMenuVisible = true;

    // Default to all calls.
    protected int mCallTypeFilter = CallLogQueryHandler.CALL_TYPE_ALL;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        mCallLogQueryHandler = new CallLogQueryHandler(getActivity().getContentResolver(), this);
        mKeyguardManager =
                (KeyguardManager) getActivity().getSystemService(Context.KEYGUARD_SERVICE);
        getActivity().getContentResolver().registerContentObserver(
                CallLog.CONTENT_URI, true, mCallLogObserver);
        getActivity().getContentResolver().registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true, mContactsObserver);
        getActivity().getContentResolver().registerContentObserver(
                ContactsContract.Data.CONTENT_URI, true, mDataObserver);
        setHasOptionsMenu(true);
    }

    /** Called by the CallLogQueryHandler when the list of calls has been fetched or updated. */
    @Override
    public void onCallsFetched(Cursor cursor) {
        if (getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        mAdapter.setLoading(false);
        mAdapter.changeCursor(cursor);
        // This will update the state of the "Clear call log" menu item.
        getActivity().invalidateOptionsMenu();
        if (mScrollToTop) {
            final ListView listView = getListView();
            // The smooth-scroll animation happens over a fixed time period.
            // As a result, if it scrolls through a large portion of the list,
            // each frame will jump so far from the previous one that the user
            // will not experience the illusion of downward motion.  Instead,
            // if we're not already near the top of the list, we instantly jump
            // near the top, and animate from there.
            if (listView.getFirstVisiblePosition() > 5) {
                listView.setSelection(5);
            }
            // Workaround for framework issue: the smooth-scroll doesn't
            // occur if setSelection() is called immediately before.
            mHandler.post(new Runnable() {
               @Override
               public void run() {
                   if (getActivity() == null || getActivity().isFinishing()) {
                       return;
                   }
                   listView.smoothScrollToPosition(0);
               }
            });

            mScrollToTop = false;
        }
        mCallLogFetched = true;
        destroyEmptyLoaderIfAllDataFetched();
    }

    /**
     * Called by {@link CallLogQueryHandler} after a successful query to voicemail status provider.
     */
    @Override
    public void onVoicemailStatusFetched(Cursor statusCursor) {
        if (getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        updateVoicemailStatusMessage(statusCursor);

        int activeSources = mVoicemailStatusHelper.getNumberActivityVoicemailSources(statusCursor);
        setVoicemailSourcesAvailable(activeSources != 0);
        MoreCloseables.closeQuietly(statusCursor);
        mVoicemailStatusFetched = true;
        destroyEmptyLoaderIfAllDataFetched();
    }

    private void destroyEmptyLoaderIfAllDataFetched() {
        if (mCallLogFetched && mVoicemailStatusFetched && mEmptyLoaderRunning) {
            mEmptyLoaderRunning = false;
            getLoaderManager().destroyLoader(EMPTY_LOADER_ID);
        }
    }

    /** Sets whether there are any voicemail sources available in the platform. */
    protected void setVoicemailSourcesAvailable(boolean voicemailSourcesAvailable) {
        if (mVoicemailSourcesAvailable == voicemailSourcesAvailable) return;
        mVoicemailSourcesAvailable = voicemailSourcesAvailable;

        Activity activity = getActivity();
        if (activity != null) {
            // This is so that the options menu content is updated.
            activity.invalidateOptionsMenu();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        View view = inflater.inflate(R.layout.call_log_fragment, container, false);
        mVoicemailStatusHelper = new VoicemailStatusHelperImpl();
        mStatusMessageView = view.findViewById(R.id.voicemail_status);
        mStatusMessageText = (TextView) view.findViewById(R.id.voicemail_status_message);
        mStatusMessageAction = (TextView) view.findViewById(R.id.voicemail_status_action);
        mFilterStatusView = (TextView) view.findViewById(R.id.filter_status);
        return view;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String currentCountryIso = GeoUtil.getCurrentCountryIso(getActivity());
        mAdapter = new CallLogAdapter(getActivity(), this,
                new ContactInfoHelper(getActivity(), currentCountryIso));
        setListAdapter(mAdapter);
        getListView().setItemsCanFocus(true);
        registerForContextMenu(getListView());
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.call_log_context_menu_options, menu);
        final MenuItem ipCallBySlot1MenuItem = menu.findItem(R.id.menu_ip_call_by_slot1);
        final MenuItem ipCallBySlot2MenuItem = menu.findItem(R.id.menu_ip_call_by_slot2);
        final MenuItem editBeforeCallMenuItem = menu.findItem(R.id.menu_edit_before_call);
        final MenuItem sendTextMessageMenuItem = menu.findItem(R.id.menu_send_text_message);
        final MenuItem addToContactMenuItem = menu.findItem(R.id.menu_add_to_contacts);

        AdapterView.AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        mNumber = getValidCallLogNumber(info.position);

        menu.setHeaderTitle(mNumber);

        if (MoreContactUtils.isMultiSimEnable(MSimConstants.SUB1)) {
            String sub1Name = MoreContactUtils.getSimSpnName(MSimConstants.SUB1);
            ipCallBySlot1MenuItem.setTitle(getActivity().getString(
                    com.android.contacts.common.R.string.ip_call_by_slot, sub1Name));
            ipCallBySlot1MenuItem.setVisible(true);
        } else {
            ipCallBySlot1MenuItem.setVisible(false);
        }
        if (MoreContactUtils.isMultiSimEnable(MSimConstants.SUB2)) {
            String sub2Name = MoreContactUtils.getSimSpnName(MSimConstants.SUB2);
            ipCallBySlot2MenuItem.setTitle(getActivity().getString(
                    com.android.contacts.common.R.string.ip_call_by_slot, sub2Name));
            ipCallBySlot2MenuItem.setVisible(true);
        } else {
            ipCallBySlot2MenuItem.setVisible(false);
        }

        mResources = getResources();
        mPhoneNumberHelper = new PhoneNumberHelper(mResources);
        final boolean canPlaceCallsTo = mPhoneNumberHelper.canPlaceCallsTo(mNumber);
        final boolean isVoicemailNumber = mPhoneNumberHelper.isVoicemailNumber(mNumber);
        final boolean isSipNumber = mPhoneNumberHelper.isSipNumber(mNumber);
        if (canPlaceCallsTo && !isSipNumber && !isVoicemailNumber) {
            editBeforeCallMenuItem.setVisible(true);
        } else {
            editBeforeCallMenuItem.setVisible(false);
        }

        if (mPhoneNumberHelper.canSendSmsTo(mNumber)) {
            sendTextMessageMenuItem.setVisible(true);
        } else {
            sendTextMessageMenuItem.setVisible(false);
        }
        String mName = getValidCallLogName(info.position);
        if (mName == null) {
            addToContactMenuItem.setVisible(true);
        } else {
            addToContactMenuItem.setVisible(false);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo menuInfo;
        try {
            menuInfo = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (Exception e) {
            return false;
        }
        mNumber = getValidCallLogNumber(menuInfo.position);

        switch (item.getItemId()) {
            case R.id.menu_ip_call_by_slot1:
                ipCallBySlot(MSimConstants.SUB1, menuInfo.position, mNumber);
                return true;
            case R.id.menu_ip_call_by_slot2:
                ipCallBySlot(MSimConstants.SUB2, menuInfo.position, mNumber);
                return true;
            case R.id.menu_edit_before_call:
                Intent editIntent = new Intent(Intent.ACTION_DIAL, CallUtil.getCallUri(mNumber));
                startActivity(editIntent);
                return true;
            case R.id.menu_send_text_message:
                Intent smsIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                        MoreContactUtils.SMS, mNumber, null));
                startActivity(smsIntent);
                return true;
            case R.id.menu_add_to_contacts:
                final CharSequence digits = (CharSequence) mNumber;
                startActivity(getAddToContactIntent(digits));
                return true;
            default:
                throw new IllegalArgumentException("Unknown menu option " + item.getItemId());
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mContext = activity;
    }

    private void ipCallBySlot(int subscription, int position, String number) {
        if (MoreContactUtils.isIPNumberExist(getActivity(), subscription)) {
            Intent callIntent = new Intent(CallUtil.getCallIntent(number));
            callIntent.putExtra(PhoneConstants.IP_CALL, true);
            callIntent.putExtra(MSimConstants.SUBSCRIPTION_KEY, subscription);
            callIntent.putExtra(MoreContactUtils.DIAL_WIDGET_SWITCHED, subscription);
            startActivity(callIntent);
        } else {
            MoreContactUtils.showNoIPNumberDialog(mContext, subscription);
        }
    }

    private static Intent getAddToContactIntent(CharSequence digits) {
        final Intent addIntent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
        addIntent.setType(Contacts.CONTENT_ITEM_TYPE);
        addIntent.putExtra(Insert.PHONE, digits);
        return addIntent;
    }

    private String getValidCallLogNumber(int position) {
        Cursor cursor = null;
        cursor = (Cursor) mAdapter.getItem(position);
        return cursor != null ? cursor.getString(CallLogQuery.NUMBER) : "";
    }

    private String getValidCallLogName(int position) {
        Cursor cursor = null;
        cursor = (Cursor) mAdapter.getItem(position);
        return cursor != null ? cursor.getString(CallLogQuery.CACHED_NAME) : "";
    }

    /**
     * Based on the new intent, decide whether the list should be configured
     * to scroll up to display the first item.
     */
    public void configureScreenFromIntent(Intent newIntent) {
        // Typically, when switching to the call-log we want to show the user
        // the same section of the list that they were most recently looking
        // at.  However, under some circumstances, we want to automatically
        // scroll to the top of the list to present the newest call items.
        // For example, immediately after a call is finished, we want to
        // display information about that call.
        mScrollToTop = Calls.CONTENT_TYPE.equals(newIntent.getType());
    }

    @Override
    public void onStart() {
        // Start the empty loader now to defer other fragments.  We destroy it when both calllog
        // and the voicemail status are fetched.
        getLoaderManager().initLoader(EMPTY_LOADER_ID, null,
                new EmptyLoader.Callback(getActivity()));
        mEmptyLoaderRunning = true;
        super.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshData();
    }

    private void updateVoicemailStatusMessage(Cursor statusCursor) {
        List<StatusMessage> messages = mVoicemailStatusHelper.getStatusMessages(statusCursor);
        if (messages.size() == 0) {
            mStatusMessageView.setVisibility(View.GONE);
        } else {
            mStatusMessageView.setVisibility(View.VISIBLE);
            // TODO: Change the code to show all messages. For now just pick the first message.
            final StatusMessage message = messages.get(0);
            if (message.showInCallLog()) {
                mStatusMessageText.setText(message.callLogMessageId);
            }
            if (message.actionMessageId != -1) {
                mStatusMessageAction.setText(message.actionMessageId);
            }
            if (message.actionUri != null) {
                mStatusMessageAction.setVisibility(View.VISIBLE);
                mStatusMessageAction.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        getActivity().startActivity(
                                new Intent(Intent.ACTION_VIEW, message.actionUri));
                    }
                });
            } else {
                mStatusMessageAction.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Kill the requests thread
        mAdapter.stopRequestProcessing();
    }

    @Override
    public void onStop() {
        super.onStop();
        updateOnExit();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mAdapter.stopRequestProcessing();
        mAdapter.changeCursor(null);
        getActivity().getContentResolver().unregisterContentObserver(mCallLogObserver);
        getActivity().getContentResolver().unregisterContentObserver(mContactsObserver);
        getActivity().getContentResolver().unregisterContentObserver(mDataObserver);
        unregisterPhoneCallReceiver();
    }

    @Override
    public void fetchCalls() {
        mCallLogQueryHandler.fetchCalls(mCallTypeFilter);
    }

    public void startCallsQuery() {
        mAdapter.setLoading(true);
        mCallLogQueryHandler.fetchCalls(mCallTypeFilter);
    }

    private void startVoicemailStatusQuery() {
        mCallLogQueryHandler.fetchVoicemailStatus();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.call_log_options, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        final MenuItem itemDeleteAll = menu.findItem(R.id.delete_all);
        // Check if all the menu items are inflated correctly. As a shortcut, we assume all
        // menu items are ready if the first item is non-null.
        if (itemDeleteAll != null) {
            itemDeleteAll.setEnabled(mAdapter != null && !mAdapter.isEmpty());

            showAllFilterMenuOptions(menu);
            hideCurrentFilterMenuOption(menu);

            // Only hide if not available.  Let the above calls handle showing.
            if (!mVoicemailSourcesAvailable) {
                menu.findItem(R.id.show_voicemails_only).setVisible(false);
            }
        }
    }

    private void hideCurrentFilterMenuOption(Menu menu) {
        MenuItem item = null;
        switch (mCallTypeFilter) {
            case CallLogQueryHandler.CALL_TYPE_ALL:
                item = menu.findItem(R.id.show_all_calls);
                break;
            case Calls.INCOMING_TYPE:
                item = menu.findItem(R.id.show_incoming_only);
                break;
            case Calls.OUTGOING_TYPE:
                item = menu.findItem(R.id.show_outgoing_only);
                break;
            case Calls.MISSED_TYPE:
                item = menu.findItem(R.id.show_missed_only);
                break;
            case Calls.VOICEMAIL_TYPE:
                menu.findItem(R.id.show_voicemails_only);
                break;
        }
        if (item != null) {
            item.setVisible(false);
        }
    }

    private void showAllFilterMenuOptions(Menu menu) {
        menu.findItem(R.id.show_all_calls).setVisible(true);
        menu.findItem(R.id.show_incoming_only).setVisible(true);
        menu.findItem(R.id.show_outgoing_only).setVisible(true);
        menu.findItem(R.id.show_missed_only).setVisible(true);
        menu.findItem(R.id.show_voicemails_only).setVisible(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.delete_all:
                //ClearCallLogDialog.show(getFragmentManager());
                onDelCallLog();
                return true;

            case R.id.show_outgoing_only:
                // We only need the phone call receiver when there is an active call type filter.
                // Not many people may use the filters so don't register the receiver until now .
                registerPhoneCallReceiver();
                mCallLogQueryHandler.fetchCalls(Calls.OUTGOING_TYPE);
                updateFilterTypeAndHeader(Calls.OUTGOING_TYPE);
                return true;

            case R.id.show_incoming_only:
                registerPhoneCallReceiver();
                mCallLogQueryHandler.fetchCalls(Calls.INCOMING_TYPE);
                updateFilterTypeAndHeader(Calls.INCOMING_TYPE);
                return true;

            case R.id.show_missed_only:
                registerPhoneCallReceiver();
                mCallLogQueryHandler.fetchCalls(Calls.MISSED_TYPE);
                updateFilterTypeAndHeader(Calls.MISSED_TYPE);
                return true;

            case R.id.show_voicemails_only:
                registerPhoneCallReceiver();
                mCallLogQueryHandler.fetchCalls(Calls.VOICEMAIL_TYPE);
                updateFilterTypeAndHeader(Calls.VOICEMAIL_TYPE);
                return true;

            case R.id.show_all_calls:
                // Filter is being turned off, receiver no longer needed.
                unregisterPhoneCallReceiver();
                mCallLogQueryHandler.fetchCalls(CallLogQueryHandler.CALL_TYPE_ALL);
                updateFilterTypeAndHeader(CallLogQueryHandler.CALL_TYPE_ALL);
                return true;

            default:
                return false;
        }
    }

    private void onDelCallLog() {
        Intent intent = new Intent("com.android.contacts.action.MULTI_PICK_CALL");
        startActivity(intent);
    }

    private void updateFilterTypeAndHeader(int filterType) {
        mCallTypeFilter = filterType;

        switch (filterType) {
            case CallLogQueryHandler.CALL_TYPE_ALL:
                mFilterStatusView.setVisibility(View.GONE);
                break;
            case Calls.INCOMING_TYPE:
                showFilterStatus(R.string.call_log_incoming_header);
                break;
            case Calls.OUTGOING_TYPE:
                showFilterStatus(R.string.call_log_outgoing_header);
                break;
            case Calls.MISSED_TYPE:
                showFilterStatus(R.string.call_log_missed_header);
                break;
            case Calls.VOICEMAIL_TYPE:
                showFilterStatus(R.string.call_log_voicemail_header);
                break;
        }
    }

    private void showFilterStatus(int resId) {
        mFilterStatusView.setText(resId);
        mFilterStatusView.setVisibility(View.VISIBLE);
    }

    public void callSelectedEntry() {
        int position = getListView().getSelectedItemPosition();
        if (position < 0) {
            // In touch mode you may often not have something selected, so
            // just call the first entry to make sure that [send] [send] calls the
            // most recent entry.
            position = 0;
        }
        final Cursor cursor = (Cursor)mAdapter.getItem(position);
        if (cursor != null) {
            String number = cursor.getString(CallLogQuery.NUMBER);
            if (TextUtils.isEmpty(number)
                    || number.equals(CallerInfo.UNKNOWN_NUMBER)
                    || number.equals(CallerInfo.PRIVATE_NUMBER)
                    || number.equals(CallerInfo.PAYPHONE_NUMBER)) {
                // This number can't be called, do nothing
                return;
            }
            Intent intent;
            // If "number" is really a SIP address, construct a sip: URI.
            if (PhoneNumberUtils.isUriNumber(number)) {
                intent = CallUtil.getCallIntent(
                        Uri.fromParts(CallUtil.SCHEME_SIP, number, null));
            } else {
                // We're calling a regular PSTN phone number.
                // Construct a tel: URI, but do some other possible cleanup first.
                int callType = cursor.getInt(CallLogQuery.CALL_TYPE);
                if (!number.startsWith("+") &&
                       (callType == Calls.INCOMING_TYPE
                                || callType == Calls.MISSED_TYPE)) {
                    // If the caller-id matches a contact with a better qualified number, use it
                    String countryIso = cursor.getString(CallLogQuery.COUNTRY_ISO);
                    number = mAdapter.getBetterNumberFromContacts(number, countryIso);
                }
                intent = CallUtil.getCallIntent(
                        Uri.fromParts(CallUtil.SCHEME_TEL, number, null));
            }
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(intent);
        }
    }

    @VisibleForTesting
    CallLogAdapter getAdapter() {
        return mAdapter;
    }

    @Override
    public void setMenuVisibility(boolean menuVisible) {
        super.setMenuVisibility(menuVisible);
        if (mMenuVisible != menuVisible) {
            mMenuVisible = menuVisible;
            if (!menuVisible) {
                updateOnExit();
            } else if (isResumed()) {
                refreshData();
            }
        }
    }

    /** Requests updates to the data to be shown. */
    private void refreshData() {
        // Prevent unnecessary refresh.
        if (mRefreshDataRequired) {
            // Mark all entries in the contact info cache as out of date, so they will be looked up
            // again once being shown.
            mAdapter.invalidateCache();
            startCallsQuery();
            startVoicemailStatusQuery();
            updateOnEntry();
            mRefreshDataRequired = false;
        }
    }

    /** Removes the missed call notifications. */
    private void removeMissedCallNotifications() {
        try {
            ITelephony telephony =
                    ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
            if (telephony != null) {
                telephony.cancelMissedCallsNotification();
            } else {
                Log.w(TAG, "Telephony service is null, can't call " +
                        "cancelMissedCallsNotification");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to clear missed calls notification due to remote exception");
        }
    }

    /** Updates call data and notification state while leaving the call log tab. */
    private void updateOnExit() {
        updateOnTransition(false);
    }

    /** Updates call data and notification state while entering the call log tab. */
    private void updateOnEntry() {
        updateOnTransition(true);
    }

    private void updateOnTransition(boolean onEntry) {
        // We don't want to update any call data when keyguard is on because the user has likely not
        // seen the new calls yet.
        // This might be called before onCreate() and thus we need to check null explicitly.
        if (mKeyguardManager != null && !mKeyguardManager.inKeyguardRestrictedInputMode()) {
            // On either of the transitions we reset the new flag and update the notifications.
            // While exiting we additionally consume all missed calls (by marking them as read).
            // This will ensure that they no more appear in the "new" section when we return back.
            mCallLogQueryHandler.markNewCallsAsOld();
            if (!onEntry) {
                mCallLogQueryHandler.markMissedCallsAsRead();
            }
            removeMissedCallNotifications();
            updateVoicemailNotifications();
        }
    }

    private void updateVoicemailNotifications() {
        Intent serviceIntent = new Intent(getActivity(), CallLogNotificationsService.class);
        serviceIntent.setAction(CallLogNotificationsService.ACTION_UPDATE_NOTIFICATIONS);
        getActivity().startService(serviceIntent);
    }

    /**
     * Register a phone call filter to reset the call type when a phone call is place.
     */
    private void registerPhoneCallReceiver() {
        if (mPhoneStateListener != null) {
            return; // Already registered.
        }
        mTelephonyManager = (TelephonyManager) getActivity().getSystemService(
                Context.TELEPHONY_SERVICE);
        mPhoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                if (state != TelephonyManager.CALL_STATE_OFFHOOK &&
                        state != TelephonyManager.CALL_STATE_RINGING) {
                    return;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (getActivity() == null || getActivity().isFinishing()) {
                            return;
                        }
                        updateFilterTypeAndHeader(CallLogQueryHandler.CALL_TYPE_ALL);
                    }
                 });
            }
        };
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    /**
     * Un-registers the phone call receiver.
     */
    protected void unregisterPhoneCallReceiver() {
        if (mPhoneStateListener != null) {
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mPhoneStateListener = null;
        }
    }
}
