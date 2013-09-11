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

package com.android.dialer;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;

import com.android.contacts.common.MoreContactUtils;
import com.android.contacts.common.test.NeededForTesting;
import com.android.dialer.calllog.CallTypeHelper;
import com.android.dialer.calllog.PhoneNumberHelper;

/**
 * Helper class to fill in the views in {@link PhoneCallDetailsViews}.
 */
public class PhoneCallDetailsHelper {
    /** The maximum number of icons will be shown to represent the call types in a group. */
    private static final int MAX_CALL_TYPE_ICONS = 3;

    private final Context mContext;
    private final Resources mResources;
    /** The injected current time in milliseconds since the epoch. Used only by tests. */
    private Long mCurrentTimeMillisForTest;
    // Helper classes.
    private final CallTypeHelper mCallTypeHelper;
    private final PhoneNumberHelper mPhoneNumberHelper;

    /**
     * Creates a new instance of the helper.
     * <p>
     * Generally you should have a single instance of this helper in any context.
     *
     * @param resources used to look up strings
     */
    public PhoneCallDetailsHelper(Context context, CallTypeHelper callTypeHelper,
            PhoneNumberHelper phoneNumberHelper) {
        mContext = context;
        mResources = mContext.getResources();
        mCallTypeHelper = callTypeHelper;
        mPhoneNumberHelper = phoneNumberHelper;
    }

    /** Fills the call details views with content. */
    public void setPhoneCallDetails(PhoneCallDetailsViews views, PhoneCallDetails details,
            boolean isHighlighted) {
        // Display the icon for the last call sub.
        setPhoneCallDetails(views, details, isHighlighted, null);
    }

    /** Fills the call details views with content. */
    public void setPhoneCallDetails(PhoneCallDetailsViews views, PhoneCallDetails details,
            boolean isHighlighted, String filter) {
        // Display the icon for the last call sub.
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            views.subIconView.setVisibility(View.VISIBLE);
            views.subIconView.setImageDrawable(MoreContactUtils.getMultiSimIcon(mContext,
                    MoreContactUtils.FRAMEWORK_ICON, details.subscription));
        } else {
            views.subIconView.setVisibility(View.GONE);
        }

        // Display up to a given number of icons.
        views.callTypeIcons.clear();
        int count = details.callTypes.length;
        for (int index = 0; index < count && index < MAX_CALL_TYPE_ICONS; ++index) {
            views.callTypeIcons.add(details.callTypes[index]);
        }
        views.callTypeIcons.setVisibility(View.VISIBLE);

        // Show the total call count only if there are more than the maximum number of icons.
        final Integer callCount;
        if (count > MAX_CALL_TYPE_ICONS) {
            callCount = count;
        } else {
            callCount = null;
        }
        // The color to highlight the count and date in, if any. This is based on the first call.
        Integer highlightColor =
                isHighlighted ? mCallTypeHelper.getHighlightedColor(details.callTypes[0]) : null;

        // The date of this call, relative to the current time.
        CharSequence dateText =
            DateUtils.getRelativeTimeSpanString(details.date,
                    getCurrentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);

        // Set the call count and date.
        setCallCountAndDate(views, callCount, dateText, highlightColor);

        CharSequence numberFormattedLabel = null;
        // Only show a label if the number is shown and it is not a SIP address.
        if (!TextUtils.isEmpty(details.number)
                && !PhoneNumberUtils.isUriNumber(details.number.toString())) {
            numberFormattedLabel = Phone.getTypeLabel(mResources, details.numberType,
                    details.numberLabel);
        }

        CharSequence nameText;
        final CharSequence numberText;
        final CharSequence labelText;
        CharSequence displayNumber = mPhoneNumberHelper.getDisplayNumber(details.number,
                details.formattedNumber);

        CharSequence locationText;

