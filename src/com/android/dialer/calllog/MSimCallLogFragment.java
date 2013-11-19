/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2013, The Linux Foundation. All rights reserved.
 *
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

package com.android.dialer.calllog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.CallLog.Calls;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.contacts.common.MoreContactUtils;
import com.android.dialer.R;
import com.android.dialer.voicemail.VoicemailStatusHelperImpl;

/**
 * Displays a list of call log entries.
 */
public class MSimCallLogFragment extends CallLogFragment {
    private static final String TAG = "MSimCallLogFragment";

    /**
     * Key for the call log sub saved in the default preference.
     */
    private static final String PREFERENCE_KEY_CALLLOG_SUB = "call_log_sub";

    // Add and change for filter call log.
    private Spinner mFilterSubSpinnerView;
    private Spinner mFilterStatusSpinnerView;

    // Default to all slots.
    private int mCallSubFilter = CallLogQueryHandler.CALL_SUB_ALL;

    // The index for call type spinner.
    private static final int INDEX_CALL_TYPE_ALL = 0;
    private static final int INDEX_CALL_TYPE_INCOMING = 1;
    private static final int INDEX_CALL_TYPE_OUTGOING = 2;
    private static final int INDEX_CALL_TYPE_MISSED = 3;
    private static final int INDEX_CALL_TYPE_VOICEMAIL = 4;

