/*
 * Copyright (C) 2013-2014, The Linux Foundation. All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are
 met:
        * Redistributions of source code must retain the above copyright
          notice, this list of conditions and the following disclaimer.
        * Redistributions in binary form must reproduce the above
          copyright notice, this list of conditions and the following
          disclaimer in the documentation and/or other materials provided
          with the distribution.
        * Neither the name of The Linux Foundation, Inc. nor the names of its
          contributors may be used to endorse or promote products derived
          from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.dialer;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import static com.android.internal.telephony.PhoneConstants.SUBSCRIPTION_KEY;
import java.util.List;

public class SpeedDialListActivity extends ListActivity
        implements OnItemClickListener {

    private static final String TAG = "SpeedDial";
    private static final String ACTION_ADD_VOICEMAIL
            = "com.android.phone.CallFeaturesSetting.ADD_VOICEMAIL";

    private static final int PREF_NUM = 8;
    private static final int SPEED_ITEMS = 9;
    //save the speed dial number
    private static String[] mContactDataNumber = new String[PREF_NUM];
    //save the speed dial name
    private static String[] mContactDataName = new String[PREF_NUM];
    //save the speed dial sim key
    private static Boolean[] mContactSimKey = new Boolean[PREF_NUM];
    //save the speed list item content, include 1 voice mail and 2-9 speed number
    private static String[] mSpeedListItems = new String[SPEED_ITEMS];

    //speeddialutils class, use to read speed number form preference and update data
    private static SpeedDialUtils mSpeedDialUtils;

    private static int mPosition;

    private static final int PICK_CONTACT_RESULT = 0;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getActionBar().setDisplayHomeAsUpEnabled(true);

        mSpeedDialUtils = new SpeedDialUtils(this);
        // the first item is the "1.voice mail", it doesn't change for ever
        mSpeedListItems[0] = getString(R.string.speed_item, String.valueOf(1),
                getString(R.string.voicemail));

        // get number and name from share preference
        for (int i = 0; i < PREF_NUM; i++) {
            mContactDataNumber[i] = mSpeedDialUtils.getContactDataNumber(i);
            mContactDataName[i] = mSpeedDialUtils.getContactDataName(i);
            mContactSimKey[i] = mSpeedDialUtils.getContactSimKey(i);
        }

        ListView listview = getListView();
        listview.setOnItemClickListener(this);
    }

    @Override
    protected void onResume() {
        // TODO Auto-generated method stub
        super.onResume();

        // when every on resume, should match name from contacts, because if
        // this activity is paused, and the contacts data is changed(eg:contact
        // is edited or deleted...),after it resumes, its data also be updated.
        matchInfoFromContacts();
        setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                mSpeedListItems));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return false;
    }

    /*
     * use to match number from contacts, if the speed number is in contacts,
     * the speed item show corresponding contact name, else show number.
     */
    private void matchInfoFromContacts() {
        // TODO Auto-generated method stub
        for (int i = 1; i < SPEED_ITEMS; i++) {
            // if there is no speed dial number for number key, show "not set", or lookup in contacts
            // according to number, if exist, show contact name, else show number.
            if (TextUtils.isEmpty(mContactDataNumber[i - 1])) {
                mContactDataName[i - 1] = "";
                mContactSimKey[i - 1] = false;

                mSpeedListItems[i] = getString(R.string.speed_item, String.valueOf(i + 1),
                        getString(R.string.not_set));
            } else {
                mContactDataName[i - 1] = mSpeedDialUtils.getValidName(mContactDataNumber[i - 1]);
                if (TextUtils.isEmpty(mContactDataName[i - 1])) {
                    mContactDataName[i - 1] = "";
                    mContactSimKey[i - 1] = false;

                    mSpeedListItems[i] = getString(R.string.speed_item, String.valueOf(i + 1),
                            mContactDataNumber[i - 1]);
                } else {
                    mContactSimKey[i - 1] = mSpeedDialUtils
                            .isSimAccontByNumber(mContactDataNumber[i - 1]);

                    mSpeedListItems[i] = getString(R.string.speed_item, String.valueOf(i + 1),
                            mContactDataName[i - 1]);
                }
            }
            mSpeedDialUtils.storeContactDataNumber(i - 1, mContactDataNumber[i - 1]);
            mSpeedDialUtils.storeContactDataName(i - 1, mContactDataName[i - 1]);
            mSpeedDialUtils.storeContactSimKey(i - 1, mContactSimKey[i - 1]);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        // TODO Auto-generated method stub
        if (position == 0) {
            Intent intent = new Intent(ACTION_ADD_VOICEMAIL);
            if (TelephonyManager.getDefault().getPhoneCount() > 1) {
                if (isMultiAccountAvailable()) {
                    showSelectAccountDialog(this);
                    return;
                } else {
                    long sub = SubscriptionManager.getDefaultVoiceSubId();
                    intent.setClassName("com.android.phone",
                            "com.android.phone.MSimCallFeaturesSubSetting");
                    intent.putExtra(SUBSCRIPTION_KEY, sub);
                }
            } else {
                intent.setClassName("com.android.phone",
                        "com.android.phone.CallFeaturesSetting");
            }
            try {
                startActivity(intent);
            } catch(ActivityNotFoundException e) {
                Log.w(TAG, "can not find activity deal with voice mail");
            }
        } else if (position < SPEED_ITEMS) {
            if ("".equals(mContactDataNumber[position - 1])) {
                goContactsToPick(position);
            } else {
                final String numStr = mContactDataNumber[position - 1];
                final String nameStr = mContactDataName[position - 1];
                final int pos = position;
                new AlertDialog.Builder(this)
                    .setTitle(nameStr)
                    .setMessage(numStr)
                    .setNegativeButton(R.string.replace,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                goContactsToPick(pos);
                            }
                        })
                    .setPositiveButton(R.string.delete,
                        new DialogInterface.OnClickListener() {
                            @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    // delete speed number, only need set array data to "",
                                    // and clear speed number in preference.
                                    mContactDataNumber[pos - 1] = "";
                                    mContactDataName[pos - 1] = "";
                                    mSpeedListItems[pos] = getString(R.string.speed_item,
                                            String.valueOf(pos + 1),
                                            getString(R.string.not_set));
                                    mSpeedDialUtils.storeContactDataNumber(pos - 1, "");
                                    mSpeedDialUtils.storeContactDataName(pos - 1, "");
                                    mSpeedDialUtils.storeContactSimKey(pos - 1, false);
                                    // update listview item
                                    setListAdapter(
                                            new ArrayAdapter<String>(SpeedDialListActivity.this,
                                            android.R.layout.simple_list_item_1, mSpeedListItems));
                                }
                            })
                    .show();
            }
        } else {
            Log.w(TAG, "the invalid item");
        }
    }

    private boolean isMultiAccountAvailable() {
        TelecomManager telecomManager = getTelecomManager(this);
        return (telecomManager.getUserSelectedOutgoingPhoneAccount() == null)
                && (telecomManager.getAllPhoneAccountsCount() > 1);
    }

    private void showSelectAccountDialog(Context context) {
        TelecomManager telecomManager = getTelecomManager(context);
        List<PhoneAccountHandle> accountsList = telecomManager
                .getCallCapablePhoneAccounts();
        final PhoneAccountHandle[] accounts = accountsList
                .toArray(new PhoneAccountHandle[accountsList.size()]);
        CharSequence[] accountEntries = new CharSequence[accounts.length];
        for (int i = 0; i < accounts.length; i++) {
            CharSequence label = telecomManager.getPhoneAccount(accounts[i])
                    .getLabel();
            accountEntries[i] = (label == null) ? null : label.toString();
        }
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.select_account_dialog_title)
                .setItems(accountEntries, new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(ACTION_ADD_VOICEMAIL);
                        long sub = Long.parseLong(accounts[which].getId());
                        intent.setClassName("com.android.phone",
                                "com.android.phone.MSimCallFeaturesSubSetting");
                        intent.putExtra(SUBSCRIPTION_KEY, sub);
                        try {
                            startActivity(intent);
                        } catch (ActivityNotFoundException e) {
                            Log.w(TAG, "can not find activity deal with voice mail");
                        }
                    }
                })
                .create();
        dialog.show();
    }

    private TelecomManager getTelecomManager(Context context) {
        return TelecomManager.from(context);
    }

    /*
     * goto contacts, used to set or replace speed number
     */
    private void goContactsToPick(int position) {
        // TODO Auto-generated method stub
        mPosition = position;
        Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds
               .Phone.CONTENT_URI);
        startActivityForResult(intent, PICK_CONTACT_RESULT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // TODO Auto-generated method stub
        if (requestCode == PICK_CONTACT_RESULT && resultCode == Activity.RESULT_OK
                && data != null) {
            // get phone number according to the uri
            Uri uriRet = data.getData();
            if (uriRet != null) {
                int numId = mPosition - 1;
                Cursor c = null;
                try {
                    c = getContentResolver().query(uriRet, null, null, null, null);
                    if (null != c && 0 != c.getCount()) {
                        c.moveToFirst();
                        String number = c.getString(c.getColumnIndexOrThrow(Data.DATA1));
                        String name = "";
                        String accountType = null;
                        int rawContactId = c.getInt(c.getColumnIndexOrThrow("raw_contact_id"));
                        String where = "_id = " + rawContactId;
                        c = getContentResolver().query(RawContacts.CONTENT_URI, new String[] {
                                "display_name", "account_type"}, where, null, null);
                        if (null != c && 0 != c.getCount()) {
                            c.moveToFirst();
                            name = c.getString(c.getColumnIndexOrThrow("display_name"));
                            accountType = c.getString(c.getColumnIndexOrThrow("account_type"));
                            if (!okToSet(number)) {
                                Toast.makeText(this, R.string.assignSpeedDialFailToast,
                                        Toast.LENGTH_LONG).show();
                                return;
                            } else {
                                mContactDataName[numId] = name;
                                mContactDataNumber[numId] = number;
                                mContactSimKey[numId] = mSpeedDialUtils.isSimAccount(accountType);
                            }
                        }
                    } else {
                        mContactDataNumber[numId] = "";
                        mContactDataName[numId] = "";
                    }
                } catch (Exception e) {
                    // exception happens
                } finally {
                    if (null != c) {
                        c.close();
                    }
                }
                mSpeedDialUtils.storeContactDataNumber(numId, mContactDataNumber[numId]);
                mSpeedDialUtils.storeContactDataName(numId, mContactDataName[numId]);
                mSpeedDialUtils.storeContactSimKey(numId, mContactSimKey[numId]);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private boolean okToSet(String number) {
        boolean isCheckOk = true;
        for (String phoneNumber: mContactDataNumber) {
            if (number.equals(phoneNumber)) {
                isCheckOk = false;
                break;
            }
        }
        return isCheckOk;
    }

}