        String phoneNum = (String) details.number;
        if (!TextUtils.isEmpty(filter) && phoneNum.contains(filter)) {
            int start, end;
            start = phoneNum.indexOf(filter);
            end = start + filter.length();
            int[] offset = getStartEnd((String) displayNumber, start, end);
            SpannableStringBuilder style = new SpannableStringBuilder(displayNumber);
            style.setSpan(new BackgroundColorSpan(0xFF33B5E5), offset[0], offset[1],
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            displayNumber = style;
        }

        if (TextUtils.isEmpty(details.name)) {
            nameText = displayNumber;
            if (TextUtils.isEmpty(details.geocode)
                    || mPhoneNumberHelper.isVoicemailNumber(details.number)) {
                numberText = mResources.getString(R.string.call_log_empty_gecode);
            } else {
                numberText = details.geocode;
            }
            labelText = null;
            // We have a real phone number as "nameView" so make it always LTR
            views.nameView.setTextDirection(View.TEXT_DIRECTION_LTR);
        } else {
            nameText = details.name;
            String nameNum = getNameNumber((String)details.name);
            if (!TextUtils.isEmpty(filter) && nameNum.contains(filter)) {
                int start,end;
                start = nameNum.indexOf(filter);
                end = start + filter.length();
                int[] offset = getStartEnd(((String)details.name).toLowerCase(), start, end);
                SpannableStringBuilder style = new SpannableStringBuilder(details.name);
                style.setSpan(new BackgroundColorSpan(0xFF33B5E5), offset[0], offset[1],
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                nameText = style;
            }

            numberText = displayNumber;
            labelText = "(" + numberFormattedLabel + ")";
            // We have a real phone number as "numberView" so make it always LTR
            views.numberView.setTextDirection(View.TEXT_DIRECTION_LTR);
            if (TextUtils.isEmpty(details.geocode)
                    || mPhoneNumberHelper.isVoicemailNumber(details.number)) {
                locationText = mResources.getString(R.string.call_log_empty_gecode);
            } else {
                locationText = details.geocode;
            }
            views.locationView.setText(locationText);
        }

        views.nameView.setText(nameText);
        views.numberView.setText(numberText);
        views.labelView.setText(labelText);
        views.labelView.setVisibility(TextUtils.isEmpty(labelText) ? View.GONE : View.VISIBLE);
    }

    private String getNumberFromChar(char c) {
        if (c >= 'a' && c <= 'c') {
            return "2";
        } else if (c >= 'd' && c <= 'f') {
            return "3";
        } else if (c >= 'g' && c <= 'i') {
            return "4";
        } else if (c >= 'j' && c <= 'l') {
            return "5";
        } else if (c >= 'm' && c <= 'o') {
            return "6";
        } else if (c >= 'p' && c <= 's') {
            return "7";
        } else if (c >= 't' && c <= 'v') {
            return "8";
        } else if (c >= 'w' && c <= 'z') {
            return "9";
        } else if ('0' <= c && c <= '9') {
            return "" + c;
        } else {
            return "";
        }
    }

    private String getNameNumber(String name) {
        String number = "";
        String nameLow = name.toLowerCase();
        for (int i = 0; i < nameLow.length(); i++) {
            char c = nameLow.charAt(i);
            number = number + getNumberFromChar(c);
        }
        return number;
    }

    /**
     * re-calculate start & end according to the String we are about to show
     */
    private int[] getStartEnd(String s, int start, int end) {
        int[] offset = new int[2];

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c == '+' || c >= 'a' && c <= 'z')) {
                if (i <= start) {
                    start++;
                    end++;
                } else if (i > start && i <= end) {
                    end++;
                }
            }
        }
        if (start > s.length()) {
            start = s.length();
        }
        if (end > s.length()) {
            end = s.length();
        }
        offset[0] = start;
        offset[1] = end;
        return offset;
    }

    /** Sets the text of the header view for the details page of a phone call. */
    public void setCallDetailsHeader(TextView nameView, PhoneCallDetails details) {
        final CharSequence nameText;
        final CharSequence displayNumber =
                mPhoneNumberHelper.getDisplayNumber(details.number,
                        mResources.getString(R.string.recentCalls_addToContact));
        if (TextUtils.isEmpty(details.name)) {
            nameText = displayNumber;
        } else {
            nameText = details.name;
        }

        nameView.setText(nameText);
    }

    @NeededForTesting
    public void setCurrentTimeForTest(long currentTimeMillis) {
        mCurrentTimeMillisForTest = currentTimeMillis;
    }

    /**
     * Returns the current time in milliseconds since the epoch.
     * <p>
     * It can be injected in tests using {@link #setCurrentTimeForTest(long)}.
     */
    private long getCurrentTimeMillis() {
        if (mCurrentTimeMillisForTest == null) {
            return System.currentTimeMillis();
        } else {
            return mCurrentTimeMillisForTest;
        }
    }

    /** Sets the call count and date. */
    private void setCallCountAndDate(PhoneCallDetailsViews views, Integer callCount,
            CharSequence dateText, Integer highlightColor) {
        // Combine the count (if present) and the date.
        final CharSequence text;
        if (callCount != null) {
            text = mResources.getString(
                    R.string.call_log_item_count_and_date, callCount.intValue(), dateText);
        } else {
            text = dateText;
        }

        // Apply the highlight color if present.
        final CharSequence formattedText;
        if (highlightColor != null) {
            formattedText = addBoldAndColor(text, highlightColor);
        } else {
            formattedText = text;
        }

        views.callTypeAndDate.setText(formattedText);
    }

    /** Creates a SpannableString for the given text which is bold and in the given color. */
    private CharSequence addBoldAndColor(CharSequence text, int color) {
        int flags = Spanned.SPAN_INCLUSIVE_INCLUSIVE;
        SpannableString result = new SpannableString(text);
        result.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), flags);
        result.setSpan(new ForegroundColorSpan(color), 0, text.length(), flags);
        return result;
    }
}