    private OnItemSelectedListener mSubSelectedListener = new OnItemSelectedListener() {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            Log.i(TAG, "Sub selected, position: " + position);
            int sub = position - 1;
            mCallSubFilter = sub;
            setSelectedSub(sub);
            mCallLogQueryHandler.fetchCalls(mCallTypeFilter, mCallSubFilter);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // Do nothing.
        }

    };

    private OnItemSelectedListener mStatusSelectedListener = new OnItemSelectedListener() {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            Log.i(TAG, "Status selected, position: " + position);
            switch (position) {
                case INDEX_CALL_TYPE_ALL:
                    mCallTypeFilter = CallLogQueryHandler.CALL_TYPE_ALL;
                    break;
                case INDEX_CALL_TYPE_INCOMING:
                    mCallTypeFilter = Calls.INCOMING_TYPE;
                    break;
                case INDEX_CALL_TYPE_OUTGOING:
                    mCallTypeFilter = Calls.OUTGOING_TYPE;
                    break;
                case INDEX_CALL_TYPE_MISSED:
                    mCallTypeFilter = Calls.MISSED_TYPE;
                    break;
                case INDEX_CALL_TYPE_VOICEMAIL:
                    mCallTypeFilter = Calls.VOICEMAIL_TYPE;
                    break;
            }
            mCallLogQueryHandler.fetchCalls(mCallTypeFilter, mCallSubFilter);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // Do nothing.
        }

    };

    @Override
    protected void setVoicemailSourcesAvailable(boolean voicemailSourcesAvailable) {
        if (mVoicemailSourcesAvailable == voicemailSourcesAvailable) return;
        mVoicemailSourcesAvailable = voicemailSourcesAvailable;

        Activity activity = getActivity();
        if (activity != null) {
            // This is so that the options menu content is updated.
            activity.invalidateOptionsMenu();
            updateFilterSpinnierViews();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        View view = inflater.inflate(R.layout.msim_call_log_fragment, container, false);
        mVoicemailStatusHelper = new VoicemailStatusHelperImpl();
        mStatusMessageView = view.findViewById(R.id.voicemail_status);
        mStatusMessageText = (TextView) view.findViewById(R.id.voicemail_status_message);
        mStatusMessageAction = (TextView) view.findViewById(R.id.voicemail_status_action);

        mFilterSubSpinnerView = (Spinner) view.findViewById(R.id.filter_sub_spinner);
        mFilterStatusSpinnerView = (Spinner) view.findViewById(R.id.filter_status_spinner);

        return view;
    }

    @Override
    public void onResume() {
        // Update the filter views.
        updateFilterSpinnierViews();
        super.onResume();
    }

    @Override
    public void fetchCalls() {
        mCallLogQueryHandler.fetchCalls(mCallTypeFilter, mCallSubFilter);
    }

    @Override
    public void startCallsQuery() {
        mAdapter.setLoading(true);
        mCallLogQueryHandler.fetchCalls(mCallTypeFilter, mCallSubFilter);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.msim_call_log_options, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        final MenuItem itemDeleteAll = menu.findItem(R.id.delete_all);
        // Check if all the menu items are inflated correctly. As a shortcut, we assume all
        // menu items are ready if the first item is non-null.
        if (itemDeleteAll != null) {
            itemDeleteAll.setEnabled(mAdapter != null && !mAdapter.isEmpty());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.delete_all:
                //ClearCallLogDialog.show(getFragmentManager());
                onDelCallLog();
                return true;

            default:
                return false;
        }
    }

    private void onDelCallLog() {
        Intent intent = new Intent("com.android.contacts.action.MULTI_PICK_CALL");
        startActivity(intent);
    }

    /**
     * Initialize the filter views content.
     */
    private void updateFilterSpinnierViews() {
        if (mFilterSubSpinnerView == null
                || mFilterStatusSpinnerView == null) {
            Log.w(TAG, "The filter view is null, please pay attention!");
            return;
        }

        // As the default, the view for filter sub will be visible. But if there is only one
        // SIM, the view for filter sub needn't display, set it as gone.
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            mCallSubFilter = getSelectedSub();
            mFilterSubSpinnerView.setVisibility(View.VISIBLE);

            ArrayAdapter<SpinnerContent> filterSubAdapter = new ArrayAdapter<SpinnerContent>(
                    this.getActivity(), R.layout.call_log_spinner_item, setupSubFilterContent());
            mFilterSubSpinnerView.setAdapter(filterSubAdapter);
            mFilterSubSpinnerView.setOnItemSelectedListener(mSubSelectedListener);
            SpinnerContent.setSpinnerContentValue(mFilterSubSpinnerView, mCallSubFilter);
        } else {
            // There is only one SIM, needn't to show this filter.
            mCallSubFilter = CallLogQueryHandler.CALL_SUB_ALL;
            mFilterSubSpinnerView.setVisibility(View.GONE);
        }

        // Update the filter status content.
        ArrayAdapter<SpinnerContent> filterStatusAdapter = new ArrayAdapter<SpinnerContent>(
                this.getActivity(), R.layout.call_log_spinner_item, setupStatusFilterContent());
        mFilterStatusSpinnerView.setAdapter(filterStatusAdapter);
        mFilterStatusSpinnerView.setOnItemSelectedListener(mStatusSelectedListener);
        SpinnerContent.setSpinnerContentValue(mFilterStatusSpinnerView, mCallTypeFilter);
    }

    private SpinnerContent[] setupSubFilterContent() {
        int count = MSimTelephonyManager.getDefault().getPhoneCount();
        // Update the filter sub content.
        SpinnerContent filterSub[] = new SpinnerContent[count + 1];
        filterSub[0] = new SpinnerContent(CallLogQueryHandler.CALL_SUB_ALL,
                getString(R.string.call_log_show_all_slots));
        for (int i = 0; i < count; i++) {
            filterSub[i + 1] = new SpinnerContent(i, MoreContactUtils.getMultiSimAliasesName(
                    getActivity(), i));
        }
        return filterSub;
    }

    private SpinnerContent[] setupStatusFilterContent() {
        // Didn't show the voice mail item if not available.
        int statusCount = mVoicemailSourcesAvailable ? 5 : 4;
        SpinnerContent filterStatus[] = new SpinnerContent[statusCount];
        for (int i = 0; i < statusCount; i++) {
            int value = CallLogQueryHandler.CALL_TYPE_ALL;
            String lable = null;
            switch (i) {
                case INDEX_CALL_TYPE_ALL:
                    value = CallLogQueryHandler.CALL_TYPE_ALL;
                    lable = getString(R.string.call_log_all_calls_header);
                    break;
                case INDEX_CALL_TYPE_INCOMING:
                    value = Calls.INCOMING_TYPE;
                    lable = getString(R.string.call_log_incoming_header);
                    break;
                case INDEX_CALL_TYPE_OUTGOING:
                    value = Calls.OUTGOING_TYPE;
                    lable = getString(R.string.call_log_outgoing_header);
                    break;
                case INDEX_CALL_TYPE_MISSED:
                    value = Calls.MISSED_TYPE;
                    lable = getString(R.string.call_log_missed_header);
                    break;
                case INDEX_CALL_TYPE_VOICEMAIL:
                    value = Calls.VOICEMAIL_TYPE;
                    lable = getString(R.string.call_log_voicemail_header);
                    break;
            }
            filterStatus[i] = new SpinnerContent(value, lable);
        }
        return filterStatus;
    }

    /**
     * @return the saved selected subscription.
     */
    private int getSelectedSub() {
        // Get the saved selected sub, and the default value is display all.
        int sub = PreferenceManager.getDefaultSharedPreferences(this.getActivity()).getInt(
                PREFERENCE_KEY_CALLLOG_SUB, CallLogQueryHandler.CALL_SUB_ALL);
        return sub;
    }

    /**
     * Save the selected subscription to preference.
     */
    private void setSelectedSub(int sub) {
        // Save the selected sub to the default preference.
        PreferenceManager.getDefaultSharedPreferences(this.getActivity()).edit()
                .putInt(PREFERENCE_KEY_CALLLOG_SUB, sub).commit();
    }

    /**
     * To save the spinner content.
     */
    private static class SpinnerContent {
        public final int value;
        public final String label;

        public static void setSpinnerContentValue(Spinner spinner, int value) {
            for (int i = 0, count = spinner.getCount(); i < count; i++) {
                SpinnerContent sc = (SpinnerContent)spinner.getItemAtPosition(i);
                if (sc.value == value) {
                    spinner.setSelection(i, true);
                    Log.i(TAG, "Set selection for spinner(" + sc + ") with the value: " + value);
                    return;
                }
            }
        }

        public SpinnerContent(int value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
