/* Copyright (c) 2015 - 2017, The Linux Foundation. All rights reserved.
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

import android.content.Context;
import android.os.Bundle;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;
import com.android.incallui.InCallPresenter.InCallDetailsListener;
import com.google.common.base.Preconditions;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashMap;
import java.util.List;

import org.codeaurora.ims.QtiCallConstants;

/**
 * This class listens to incoming events from the {@class InCallDetailsListener}.
 * When call details change, this class is notified and we parse the extras from the details to
 * figure out if session modification cause has been sent when a call upgrades/downgrades and
 * notify the {@class InCallMessageController} to display the indication on UI.
 */
public class SessionModificationCauseNotifier implements InCallDetailsListener, CallList.Listener {

    private final List<InCallSessionModificationCauseListener> mSessionModificationCauseListeners
            = new CopyOnWriteArrayList<>();

    private static SessionModificationCauseNotifier sSessionModificationCauseNotifier;
    private final HashMap<String, Integer> mSessionModificationCauseMap = new HashMap<>();
    private Context mContext;
    /**
     * Returns a singleton instance of {@class SessionModificationCauseNotifier}
     */
    public static synchronized SessionModificationCauseNotifier getInstance() {
        if (sSessionModificationCauseNotifier == null) {
            sSessionModificationCauseNotifier = new SessionModificationCauseNotifier();
        }
        return sSessionModificationCauseNotifier;
    }

    /**
     * Adds a new session modification cause listener. Users interested in this cause
     * should add a listener of type {@class InCallSessionModificationCauseListener}
     */
    public void addListener(InCallSessionModificationCauseListener listener) {
        Preconditions.checkNotNull(listener);
        mSessionModificationCauseListeners.add(listener);
    }

    /**
     * Removes an existing session modification cause listener. Users listening to any cause
     * changes when not interested any more can de-register an existing listener of type
     * {@class InCallSessionModificationCauseListener}
     */
    public void removeListener(InCallSessionModificationCauseListener listener) {
        if (listener != null) {
            mSessionModificationCauseListeners.remove(listener);
        } else {
            Log.e(this, "Can't remove null listener");
        }
    }

    /**
     * Private constructor. Must use getInstance() to get this singleton.
     */
    private SessionModificationCauseNotifier() {
    }

    private int getSessionModificationCause(Bundle callExtras) {
        return callExtras.getInt(QtiCallConstants.SESSION_MODIFICATION_CAUSE_EXTRA_KEY,
                QtiCallConstants.CAUSE_CODE_UNSPECIFIED);
    }

    public void setUp(Context context){
        mContext = context;
    }

    /**
     * Overrides onDetailsChanged method of {@class InCallDetailsListener}. We are
     * notified when call details change and extract the session modification cause from the
     * extras, detect if the cause has changed and notify all registered listeners.
     */
    @Override
    public void onDetailsChanged(DialerCall call, android.telecom.Call.Details details) {
        Log.d(this, "onDetailsChanged: - call: " + call + "details: " + details);
        final Bundle extras =  (call != null && details != null) ? details.getExtras() : null;

        if (extras == null) {
            return;
        }

        final String callId = call.getId();

        final int oldSessionModificationCause = mSessionModificationCauseMap.containsKey(callId) ?
                mSessionModificationCauseMap.get(callId) : QtiCallConstants.CAUSE_CODE_UNSPECIFIED;
        final int newSessionModificationCause = getSessionModificationCause(extras);

        if (oldSessionModificationCause == newSessionModificationCause) {
            return;
        }

        mSessionModificationCauseMap.put(callId, newSessionModificationCause);
        // Notify all listeners only when there is a valid value
        if (newSessionModificationCause != QtiCallConstants.CAUSE_CODE_UNSPECIFIED) {
            Preconditions.checkNotNull(mSessionModificationCauseListeners);
            Log.i(this, "onSessionModificationCauseChanged: - call: " + call +
                         "sessionModificationCause: " + newSessionModificationCause);
            for (InCallSessionModificationCauseListener listener :
                    mSessionModificationCauseListeners) {
                listener.onSessionModificationCauseChanged(call, newSessionModificationCause);
            }

            if (newSessionModificationCause == QtiCallConstants.
                   CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_GENERIC){
               QtiCallUtils.displayLongToast(mContext,
                   getSessionModificationCauseResourceId(newSessionModificationCause));
            } else {
              QtiCallUtils.displayToast(mContext,
                   getSessionModificationCauseResourceId(newSessionModificationCause));
            }
        }
    }

    /**
     * This method returns the string resource id (i.e. display string) that corresponds
     * to the session modification cause code.
     */
    private static int getSessionModificationCauseResourceId(int cause) {
        switch(cause) {
            case QtiCallConstants.CAUSE_CODE_UNSPECIFIED:
                return R.string.session_modify_cause_unspecified;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_UPGRADE_LOCAL_REQ:
                return R.string.session_modify_cause_upgrade_local_request;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_UPGRADE_REMOTE_REQ:
                return R.string.session_modify_cause_upgrade_remote_request;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_LOCAL_REQ:
                return R.string.session_modify_cause_downgrade_local_request;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_REMOTE_REQ:
                return R.string.session_modify_cause_downgrade_remote_request;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_RTP_TIMEOUT:
                return R.string.session_modify_cause_downgrade_rtp_timeout;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_QOS:
                return R.string.session_modify_cause_downgrade_qos;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_PACKET_LOSS:
                return R.string.session_modify_cause_downgrade_packet_loss;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_LOW_THRPUT:
                return R.string.session_modify_cause_downgrade_low_thrput;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_THERM_MITIGATION:
                return R.string.session_modify_cause_downgrade_thermal_mitigation;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_LIPSYNC:
                return R.string.session_modify_cause_downgrade_lipsync;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_GENERIC:
                return R.string.session_modify_cause_downgrade_generic;
            case QtiCallConstants.CAUSE_CODE_SESSION_MODIFY_DOWNGRADE_GENERIC_ERROR:
            default:
                return R.string.session_modify_cause_downgrade_generic_error;
        }
    }

    /**
     * This method overrides onDisconnect method of {@interface CallList.Listener}
     */
    @Override
    public void onDisconnect(final DialerCall call) {
        Log.d(this, "onDisconnect: call: " + call);
        mSessionModificationCauseMap.remove(call.getId());
    }

    @Override
    public void onUpgradeToVideo(DialerCall call) {
        //NO-OP
    }

    @Override
    public void onIncomingCall(DialerCall call) {
        //NO-OP
    }

    @Override
    public void onCallListChange(CallList callList) {
        //NO-OP
    }

    @Override
    public void onInternationalCallOnWifi(DialerCall call) {
        //NO-OP
    }
    @Override
    public void onSessionModificationStateChange(DialerCall call) {
        //NO-OP
    }

    @Override
    public void onWiFiToLteHandover(DialerCall call) {
        //NO-OP
    }

    @Override
    public void onHandoverToWifiFailed(DialerCall call) {
        //NO-OP
    }
}
