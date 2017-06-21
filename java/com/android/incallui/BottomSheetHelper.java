/* Copyright (c) 2017, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.incallui;

import android.support.v4.app.FragmentManager;
import android.support.v4.os.UserManagerCompat;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.telecom.Call.Details;
import android.telecom.PhoneAccountHandle;
import android.telecom.VideoProfile;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import com.android.dialer.compat.ActivityCompat;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;
import com.android.dialer.common.LogUtil;
import com.android.dialer.util.IntentUtil;
import com.android.incallui.videotech.ims.ImsVideoTech;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

import org.codeaurora.ims.QtiImsException;
import org.codeaurora.ims.QtiImsExtListenerBaseImpl;
import org.codeaurora.ims.QtiImsExtManager;
import org.codeaurora.ims.utils.QtiImsExtUtils;

public class BottomSheetHelper implements InCallPresenter.InCallEventListener {

   private ConcurrentHashMap<String,Boolean> moreOptionsMap;
   private ExtBottomSheetFragment moreOptionsSheet;
   private int voiceNetworkType;
   private Context mContext;
   private DialerCall mCall;
   private PrimaryCallTracker mPrimaryCallTracker;
   private Resources mResources;
   private static BottomSheetHelper mHelper;
   private AlertDialog callTransferDialog;
   private AlertDialog modifyCallDialog;
   private static final int BLIND_TRANSFER = 0;
   private static final int ASSURED_TRANSFER = 1;
   private static final int CONSULTATIVE_TRANSFER = 2;
   private static final int INVALID_INDEX = -1;

   /* QtiImsExtListenerBaseImpl instance to handle call deflection response */
   private QtiImsExtListenerBaseImpl imsInterfaceListener =
      new QtiImsExtListenerBaseImpl() {

     /* Handles call deflect response */
     @Override
     public void receiveCallDeflectResponse(int phoneId, int result) {
          LogUtil.w("BottomSheetHelper.receiveCallDeflectResponse:", "result = " + result);
     }

     /* Handles call transfer response */
     @Override
     public void receiveCallTransferResponse(int phoneId, int result) {
          LogUtil.w("BottomSheetHelper.receiveCallTransferResponse", "result: " + result);
     }
   };

   private BottomSheetHelper() {
     LogUtil.d("BottomSheetHelper"," ");
   }

   public static BottomSheetHelper getInstance() {
     if (mHelper == null) {
       mHelper = new BottomSheetHelper();
     }
     return mHelper;
   }

   public void setUp(Context context) {
     LogUtil.d("BottomSheetHelper","setUp");
     mContext = context;
     mResources = context.getResources();
     final String[][] moreOptions = getMoreOptionsFromRes(R.array.bottom_sheet_more_options);
     moreOptionsMap = prepareSheetOptions(moreOptions);
     mPrimaryCallTracker = new PrimaryCallTracker();
     InCallPresenter.getInstance().addListener(mPrimaryCallTracker);
     InCallPresenter.getInstance().addIncomingCallListener(mPrimaryCallTracker);
     InCallPresenter.getInstance().addInCallEventListener(this);
   }

   public void tearDown() {
     LogUtil.d("BottomSheetHelper","tearDown");
     InCallPresenter.getInstance().removeListener(mPrimaryCallTracker);
     InCallPresenter.getInstance().removeIncomingCallListener(mPrimaryCallTracker);
     InCallPresenter.getInstance().removeInCallEventListener(this);
     mPrimaryCallTracker = null;
     mContext = null;
     mResources = null;
     moreOptionsMap = null;
   }

   public void updateMap() {
     mCall = mPrimaryCallTracker.getPrimaryCall();
     LogUtil.i("BottomSheetHelper.updateMap","mCall = " + mCall);

     if (mCall != null && moreOptionsMap != null && mResources != null) {
       maybeUpdateDeflectInMap();
       maybeUpdateAddParticipantInMap();
       maybeUpdateTransferInMap();
       maybeUpdateManageConferenceInMap();
       maybeUpdateOneWayVideoOptionsInMap();
       maybeUpdateModifyCallInMap();
     }
   }

   // Utility function which converts options from string array to HashMap<String,Boolean>
   private static ConcurrentHashMap<String,Boolean> prepareSheetOptions(String[][] answerOptArray) {
     ConcurrentHashMap<String,Boolean> map = new ConcurrentHashMap<String,Boolean>();
     for (int iter = 0; iter < answerOptArray.length; iter ++) {
       map.put(answerOptArray[iter][0],Boolean.valueOf(answerOptArray[iter][1]));
     }
     return map;
   }

   private boolean isOneWayVideoOptionsVisible() {
     final int primaryCallState = mCall.getState();
     final int requestedVideoState = mCall.getVideoTech().getRequestedVideoState();

     return (QtiCallUtils.useExt(mContext) && mCall.hasReceivedVideoUpgradeRequest()
       && VideoProfile.isBidirectional(requestedVideoState))
       || ((DialerCall.State.INCOMING == primaryCallState
       || DialerCall.State.CALL_WAITING == primaryCallState) && mCall.isVideoCall());
   }

   private boolean isModifyCallOptionsVisible() {
     final int primaryCallState = mCall.getState();
     return QtiCallUtils.useExt(mContext) && (DialerCall.State.ACTIVE == primaryCallState
        || DialerCall.State.ONHOLD == primaryCallState)
        && QtiCallUtils.hasVoiceOrVideoCapabilities(mCall)
        && !mCall.hasReceivedVideoUpgradeRequest();
   }

   private void maybeUpdateManageConferenceInMap() {
     /* show manage conference option only for active video conference calls if the call
        has manage conference capability */
     boolean visible = mCall.isVideoCall() && mCall.getState() == DialerCall.State.ACTIVE &&
         mCall.can(android.telecom.Call.Details.CAPABILITY_MANAGE_CONFERENCE);
     moreOptionsMap.put(mResources.getString(R.string.manageConferenceLabel), visible);
   }

   public boolean isManageConferenceVisible() {
     if (moreOptionsMap == null || mResources == null) {
         LogUtil.w("isManageConferenceVisible","moreOptionsMap or mResources is null");
         return false;
     }

     return moreOptionsMap.get(mResources.getString(R.string.manageConferenceLabel)).booleanValue()
        && !mCall.hasReceivedVideoUpgradeRequest();
   }

   public void showBottomSheet(FragmentManager manager) {
     LogUtil.d("BottomSheetHelper.showBottomSheet","moreOptionsMap: " + moreOptionsMap);
     moreOptionsSheet = ExtBottomSheetFragment.newInstance(moreOptionsMap);
     moreOptionsSheet.show(manager, null);
   }

   public void dismissBottomSheet() {
     if (moreOptionsSheet != null && moreOptionsSheet.isVisible()) {
       moreOptionsSheet.dismiss();
     }
     if (callTransferDialog != null && callTransferDialog.isShowing()) {
       callTransferDialog.dismiss();
     }

     if (modifyCallDialog != null && modifyCallDialog.isShowing()) {
       modifyCallDialog.dismiss();
     }
   }

   public void optionSelected(@Nullable String text) {
     //callback for bottomsheet clicks
     LogUtil.d("BottomSheetHelper.optionSelected","text : " + text);
     if (text.equals(mContext.getResources().getString(R.string.add_participant_option_msg))) {
       if (QtiImsExtUtils.isCarrierConfigEnabled(getPhoneId(), mContext,
               "add_multi_participants_enabled")) {
         startAddMultiParticipantActivity();
       } else {
         startAddParticipantActivity();
       }
     } else if (text.equals(mResources.getString(R.string.qti_description_target_deflect))) {
       deflectCall();
     } else if (text.equals(mResources.getString(R.string.qti_description_transfer))) {
       transferCall();
     } else if (text.equals(mResources.getString(R.string.manageConferenceLabel))) {
       manageConferenceCall();
     } else if (text.equals(mResources.getString(R.string.video_tx_label))) {
       acceptIncomingCallOrUpgradeRequest(VideoProfile.STATE_TX_ENABLED);
     } else if (text.equals(mResources.getString(R.string.video_rx_label))) {
       acceptIncomingCallOrUpgradeRequest(VideoProfile.STATE_RX_ENABLED);
     } else if (text.equals(mResources.getString(R.string.modify_call_label))) {
       displayModifyCallOptions();
     }
     moreOptionsSheet = null;
   }

   public void sheetDismissed() {
     LogUtil.d("BottomSheetHelper.sheetDismissed"," ");
     moreOptionsSheet = null;
   }

   private String[][] getMoreOptionsFromRes(final int resId) {
     TypedArray typedArray = mResources.obtainTypedArray(resId);
     String[][] array = new String[typedArray.length()][];
     for  (int iter = 0;iter < typedArray.length(); iter++) {
       int id = typedArray.getResourceId(iter, 0);
       if (id > 0) {
         array[iter] = mResources.getStringArray(id);
       }
     }
     typedArray.recycle();
     return array;
   }

   public boolean shallShowMoreButton(Activity activity) {
     if (mPrimaryCallTracker != null) {
       DialerCall call = mPrimaryCallTracker.getPrimaryCall();
       if (call != null && activity != null) {
         int primaryCallState = call.getState();
         return !(ActivityCompat.isInMultiWindowMode(activity)
           || call.isEmergencyCall()
           || DialerCall.State.isDialing(primaryCallState)
           || DialerCall.State.CONNECTING == primaryCallState
           || DialerCall.State.DISCONNECTING == primaryCallState
           || call.hasSentVideoUpgradeRequest()
           || !((getVoiceNetworkType() == TelephonyManager.NETWORK_TYPE_LTE)
           || call.hasProperty(Details.PROPERTY_WIFI)));
       }
     }
     LogUtil.w("BottomSheetHelper shallShowMoreButton","returns false");
     return false;
   }

   private int getVoiceNetworkType() {
     return VERSION.SDK_INT >= VERSION_CODES.N
       ? mContext.getSystemService(TelephonyManager.class).getVoiceNetworkType()
       : TelephonyManager.NETWORK_TYPE_UNKNOWN;
   }

  private boolean isAddParticipantSupported() {
    boolean showAddParticipant = mCall != null
      && mCall.can(DialerCall.CAPABILITY_ADD_PARTICIPANT)
      && UserManagerCompat.isUserUnlocked(mContext)
      && !mCall.hasReceivedVideoUpgradeRequest();
    if (QtiImsExtUtils.isCarrierConfigEnabled(getPhoneId(), mContext,
        "add_participant_only_in_conference")) {
      showAddParticipant = showAddParticipant && (mCall != null) && (mCall.isConferenceCall());
    }
    return showAddParticipant;
  }

  private void maybeUpdateAddParticipantInMap() {
    moreOptionsMap.put(mResources.getString(R.string.add_participant_option_msg),
        isAddParticipantSupported());
  }

  private void startAddParticipantActivity() {
    try {
      mContext.startActivity(QtiCallUtils.getAddParticipantsIntent());
    } catch (ActivityNotFoundException e) {
      LogUtil.e("BottomSheetHelper.startAddParticipantActivity",
          "Activity not found. Exception = " + e);
    }
  }

  private void startAddMultiParticipantActivity() {
    Intent intent = QtiCallUtils.getAddParticipantsIntent(null);
    List<String> childCallIdList = (mCall != null) ? mCall.getChildCallIds() : null;
    if (childCallIdList != null) {
        StringBuffer sb = new StringBuffer();
        for (String tmp: childCallIdList) {
            String number = CallList.getInstance()
                    .getCallById(tmp).getNumber();
            if (number.contains(";")) {
                String[] temp = number.split(";");
                number = temp[0];
            }
            sb.append(number).append(";");
        }
        intent.putExtra("current_participant_list", sb.toString());
    } else {
      LogUtil.e("BottomSheetHelper.startAddMultiParticipantActivity",
          "sendAddMultiParticipantsIntent, childCallIdList null.");
    }
    try {
      mContext.startActivity(intent);
    } catch (ActivityNotFoundException e) {
      LogUtil.e("BottomSheetHelper.startAddMultiParticipantActivity",
          "Activity not found. Exception = " + e);
    }
  }

   private String getIccId() {
     if (mPrimaryCallTracker != null) {
       DialerCall call = mPrimaryCallTracker.getPrimaryCall();
       if (call != null) {
         PhoneAccountHandle ph = call.getAccountHandle();
         if (ph != null) {
           try {
             String iccId = ph.getId();
             if (iccId != null) {
               return iccId;
             }
           } catch (Exception e) {
             LogUtil.w("BottomSheetHelper.getIccId", "exception: " + e);
           }
           return null;
         } else {
           LogUtil.w("BottomSheetHelper.getIccId", "phoneAccountHandle is null");
           return null;
         }
       }
     }
     LogUtil.w("BottomSheetHelper.getIccId", "mPrimaryCallTracker or call is null");
     return null;
   }

   private int getActiveSubIdFromIccId(String iccId) {
     SubscriptionInfo subInfo = null;
     try {
       Class c = Class.forName("android.telephony.SubscriptionManager");
       Method m = c.getMethod("getActiveSubscriptionInfoForIccIndex",
            new Class[]{String.class});
       SubscriptionManager subscriptionManager = SubscriptionManager.from(mContext);
       if (subscriptionManager == null) {
         return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
       }
       subInfo = (SubscriptionInfo)m.invoke(subscriptionManager, iccId);
     } catch (Exception e) {
       LogUtil.e("BottomSheetHelper.getActiveSubIdFromIccId", " ex: " + e);
     }
     return (subInfo != null) ? subInfo.getSubscriptionId()
          : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
   }

   public int getSubId() {
     return getActiveSubIdFromIccId(getIccId());
   }

   /* this API should be called only when there is a call */
   public int getPhoneId() {
     // check for phoneId only in multisim case, otherwise return 0
     int phoneCount = mContext.getSystemService(TelephonyManager.class).getPhoneCount();
     if (phoneCount > 1) {
       int subId = getSubId();
       LogUtil.d("BottomSheetHelper.getPhoneId", "subId: " + subId);
       try {
         Class c = Class.forName("android.telephony.SubscriptionManager");
         Method m = c.getMethod("getPhoneId",new Class[]{int.class});
         int phoneId = (Integer)m.invoke(null, subId);
         if (phoneId >= phoneCount || phoneId < 0) {
           phoneId = 0;
         }
         LogUtil.d("BottomSheetHelper.getPhoneId", "phoneid: " + phoneId);
         return phoneId;
       } catch (Exception e) {
         LogUtil.e("BottomSheetHelper.getPhoneId", " ex: " + e);
       }
     }
     return 0;
   }

   private void maybeUpdateDeflectInMap() {
     final boolean showDeflectCall = QtiCallUtils.isCallDeflectSupported(mContext) &&
         (mCall.getState() == DialerCall.State.INCOMING) && !mCall.isVideoCall() &&
         !mCall.hasReceivedVideoUpgradeRequest();
     moreOptionsMap.put(mResources.getString(R.string.qti_description_target_deflect),
         showDeflectCall);
   }

   /**
    * Deflect the incoming call.
    */
   private void deflectCall() {
     LogUtil.enterBlock("BottomSheetHelper.onDeflect");
     if(mCall == null ) {
       LogUtil.w("BottomSheetHelper.onDeflect", "mCall is null");
       return;
     }
     String deflectCallNumber = QtiImsExtUtils.getCallDeflectNumber(
          mContext.getContentResolver());
     /* If not set properly, inform via Log */
     if (deflectCallNumber == null) {
       LogUtil.w("BottomSheetHelper.onDeflect",
            "Number not set. Provide the number via IMS settings and retry.");
       return;
     }
     int phoneId = getPhoneId();
     LogUtil.d("BottomSheetHelper.onDeflect", "mCall:" + mCall +
          "deflectCallNumber:" + deflectCallNumber);
     try {
       LogUtil.d("BottomSheetHelper.onDeflect",
            "Sending deflect request with Phone id " + phoneId +
            " to " + deflectCallNumber);
       new QtiImsExtManager(mContext).sendCallDeflectRequest(phoneId,
            deflectCallNumber, imsInterfaceListener);
     } catch (QtiImsException e) {
       LogUtil.e("BottomSheetHelper.onDeflect", "sendCallDeflectRequest exception " + e);
     }
   }

   private int getCallTransferCapabilities() {
     Bundle extras = mCall.getExtras();
     return (extras == null)? 0 :
          extras.getInt(QtiImsExtUtils.QTI_IMS_TRANSFER_EXTRA_KEY, 0);
   }

   private void maybeUpdateTransferInMap() {
     moreOptionsMap.put(mResources.getString(R.string.qti_description_transfer),
         getCallTransferCapabilities() != 0 && !mCall.hasReceivedVideoUpgradeRequest());
   }

   private void manageConferenceCall() {
     final InCallActivity inCallActivity = InCallPresenter.getInstance().getActivity();
     if (inCallActivity == null) {
       LogUtil.w("BottomSheetHelper.manageConferenceCall", "inCallActivity is null");
       return;
     }

     inCallActivity.showConferenceFragment(true);
   }

   private void transferCall() {
     LogUtil.enterBlock("BottomSheetHelper.transferCall");
     if(mCall == null ) {
       LogUtil.w("BottomSheetHelper.transferCall", "mCall is null");
       return;
     }
     displayCallTransferOptions();
   }

   /**
    * The function is called when Call Transfer button gets pressed. The function creates and
    * displays call transfer options.
    */
   private void displayCallTransferOptions() {
     final InCallActivity inCallActivity = InCallPresenter.getInstance().getActivity();
     if (inCallActivity == null) {
       LogUtil.e("BottomSheetHelper.displayCallTransferOptions", "inCallActivity is NULL");
       return;
     }
     final ArrayList<CharSequence> items = getCallTransferOptions();
     AlertDialog.Builder builder = new AlertDialog.Builder(inCallActivity)
          .setTitle(R.string.qti_description_transfer);

     DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
         @Override
         public void onClick(DialogInterface dialog, int item) {
              LogUtil.d("BottomSheetHelper.onCallTransferItemClicked", "" + items.get(item));
              onCallTransferItemClicked(item);
              dialog.dismiss();
         }
     };
     builder.setSingleChoiceItems(items.toArray(new CharSequence[0]), INVALID_INDEX, listener);
     callTransferDialog = builder.create();
     callTransferDialog.show();
   }

   private ArrayList<CharSequence> getCallTransferOptions() {
     final ArrayList<CharSequence> items = new ArrayList<CharSequence>();
     final int transferCapabilities = getCallTransferCapabilities();
     if ((transferCapabilities & QtiImsExtUtils.QTI_IMS_CONSULTATIVE_TRANSFER) != 0) {
       items.add(mResources.getText(R.string.qti_ims_onscreenBlindTransfer));
       items.add(mResources.getText(R.string.qti_ims_onscreenAssuredTransfer));
       items.add(mResources.getText(R.string.qti_ims_onscreenConsultativeTransfer));
     } else if ((transferCapabilities & QtiImsExtUtils.QTI_IMS_BLIND_TRANSFER) != 0) {
       items.add(mResources.getText(R.string.qti_ims_onscreenBlindTransfer));
       items.add(mResources.getText(R.string.qti_ims_onscreenAssuredTransfer));
     }
     return items;
   }

   private void onCallTransferItemClicked(int item) {
     switch(item) {
       case BLIND_TRANSFER:
         callTransferClicked(QtiImsExtUtils.QTI_IMS_BLIND_TRANSFER);
         break;
       case ASSURED_TRANSFER:
         callTransferClicked(QtiImsExtUtils.QTI_IMS_ASSURED_TRANSFER);
         break;
       case CONSULTATIVE_TRANSFER:
         callTransferClicked(QtiImsExtUtils.QTI_IMS_CONSULTATIVE_TRANSFER);
         break;
       default:
         break;
     }
   }

   private void callTransferClicked(int type) {
     String number = QtiImsExtUtils.getCallDeflectNumber(mContext.getContentResolver());
     if (number == null) {
       LogUtil.w("BottomSheetHelper.callTransferClicked", "transfer number error, number is null");
       return;
     }
     int phoneId = getPhoneId();
     try {
       LogUtil.d("BottomSheetHelper.sendCallTransferRequest", "Phoneid-" + phoneId + " type-"
            + type + " number- " + number);
       new QtiImsExtManager(mContext).sendCallTransferRequest(phoneId, type, number,
            imsInterfaceListener);
     } catch (QtiImsException e) {
       LogUtil.e("BottomSheetHelper.sendCallTransferRequest", "exception " + e);
     }
   }

   private void maybeUpdateOneWayVideoOptionsInMap() {
     final boolean showOneWayVideo = isOneWayVideoOptionsVisible();
     moreOptionsMap.put(mResources.getString(R.string.video_rx_label), showOneWayVideo);
     moreOptionsMap.put(mResources.getString(R.string.video_tx_label), showOneWayVideo);
   }

   private void maybeUpdateModifyCallInMap() {
     moreOptionsMap.put(mContext.getResources().getString(R.string.modify_call_label),
        isModifyCallOptionsVisible());
   }

   private void acceptIncomingCallOrUpgradeRequest(int videoState) {
     if (mCall == null) {
       LogUtil.e("BottomSheetHelper.acceptIncomingCallOrUpgradeRequest", "Call is null. Return");
       return;
     }

     if (mCall.hasReceivedVideoUpgradeRequest()) {
       mCall.getVideoTech().acceptVideoRequest(videoState);
     } else {
       mCall.answer(videoState);
     }
   }

    /**
     * The function is called when Modify Call button gets pressed. The function creates and
     * displays modify call options.
     */
    public void displayModifyCallOptions() {
      final InCallActivity inCallActivity = InCallPresenter.getInstance().getActivity();
      if (inCallActivity == null) {
        LogUtil.e("BottomSheetHelper.displayModifyCallOptions", "inCallActivity is NULL");
        return;
      }

      if (mCall == null) {
        LogUtil.e("BottomSheetHelper.displayModifyCallOptions",
            "Can't display modify call options. Call is null");
        return;
      }

      final ArrayList<CharSequence> items = new ArrayList<CharSequence>();
      final ArrayList<Integer> itemToCallType = new ArrayList<Integer>();

      // Prepare the string array and mapping.
      if (QtiCallUtils.hasVoiceCapabilities(mCall) && mCall.isVideoCall()) {
        items.add(mResources.getText(R.string.modify_call_option_voice));
        itemToCallType.add(VideoProfile.STATE_AUDIO_ONLY);
      }

      if (QtiCallUtils.hasReceiveVideoCapabilities(mCall) && !QtiCallUtils.isVideoRxOnly(mCall)) {
        items.add(mResources.getText(R.string.modify_call_option_vt_rx));
        itemToCallType.add(VideoProfile.STATE_RX_ENABLED);
      }

      if (QtiCallUtils.hasTransmitVideoCapabilities(mCall) && !QtiCallUtils.isVideoTxOnly(mCall)) {
        items.add(mResources.getText(R.string.modify_call_option_vt_tx));
        itemToCallType.add(VideoProfile.STATE_TX_ENABLED);
      }

      if (QtiCallUtils.hasReceiveVideoCapabilities(mCall)
          && QtiCallUtils.hasTransmitVideoCapabilities(mCall)
          && !QtiCallUtils.isVideoBidirectional(mCall)) {
        items.add(mResources.getText(R.string.modify_call_option_vt));
        itemToCallType.add(VideoProfile.STATE_BIDIRECTIONAL);
      }

      AlertDialog.Builder builder = new AlertDialog.Builder(inCallActivity);
      builder.setTitle(R.string.modify_call_option_title);

      DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int item) {
            final int selCallType = itemToCallType.get(item);
            Log.v(this, "Videocall: ModifyCall: upgrade/downgrade to "
                + QtiCallUtils.callTypeToString(selCallType));
            changeToVideoClicked(mCall, selCallType);
            dialog.dismiss();
          }
      };
      builder.setSingleChoiceItems(items.toArray(new CharSequence[0]), INVALID_INDEX, listener);
      modifyCallDialog = builder.create();
      modifyCallDialog.show();
    }

    /**
     * Sends a session modify request to the telephony framework
     */
    private void changeToVideoClicked(DialerCall call, int videoState) {
      call.getVideoTech().upgradeToVideo(videoState);
    }

    /**
     * Handles a change to the fullscreen mode of the app.
     *
     * @param isFullscreenMode {@code true} if the app is now fullscreen, {@code false} otherwise.
     */
    @Override
    public void onFullscreenModeChanged(boolean isFullscreenMode) {
      if (isFullscreenMode) {
        dismissBottomSheet();
      }
    }
}
