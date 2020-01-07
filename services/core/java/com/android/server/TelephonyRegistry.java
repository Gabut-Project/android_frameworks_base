/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import static android.telephony.TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED;
import static android.telephony.TelephonyRegistryManager.SIM_ACTIVATION_TYPE_DATA;
import static android.telephony.TelephonyRegistryManager.SIM_ACTIVATION_TYPE_VOICE;

import static java.util.Arrays.copyOf;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.LinkProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.Annotation;
import android.telephony.Annotation.DataFailureCause;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.Annotation.SrvccState;
import android.telephony.CallAttributes;
import android.telephony.CallQuality;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.DataFailCause;
import android.telephony.DisconnectCause;
import android.telephony.LocationAccessPolicy;
import android.telephony.PhoneCapability;
import android.telephony.PhoneStateListener;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.PreciseDisconnectCause;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;
import android.util.LocalLog;
import android.util.StatsLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.IPhoneStateListener;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.am.BatteryStatsService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Since phone process can be restarted, this class provides a centralized place
 * that applications can register and be called back from.
 *
 * Change-Id: I450c968bda93767554b5188ee63e10c9f43c5aa4 fixes bugs 16148026
 * and 15973975 by saving the phoneId of the registrant and then using the
 * phoneId when deciding to to make a callback. This is necessary because
 * a subId changes from to a dummy value when a SIM is removed and thus won't
 * compare properly. Because SubscriptionManager.getPhoneId(int subId) handles
 * the dummy value conversion we properly do the callbacks.
 *
 * Eventually we may want to remove the notion of dummy value but for now this
 * looks like the best approach.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class TelephonyRegistry extends ITelephonyRegistry.Stub {
    private static final String TAG = "TelephonyRegistry";
    private static final boolean DBG = false; // STOPSHIP if true
    private static final boolean DBG_LOC = false; // STOPSHIP if true
    private static final boolean VDBG = false; // STOPSHIP if true

    private static class Record {
        Context context;

        String callingPackage;
        String callingFeatureId;

        IBinder binder;

        TelephonyRegistryDeathRecipient deathRecipient;

        IPhoneStateListener callback;
        IOnSubscriptionsChangedListener onSubscriptionsChangedListenerCallback;
        IOnSubscriptionsChangedListener onOpportunisticSubscriptionsChangedListenerCallback;

        int callerUid;
        int callerPid;

        int events;

        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;

        boolean matchPhoneStateListenerEvent(int events) {
            return (callback != null) && ((events & this.events) != 0);
        }

        boolean matchOnSubscriptionsChangedListener() {
            return (onSubscriptionsChangedListenerCallback != null);
        }

        boolean matchOnOpportunisticSubscriptionsChangedListener() {
            return (onOpportunisticSubscriptionsChangedListenerCallback != null);
        }

        boolean canReadCallLog() {
            try {
                return TelephonyPermissions.checkReadCallLog(
                        context, subId, callerPid, callerUid, callingPackage, callingFeatureId);
            } catch (SecurityException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "{callingPackage=" + callingPackage + " binder=" + binder
                    + " callback=" + callback
                    + " onSubscriptionsChangedListenererCallback="
                    + onSubscriptionsChangedListenerCallback
                    + " onOpportunisticSubscriptionsChangedListenererCallback="
                    + onOpportunisticSubscriptionsChangedListenerCallback
                    + " callerUid=" + callerUid + " subId=" + subId + " phoneId=" + phoneId
                    + " events=" + Integer.toHexString(events) + "}";
        }
    }

    private final Context mContext;

    // access should be inside synchronized (mRecords) for these two fields
    private final ArrayList<IBinder> mRemoveList = new ArrayList<IBinder>();
    private final ArrayList<Record> mRecords = new ArrayList<Record>();

    private final IBatteryStats mBatteryStats;

    private final AppOpsManager mAppOps;

    private boolean mHasNotifySubscriptionInfoChangedOccurred = false;

    private boolean mHasNotifyOpportunisticSubscriptionInfoChangedOccurred = false;

    private int mNumPhones;

    private int[] mCallState;

    private String[] mCallIncomingNumber;

    private ServiceState[] mServiceState;

    private int[] mVoiceActivationState;

    private int[] mDataActivationState;

    private boolean[] mUserMobileDataState;

    private SignalStrength[] mSignalStrength;

    private boolean[] mMessageWaiting;

    private boolean[] mCallForwarding;

    private int[] mDataActivity;

    // Connection state of default APN type data (i.e. internet) of phones
    private int[] mDataConnectionState;

    private Bundle[] mCellLocation;

    private int[] mDataConnectionNetworkType;

    private ArrayList<List<CellInfo>> mCellInfo = null;

    private Map<Integer, List<EmergencyNumber>> mEmergencyNumberList;

    private EmergencyNumber[] mOutgoingSmsEmergencyNumber;

    private EmergencyNumber[] mOutgoingCallEmergencyNumber;

    private CallQuality[] mCallQuality;

    private CallAttributes[] mCallAttributes;

    // network type of the call associated with the mCallAttributes and mCallQuality
    private int[] mCallNetworkType;

    private int[] mSrvccState;

    private int mDefaultSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private int mDefaultPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;

    private int[] mRingingCallState;

    private int[] mForegroundCallState;

    private int[] mBackgroundCallState;

    private PreciseCallState[] mPreciseCallState;

    private int[] mCallDisconnectCause;

    private List<ImsReasonInfo> mImsReasonInfo = null;

    private int[] mCallPreciseDisconnectCause;

    private boolean mCarrierNetworkChangeState = false;

    private PhoneCapability mPhoneCapability = null;

    private int mActiveDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    @RadioPowerState
    private int mRadioPowerState = TelephonyManager.RADIO_POWER_UNAVAILABLE;

    private final LocalLog mLocalLog = new LocalLog(100);

    private final LocalLog mListenLog = new LocalLog(100);

    // Per-phoneMap of APN Type to DataConnectionState
    private List<Map<String, PreciseDataConnectionState>> mPreciseDataConnectionStates =
            new ArrayList<Map<String, PreciseDataConnectionState>>();

    // Nothing here yet, but putting it here in case we want to add more in the future.
    static final int ENFORCE_COARSE_LOCATION_PERMISSION_MASK = 0;

    static final int ENFORCE_FINE_LOCATION_PERMISSION_MASK =
            PhoneStateListener.LISTEN_CELL_LOCATION
                    | PhoneStateListener.LISTEN_CELL_INFO;

    static final int ENFORCE_PHONE_STATE_PERMISSION_MASK =
                PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR
                        | PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR
                        | PhoneStateListener.LISTEN_EMERGENCY_NUMBER_LIST;

    static final int PRECISE_PHONE_STATE_PERMISSION_MASK =
                PhoneStateListener.LISTEN_PRECISE_CALL_STATE |
                PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE;

    static final int READ_ACTIVE_EMERGENCY_SESSION_PERMISSION_MASK =
            PhoneStateListener.LISTEN_OUTGOING_EMERGENCY_CALL
                    | PhoneStateListener.LISTEN_OUTGOING_EMERGENCY_SMS;

    private static final int MSG_USER_SWITCHED = 1;
    private static final int MSG_UPDATE_DEFAULT_SUB = 2;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_SWITCHED: {
                    if (VDBG) log("MSG_USER_SWITCHED userId=" + msg.arg1);
                    int numPhones = getTelephonyManager().getPhoneCount();
                    for (int sub = 0; sub < numPhones; sub++) {
                        TelephonyRegistry.this.notifyCellLocationForSubscriber(sub,
                                mCellLocation[sub]);
                    }
                    break;
                }
                case MSG_UPDATE_DEFAULT_SUB: {
                    int newDefaultPhoneId = msg.arg1;
                    int newDefaultSubId = msg.arg2;
                    if (VDBG) {
                        log("MSG_UPDATE_DEFAULT_SUB:current mDefaultSubId=" + mDefaultSubId
                                + " current mDefaultPhoneId=" + mDefaultPhoneId
                                + " newDefaultSubId=" + newDefaultSubId
                                + " newDefaultPhoneId=" + newDefaultPhoneId);
                    }

                    //Due to possible risk condition,(notify call back using the new
                    //defaultSubId comes before new defaultSubId update) we need to recall all
                    //possible missed notify callback
                    synchronized (mRecords) {
                        for (Record r : mRecords) {
                            if(r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                                checkPossibleMissNotify(r, newDefaultPhoneId);
                            }
                        }
                        handleRemoveListLocked();
                    }
                    mDefaultSubId = newDefaultSubId;
                    mDefaultPhoneId = newDefaultPhoneId;
                    mLocalLog.log("Default subscription updated: mDefaultPhoneId="
                            + mDefaultPhoneId + ", mDefaultSubId=" + mDefaultSubId);
                }
            }
        }
    };

    private class TelephonyRegistryDeathRecipient implements IBinder.DeathRecipient {

        private final IBinder binder;

        TelephonyRegistryDeathRecipient(IBinder binder) {
            this.binder = binder;
        }

        @Override
        public void binderDied() {
            if (DBG) log("binderDied " + binder);
            remove(binder);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (VDBG) log("mBroadcastReceiver: action=" + action);
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (DBG) log("onReceive: userHandle=" + userHandle);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCHED, userHandle, 0));
            } else if (action.equals(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED)) {
                int newDefaultSubId = intent.getIntExtra(
                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.getDefaultSubscriptionId());
                int newDefaultPhoneId = intent.getIntExtra(
                        SubscriptionManager.EXTRA_SLOT_INDEX,
                        SubscriptionManager.getPhoneId(newDefaultSubId));
                if (DBG) {
                    log("onReceive:current mDefaultSubId=" + mDefaultSubId
                            + " current mDefaultPhoneId=" + mDefaultPhoneId
                            + " newDefaultSubId=" + newDefaultSubId
                            + " newDefaultPhoneId=" + newDefaultPhoneId);
                }

                if (validatePhoneId(newDefaultPhoneId)
                        && (newDefaultSubId != mDefaultSubId
                                || newDefaultPhoneId != mDefaultPhoneId)) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_DEFAULT_SUB,
                            newDefaultPhoneId, newDefaultSubId));
                }
            } else if (action.equals(ACTION_MULTI_SIM_CONFIG_CHANGED)) {
                onMultiSimConfigChanged();
            }
        }
    };

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    private void onMultiSimConfigChanged() {
        int oldNumPhones = mNumPhones;
        mNumPhones = getTelephonyManager().getActiveModemCount();
        if (oldNumPhones == mNumPhones) return;

        if (DBG) {
            log("TelephonyRegistry: activeModemCount changed from " + oldNumPhones
                    + " to " + mNumPhones);
        }
        mCallState = copyOf(mCallState, mNumPhones);
        mDataActivity = copyOf(mCallState, mNumPhones);
        mDataConnectionState = copyOf(mCallState, mNumPhones);
        mDataConnectionNetworkType = copyOf(mCallState, mNumPhones);
        mCallIncomingNumber = copyOf(mCallIncomingNumber, mNumPhones);
        mServiceState = copyOf(mServiceState, mNumPhones);
        mVoiceActivationState = copyOf(mVoiceActivationState, mNumPhones);
        mDataActivationState = copyOf(mDataActivationState, mNumPhones);
        mUserMobileDataState = copyOf(mUserMobileDataState, mNumPhones);
        mSignalStrength = copyOf(mSignalStrength, mNumPhones);
        mMessageWaiting = copyOf(mMessageWaiting, mNumPhones);
        mCallForwarding = copyOf(mCallForwarding, mNumPhones);
        mCellLocation = copyOf(mCellLocation, mNumPhones);
        mSrvccState = copyOf(mSrvccState, mNumPhones);
        mPreciseCallState = copyOf(mPreciseCallState, mNumPhones);
        mForegroundCallState = copyOf(mForegroundCallState, mNumPhones);
        mBackgroundCallState = copyOf(mBackgroundCallState, mNumPhones);
        mRingingCallState = copyOf(mRingingCallState, mNumPhones);
        mCallDisconnectCause = copyOf(mCallDisconnectCause, mNumPhones);
        mCallPreciseDisconnectCause = copyOf(mCallPreciseDisconnectCause, mNumPhones);
        mCallQuality = copyOf(mCallQuality, mNumPhones);
        mCallNetworkType = copyOf(mCallNetworkType, mNumPhones);
        mCallAttributes = copyOf(mCallAttributes, mNumPhones);
        mOutgoingCallEmergencyNumber = copyOf(mOutgoingCallEmergencyNumber, mNumPhones);
        mOutgoingSmsEmergencyNumber = copyOf(mOutgoingSmsEmergencyNumber, mNumPhones);

        // ds -> ss switch.
        if (mNumPhones < oldNumPhones) {
            cutListToSize(mCellInfo, mNumPhones);
            cutListToSize(mImsReasonInfo, mNumPhones);
            cutListToSize(mPreciseDataConnectionStates, mNumPhones);
            return;
        }

        // mNumPhones > oldNumPhones: ss -> ds switch
        for (int i = oldNumPhones; i < mNumPhones; i++) {
            mCallState[i] =  TelephonyManager.CALL_STATE_IDLE;
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mDataConnectionState[i] = TelephonyManager.DATA_UNKNOWN;
            mVoiceActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mDataActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mCallIncomingNumber[i] =  "";
            mServiceState[i] =  new ServiceState();
            mSignalStrength[i] =  new SignalStrength();
            mUserMobileDataState[i] = false;
            mMessageWaiting[i] =  false;
            mCallForwarding[i] =  false;
            mCellLocation[i] = new Bundle();
            mCellInfo.add(i, null);
            mImsReasonInfo.add(i, null);
            mSrvccState[i] = TelephonyManager.SRVCC_STATE_HANDOVER_NONE;
            mCallDisconnectCause[i] = DisconnectCause.NOT_VALID;
            mCallPreciseDisconnectCause[i] = PreciseDisconnectCause.NOT_VALID;
            mCallQuality[i] = createCallQuality();
            mCallAttributes[i] = new CallAttributes(createPreciseCallState(),
                    TelephonyManager.NETWORK_TYPE_UNKNOWN, createCallQuality());
            mCallNetworkType[i] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            mPreciseCallState[i] = createPreciseCallState();
            mRingingCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mForegroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mBackgroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mPreciseDataConnectionStates.add(new HashMap<String, PreciseDataConnectionState>());
        }

        // Note that location can be null for non-phone builds like
        // like the generic one.
        CellLocation  location = CellLocation.getEmpty();
        if (location != null) {
            for (int i = oldNumPhones; i < mNumPhones; i++) {
                location.fillInNotifierBundle(mCellLocation[i]);
            }
        }
    }

    private void cutListToSize(List list, int size) {
        if (list == null) return;

        while (list.size() > size) {
            list.remove(list.size() - 1);
        }
    }

    // we keep a copy of all of the state so we can send it out when folks
    // register for it
    //
    // In these calls we call with the lock held. This is safe becasuse remote
    // calls go through a oneway interface and local calls going through a
    // handler before they get to app code.

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public TelephonyRegistry(Context context) {
        CellLocation  location = CellLocation.getEmpty();

        mContext = context;
        mBatteryStats = BatteryStatsService.getService();

        int numPhones = getTelephonyManager().getActiveModemCount();
        if (DBG) log("TelephonyRegistry: ctor numPhones=" + numPhones);
        mNumPhones = numPhones;
        mCallState = new int[numPhones];
        mDataActivity = new int[numPhones];
        mDataConnectionState = new int[numPhones];
        mDataConnectionNetworkType = new int[numPhones];
        mCallIncomingNumber = new String[numPhones];
        mServiceState = new ServiceState[numPhones];
        mVoiceActivationState = new int[numPhones];
        mDataActivationState = new int[numPhones];
        mUserMobileDataState = new boolean[numPhones];
        mSignalStrength = new SignalStrength[numPhones];
        mMessageWaiting = new boolean[numPhones];
        mCallForwarding = new boolean[numPhones];
        mCellLocation = new Bundle[numPhones];
        mSrvccState = new int[numPhones];
        mPreciseCallState = new PreciseCallState[numPhones];
        mForegroundCallState = new int[numPhones];
        mBackgroundCallState = new int[numPhones];
        mRingingCallState = new int[numPhones];
        mCallDisconnectCause = new int[numPhones];
        mCallPreciseDisconnectCause = new int[numPhones];
        mCallQuality = new CallQuality[numPhones];
        mCallNetworkType = new int[numPhones];
        mCallAttributes = new CallAttributes[numPhones];
        mPreciseDataConnectionStates = new ArrayList<>();
        mCellInfo = new ArrayList<>();
        mImsReasonInfo = new ArrayList<>();
        mEmergencyNumberList = new HashMap<>();
        mOutgoingCallEmergencyNumber = new EmergencyNumber[numPhones];
        mOutgoingSmsEmergencyNumber = new EmergencyNumber[numPhones];
        for (int i = 0; i < numPhones; i++) {
            mCallState[i] =  TelephonyManager.CALL_STATE_IDLE;
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mDataConnectionState[i] = TelephonyManager.DATA_UNKNOWN;
            mVoiceActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mDataActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mCallIncomingNumber[i] =  "";
            mServiceState[i] =  new ServiceState();
            mSignalStrength[i] =  new SignalStrength();
            mUserMobileDataState[i] = false;
            mMessageWaiting[i] =  false;
            mCallForwarding[i] =  false;
            mCellLocation[i] = new Bundle();
            mCellInfo.add(i, null);
            mImsReasonInfo.add(i, null);
            mSrvccState[i] = TelephonyManager.SRVCC_STATE_HANDOVER_NONE;
            mCallDisconnectCause[i] = DisconnectCause.NOT_VALID;
            mCallPreciseDisconnectCause[i] = PreciseDisconnectCause.NOT_VALID;
            mCallQuality[i] = createCallQuality();
            mCallAttributes[i] = new CallAttributes(createPreciseCallState(),
                    TelephonyManager.NETWORK_TYPE_UNKNOWN, createCallQuality());
            mCallNetworkType[i] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            mPreciseCallState[i] = createPreciseCallState();
            mRingingCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mForegroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mBackgroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mPreciseDataConnectionStates.add(new HashMap<String, PreciseDataConnectionState>());
        }

        // Note that location can be null for non-phone builds like
        // like the generic one.
        if (location != null) {
            for (int i = 0; i < numPhones; i++) {
                location.fillInNotifierBundle(mCellLocation[i]);
            }
        }

        mAppOps = mContext.getSystemService(AppOpsManager.class);
    }

    public void systemRunning() {
        // Watch for interesting updates
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        filter.addAction(ACTION_MULTI_SIM_CONFIG_CHANGED);
        log("systemRunning register for intents");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void addOnSubscriptionsChangedListener(String callingPackage, String callingFeatureId,
            IOnSubscriptionsChangedListener callback) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen oscl: E pkg=" + callingPackage + " myUserId=" + UserHandle.myUserId()
                + " callerUserId="  + callerUserId + " callback=" + callback
                + " callback.asBinder=" + callback.asBinder());
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            Record r = add(b);

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.onSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.events = 0;
            if (DBG) {
                log("listen oscl:  Register r=" + r);
            }
            // Always notify when registration occurs if there has been a notification.
            if (mHasNotifySubscriptionInfoChangedOccurred) {
                try {
                    if (VDBG) log("listen oscl: send to r=" + r);
                    r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    if (VDBG) log("listen oscl: sent to r=" + r);
                } catch (RemoteException e) {
                    if (VDBG) log("listen oscl: remote exception sending to r=" + r + " e=" + e);
                    remove(r.binder);
                }
            } else {
                log("listen oscl: mHasNotifySubscriptionInfoChangedOccurred==false no callback");
            }
        }
    }

    @Override
    public void removeOnSubscriptionsChangedListener(String pkgForDebug,
            IOnSubscriptionsChangedListener callback) {
        if (DBG) log("listen oscl: Unregister");
        remove(callback.asBinder());
    }


    @Override
    public void addOnOpportunisticSubscriptionsChangedListener(String callingPackage,
            String callingFeatureId, IOnSubscriptionsChangedListener callback) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen ooscl: E pkg=" + callingPackage + " myUserId=" + UserHandle.myUserId()
                    + " callerUserId="  + callerUserId + " callback=" + callback
                    + " callback.asBinder=" + callback.asBinder());
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            Record r = add(b);

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.onOpportunisticSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.events = 0;
            if (DBG) {
                log("listen ooscl:  Register r=" + r);
            }
            // Always notify when registration occurs if there has been a notification.
            if (mHasNotifyOpportunisticSubscriptionInfoChangedOccurred) {
                try {
                    if (VDBG) log("listen ooscl: send to r=" + r);
                    r.onOpportunisticSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    if (VDBG) log("listen ooscl: sent to r=" + r);
                } catch (RemoteException e) {
                    if (VDBG) log("listen ooscl: remote exception sending to r=" + r + " e=" + e);
                    remove(r.binder);
                }
            } else {
                log("listen ooscl: hasNotifyOpptSubInfoChangedOccurred==false no callback");
            }
        }
    }

    @Override
    public void notifySubscriptionInfoChanged() {
        if (VDBG) log("notifySubscriptionInfoChanged:");
        synchronized (mRecords) {
            if (!mHasNotifySubscriptionInfoChangedOccurred) {
                log("notifySubscriptionInfoChanged: first invocation mRecords.size="
                        + mRecords.size());
            }
            mHasNotifySubscriptionInfoChangedOccurred = true;
            mRemoveList.clear();
            for (Record r : mRecords) {
                if (r.matchOnSubscriptionsChangedListener()) {
                    try {
                        if (VDBG) log("notifySubscriptionInfoChanged: call osc to r=" + r);
                        r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                        if (VDBG) log("notifySubscriptionInfoChanged: done osc to r=" + r);
                    } catch (RemoteException ex) {
                        if (VDBG) log("notifySubscriptionInfoChanged: RemoteException r=" + r);
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyOpportunisticSubscriptionInfoChanged() {
        if (VDBG) log("notifyOpptSubscriptionInfoChanged:");
        synchronized (mRecords) {
            if (!mHasNotifyOpportunisticSubscriptionInfoChangedOccurred) {
                log("notifyOpptSubscriptionInfoChanged: first invocation mRecords.size="
                        + mRecords.size());
            }
            mHasNotifyOpportunisticSubscriptionInfoChangedOccurred = true;
            mRemoveList.clear();
            for (Record r : mRecords) {
                if (r.matchOnOpportunisticSubscriptionsChangedListener()) {
                    try {
                        if (VDBG) log("notifyOpptSubChanged: call oosc to r=" + r);
                        r.onOpportunisticSubscriptionsChangedListenerCallback
                                .onSubscriptionsChanged();
                        if (VDBG) log("notifyOpptSubChanged: done oosc to r=" + r);
                    } catch (RemoteException ex) {
                        if (VDBG) log("notifyOpptSubChanged: RemoteException r=" + r);
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Deprecated
    @Override
    public void listen(String callingPackage, IPhoneStateListener callback, int events,
            boolean notifyNow) {
        listenWithFeature(callingPackage, null, callback, events, notifyNow);
    }

    @Override
    public void listenWithFeature(String callingPackage, String callingFeatureId,
            IPhoneStateListener callback, int events, boolean notifyNow) {
        listenForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, callingPackage,
                callingFeatureId, callback, events, notifyNow);
    }

    @Override
    public void listenForSubscriber(int subId, String callingPackage, String callingFeatureId,
            IPhoneStateListener callback, int events, boolean notifyNow) {
        listen(callingPackage, callingFeatureId, callback, events, notifyNow, subId);
    }

    private void listen(String callingPackage, @Nullable String callingFeatureId,
            IPhoneStateListener callback, int events, boolean notifyNow, int subId) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        String str = "listen: E pkg=" + callingPackage + " events=0x" + Integer.toHexString(events)
                + " notifyNow=" + notifyNow + " subId=" + subId + " myUserId="
                + UserHandle.myUserId() + " callerUserId=" + callerUserId;
        mListenLog.log(str);
        if (VDBG) {
            log(str);
        }

        if (events != PhoneStateListener.LISTEN_NONE) {
            // Checks permission and throws SecurityException for disallowed operations. For pre-M
            // apps whose runtime permission has been revoked, we return immediately to skip sending
            // events to the app without crashing it.
            if (!checkListenerPermission(events, subId, callingPackage, callingFeatureId,
                    "listen")) {
                return;
            }

            int phoneId = SubscriptionManager.getPhoneId(subId);
            synchronized (mRecords) {
                // register
                IBinder b = callback.asBinder();
                Record r = add(b);

                if (r == null) {
                    return;
                }

                r.context = mContext;
                r.callback = callback;
                r.callingPackage = callingPackage;
                r.callingFeatureId = callingFeatureId;
                r.callerUid = Binder.getCallingUid();
                r.callerPid = Binder.getCallingPid();
                // Legacy applications pass SubscriptionManager.DEFAULT_SUB_ID,
                // force all illegal subId to SubscriptionManager.DEFAULT_SUB_ID
                if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                    r.subId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
                 } else {//APP specify subID
                    r.subId = subId;
                }
                r.phoneId = phoneId;
                r.events = events;
                if (DBG) {
                    log("listen:  Register r=" + r + " r.subId=" + r.subId + " phoneId=" + phoneId);
                }
                if (notifyNow && validatePhoneId(phoneId)) {
                    if ((events & PhoneStateListener.LISTEN_SERVICE_STATE) != 0) {
                        try {
                            if (VDBG) log("listen: call onSSC state=" + mServiceState[phoneId]);
                            ServiceState rawSs = new ServiceState(mServiceState[phoneId]);
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                r.callback.onServiceStateChanged(rawSs);
                            } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                                r.callback.onServiceStateChanged(rawSs.sanitizeLocationInfo(false));
                            } else {
                                r.callback.onServiceStateChanged(rawSs.sanitizeLocationInfo(true));
                            }
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTH) != 0) {
                        try {
                            int gsmSignalStrength = mSignalStrength[phoneId]
                                    .getGsmSignalStrength();
                            r.callback.onSignalStrengthChanged((gsmSignalStrength == 99 ? -1
                                    : gsmSignalStrength));
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) != 0) {
                        try {
                            r.callback.onMessageWaitingIndicatorChanged(
                                    mMessageWaiting[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) != 0) {
                        try {
                            r.callback.onCallForwardingIndicatorChanged(
                                    mCallForwarding[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_LOCATION)) {
                        try {
                            if (DBG_LOC) log("listen: mCellLocation = "
                                    + mCellLocation[phoneId]);
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                r.callback.onCellLocationChanged(
                                        new Bundle(mCellLocation[phoneId]));
                            }
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CALL_STATE) != 0) {
                        try {
                            r.callback.onCallStateChanged(mCallState[phoneId],
                                     getCallIncomingNumber(r, phoneId));
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) != 0) {
                        try {
                            r.callback.onDataConnectionStateChanged(mDataConnectionState[phoneId],
                                mDataConnectionNetworkType[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_DATA_ACTIVITY) != 0) {
                        try {
                            r.callback.onDataActivity(mDataActivity[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) != 0) {
                        try {
                            r.callback.onSignalStrengthsChanged(mSignalStrength[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_INFO)) {
                        try {
                            if (DBG_LOC) log("listen: mCellInfo[" + phoneId + "] = "
                                    + mCellInfo.get(phoneId));
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
                            }
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_PRECISE_CALL_STATE) != 0) {
                        try {
                            r.callback.onPreciseCallStateChanged(mPreciseCallState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CALL_DISCONNECT_CAUSES) != 0) {
                        try {
                            r.callback.onCallDisconnectCauseChanged(mCallDisconnectCause[phoneId],
                                    mCallPreciseDisconnectCause[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_IMS_CALL_DISCONNECT_CAUSES) != 0) {
                        try {
                            r.callback.onImsCallDisconnectCauseChanged(mImsReasonInfo.get(phoneId));
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE) != 0) {
                        try {
                            for (PreciseDataConnectionState pdcs
                                    : mPreciseDataConnectionStates.get(phoneId).values()) {
                                r.callback.onPreciseDataConnectionStateChanged(pdcs);
                            }
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE) != 0) {
                        try {
                            r.callback.onCarrierNetworkChange(mCarrierNetworkChangeState);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_VOICE_ACTIVATION_STATE) !=0) {
                        try {
                            r.callback.onVoiceActivationStateChanged(
                                    mVoiceActivationState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_DATA_ACTIVATION_STATE) !=0) {
                        try {
                            r.callback.onDataActivationStateChanged(mDataActivationState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) != 0) {
                        try {
                            r.callback.onUserMobileDataStateChanged(mUserMobileDataState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_EMERGENCY_NUMBER_LIST) != 0) {
                        try {
                            r.callback.onEmergencyNumberListChanged(mEmergencyNumberList);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_PHONE_CAPABILITY_CHANGE) != 0) {
                        try {
                            r.callback.onPhoneCapabilityChanged(mPhoneCapability);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener
                            .LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE) != 0) {
                        try {
                            r.callback.onActiveDataSubIdChanged(mActiveDataSubId);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED) != 0) {
                        try {
                            r.callback.onRadioPowerStateChanged(mRadioPowerState);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED) != 0) {
                        try {
                            r.callback.onSrvccStateChanged(mSrvccState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED) != 0) {
                        try {
                            r.callback.onCallAttributesChanged(mCallAttributes[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                }
            }
        } else {
            if(DBG) log("listen: Unregister");
            remove(callback.asBinder());
        }
    }

    private String getCallIncomingNumber(Record record, int phoneId) {
        // Only reveal the incoming number if the record has read call log permission.
        return record.canReadCallLog() ? mCallIncomingNumber[phoneId] : "";
    }

    private Record add(IBinder binder) {
        Record r;

        synchronized (mRecords) {
            final int N = mRecords.size();
            for (int i = 0; i < N; i++) {
                r = mRecords.get(i);
                if (binder == r.binder) {
                    // Already existed.
                    return r;
                }
            }
            r = new Record();
            r.binder = binder;
            r.deathRecipient = new TelephonyRegistryDeathRecipient(binder);

            try {
                binder.linkToDeath(r.deathRecipient, 0);
            } catch (RemoteException e) {
                if (VDBG) log("LinkToDeath remote exception sending to r=" + r + " e=" + e);
                // Binder already died. Return null.
                return null;
            }

            mRecords.add(r);
            if (DBG) log("add new record");
        }

        return r;
    }

    private void remove(IBinder binder) {
        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            for (int i = 0; i < recordCount; i++) {
                Record r = mRecords.get(i);
                if (r.binder == binder) {
                    if (DBG) {
                        log("remove: binder=" + binder + " r.callingPackage " + r.callingPackage
                                + " r.callback " + r.callback);
                    }

                    if (r.deathRecipient != null) {
                        try {
                            binder.unlinkToDeath(r.deathRecipient, 0);
                        } catch (NoSuchElementException e) {
                            if (VDBG) log("UnlinkToDeath NoSuchElementException sending to r="
                                    + r + " e=" + e);
                        }
                    }

                    mRecords.remove(i);
                    return;
                }
            }
        }
    }

    public void notifyCallStateForAllSubs(int state, String phoneNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }

        if (VDBG) {
            log("notifyCallStateForAllSubs: state=" + state + " phoneNumber=" + phoneNumber);
        }

        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_CALL_STATE) &&
                        (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                    try {
                        // Ensure the listener has read call log permission; if they do not return
                        // an empty phone number.
                        String phoneNumberOrEmpty = r.canReadCallLog() ? phoneNumber : "";
                        r.callback.onCallStateChanged(state, phoneNumberOrEmpty);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }

        // Called only by Telecomm to communicate call state across different phone accounts. So
        // there is no need to add a valid subId or slotId.
        broadcastCallStateChanged(state, phoneNumber,
                SubscriptionManager.INVALID_PHONE_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    public void notifyCallState(int phoneId, int subId, int state, String incomingNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallState: subId=" + subId
                + " state=" + state + " incomingNumber=" + incomingNumber);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallState[phoneId] = state;
                mCallIncomingNumber[phoneId] = incomingNumber;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_CALL_STATE) &&
                            (r.subId == subId) &&
                            (r.subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                        try {
                            String incomingNumberOrEmpty = getCallIncomingNumber(r, phoneId);
                            r.callback.onCallStateChanged(state, incomingNumberOrEmpty);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastCallStateChanged(state, incomingNumber, phoneId, subId);
    }

    public void notifyServiceStateForPhoneId(int phoneId, int subId, ServiceState state) {
        if (!checkNotifyPermission("notifyServiceState()")){
            return;
        }

        synchronized (mRecords) {
            String str = "notifyServiceStateForSubscriber: subId=" + subId + " phoneId=" + phoneId
                    + " state=" + state;
            if (VDBG) {
                log(str);
            }
            mLocalLog.log(str);
            // for service state updates, don't notify clients when subId is invalid. This prevents
            // us from sending incorrect notifications like b/133140128
            // In the future, we can remove this logic for every notification here and add a
            // callback so listeners know when their PhoneStateListener's subId becomes invalid, but
            // for now we use the simplest fix.
            if (validatePhoneId(phoneId) && SubscriptionManager.isValidSubscriptionId(subId)) {
                mServiceState[phoneId] = state;

                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifyServiceStateForSubscriber: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + " state=" + state);
                    }
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_SERVICE_STATE) &&
                            idMatch(r.subId, subId, phoneId)) {

                        try {
                            ServiceState stateToSend;
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                stateToSend = new ServiceState(state);
                            } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                                stateToSend = state.sanitizeLocationInfo(false);
                            } else {
                                stateToSend = state.sanitizeLocationInfo(true);
                            }
                            if (DBG) {
                                log("notifyServiceStateForSubscriber: callback.onSSC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + state);
                            }
                            r.callback.onServiceStateChanged(stateToSend);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            } else {
                log("notifyServiceStateForSubscriber: INVALID phoneId=" + phoneId
                        + " or subId=" + subId);
            }
            handleRemoveListLocked();
        }
        broadcastServiceStateChanged(state, phoneId, subId);
    }

    public void notifySimActivationStateChangedForPhoneId(int phoneId, int subId,
            int activationType, int activationState) {
        if (!checkNotifyPermission("notifySimActivationState()")){
            return;
        }
        if (VDBG) {
            log("notifySimActivationStateForPhoneId: subId=" + subId + " phoneId=" + phoneId
                    + "type=" + activationType + " state=" + activationState);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                switch (activationType) {
                    case SIM_ACTIVATION_TYPE_VOICE:
                        mVoiceActivationState[phoneId] = activationState;
                        break;
                    case SIM_ACTIVATION_TYPE_DATA:
                        mDataActivationState[phoneId] = activationState;
                        break;
                    default:
                        return;
                }
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifySimActivationStateForPhoneId: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + "type=" + activationType
                                + " state=" + activationState);
                    }
                    try {
                        if ((activationType == SIM_ACTIVATION_TYPE_VOICE)
                                && r.matchPhoneStateListenerEvent(
                                        PhoneStateListener.LISTEN_VOICE_ACTIVATION_STATE)
                                && idMatch(r.subId, subId, phoneId)) {
                            if (DBG) {
                                log("notifyVoiceActivationStateForPhoneId: callback.onVASC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + activationState);
                            }
                            r.callback.onVoiceActivationStateChanged(activationState);
                        }
                        if ((activationType == SIM_ACTIVATION_TYPE_DATA)
                                && r.matchPhoneStateListenerEvent(
                                        PhoneStateListener.LISTEN_DATA_ACTIVATION_STATE)
                                && idMatch(r.subId, subId, phoneId)) {
                            if (DBG) {
                                log("notifyDataActivationStateForPhoneId: callback.onDASC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + activationState);
                            }
                            r.callback.onDataActivationStateChanged(activationState);
                        }
                    }  catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            } else {
                log("notifySimActivationStateForPhoneId: INVALID phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
    }

    public void notifySignalStrengthForPhoneId(int phoneId, int subId,
                SignalStrength signalStrength) {
        if (!checkNotifyPermission("notifySignalStrength()")) {
            return;
        }
        if (VDBG) {
            log("notifySignalStrengthForPhoneId: subId=" + subId
                +" phoneId=" + phoneId + " signalStrength=" + signalStrength);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                if (VDBG) log("notifySignalStrengthForPhoneId: valid phoneId=" + phoneId);
                mSignalStrength[phoneId] = signalStrength;
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifySignalStrengthForPhoneId: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + " ss=" + signalStrength);
                    }
                    if (r.matchPhoneStateListenerEvent(
                                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG) {
                                log("notifySignalStrengthForPhoneId: callback.onSsS r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " ss=" + signalStrength);
                            }
                            r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_SIGNAL_STRENGTH) &&
                            idMatch(r.subId, subId, phoneId)){
                        try {
                            int gsmSignalStrength = signalStrength.getGsmSignalStrength();
                            int ss = (gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
                            if (DBG) {
                                log("notifySignalStrengthForPhoneId: callback.onSS r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " gsmSS=" + gsmSignalStrength + " ss=" + ss);
                            }
                            r.callback.onSignalStrengthChanged(ss);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            } else {
                log("notifySignalStrengthForPhoneId: invalid phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
        broadcastSignalStrengthChanged(signalStrength, phoneId, subId);
    }

    @Override
    public void notifyCarrierNetworkChange(boolean active) {
        // only CarrierService with carrier privilege rule should have the permission
        int[] subIds = Arrays.stream(SubscriptionManager.from(mContext)
                    .getActiveSubscriptionIdList(false))
                    .filter(i -> TelephonyPermissions.checkCarrierPrivilegeForSubId(i)).toArray();
        if (ArrayUtils.isEmpty(subIds)) {
            loge("notifyCarrierNetworkChange without carrier privilege");
            // the active subId does not have carrier privilege.
            throw new SecurityException("notifyCarrierNetworkChange without carrier privilege");
        }

        synchronized (mRecords) {
            mCarrierNetworkChangeState = active;
            for (int subId : subIds) {
                int phoneId = SubscriptionManager.getPhoneId(subId);

                if (VDBG) {
                    log("notifyCarrierNetworkChange: active=" + active + "subId: " + subId);
                }
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCarrierNetworkChange(active);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyCellInfo(List<CellInfo> cellInfo) {
         notifyCellInfoForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cellInfo);
    }

    public void notifyCellInfoForSubscriber(int subId, List<CellInfo> cellInfo) {
        if (!checkNotifyPermission("notifyCellInfoForSubscriber()")) {
            return;
        }
        if (VDBG) {
            log("notifyCellInfoForSubscriber: subId=" + subId
                + " cellInfo=" + cellInfo);
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCellInfo.set(phoneId, cellInfo);
                for (Record r : mRecords) {
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_INFO) &&
                            idMatch(r.subId, subId, phoneId) &&
                            checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyCellInfoForSubscriber: mCellInfo=" + cellInfo
                                    + " r=" + r);
                            }
                            r.callback.onCellInfoChanged(cellInfo);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyMessageWaitingChangedForPhoneId(int phoneId, int subId, boolean mwi) {
        if (!checkNotifyPermission("notifyMessageWaitingChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyMessageWaitingChangedForSubscriberPhoneID: subId=" + phoneId
                + " mwi=" + mwi);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mMessageWaiting[phoneId] = mwi;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onMessageWaitingIndicatorChanged(mwi);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyUserMobileDataStateChangedForPhoneId(int phoneId, int subId, boolean state) {
        if (!checkNotifyPermission("notifyUserMobileDataStateChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyUserMobileDataStateChangedForSubscriberPhoneID: PhoneId=" + phoneId
                    + " subId=" + subId + " state=" + state);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mUserMobileDataState[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onUserMobileDataStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyCallForwardingChanged(boolean cfi) {
        notifyCallForwardingChangedForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cfi);
    }

    public void notifyCallForwardingChangedForSubscriber(int subId, boolean cfi) {
        if (!checkNotifyPermission("notifyCallForwardingChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallForwardingChangedForSubscriber: subId=" + subId
                + " cfi=" + cfi);
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallForwarding[phoneId] = cfi;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallForwardingIndicatorChanged(cfi);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyDataActivity(int state) {
        notifyDataActivityForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, state);
    }

    public void notifyDataActivityForSubscriber(int subId, int state) {
        if (!checkNotifyPermission("notifyDataActivity()" )) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mDataActivity[phoneId] = state;
                for (Record r : mRecords) {
                    // Notify by correct subId.
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_DATA_ACTIVITY) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onDataActivity(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification to registrants that the data connection state has changed.
     *
     * @param phoneId the phoneId carrying the data connection
     * @param subId the subscriptionId for the data connection
     * @param apnType the APN type that triggered a change in the data connection
     * @param preciseState a PreciseDataConnectionState that has info about the data connection
     */
    public void notifyDataConnectionForSubscriber(
            int phoneId, int subId, String apnType, PreciseDataConnectionState preciseState) {
        if (!checkNotifyPermission("notifyDataConnection()" )) {
            return;
        }

        String apn = "";
        int state = TelephonyManager.DATA_UNKNOWN;
        int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        LinkProperties linkProps = null;

        if (preciseState != null) {
            apn = preciseState.getDataConnectionApn();
            state = preciseState.getState();
            networkType = preciseState.getNetworkType();
            linkProps = preciseState.getDataConnectionLinkProperties();
        }
        if (VDBG) {
            log("notifyDataConnectionForSubscriber: subId=" + subId
                    + " state=" + state + "' apn='" + apn
                    + "' apnType=" + apnType + " networkType=" + networkType
                    + "' preciseState=" + preciseState);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                // We only call the callback when the change is for default APN type.
                if (PhoneConstants.APN_TYPE_DEFAULT.equals(apnType)
                        && (mDataConnectionState[phoneId] != state
                        || mDataConnectionNetworkType[phoneId] != networkType)) {
                    String str = "onDataConnectionStateChanged("
                            + dataStateToString(state)
                            + ", " + getNetworkTypeName(networkType)
                            + ") subId=" + subId + ", phoneId=" + phoneId;
                    log(str);
                    mLocalLog.log(str);
                    for (Record r : mRecords) {
                        if (r.matchPhoneStateListenerEvent(
                                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
                                && idMatch(r.subId, subId, phoneId)) {
                            try {
                                if (DBG) {
                                    log("Notify data connection state changed on sub: " + subId);
                                }
                                r.callback.onDataConnectionStateChanged(state, networkType);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                    handleRemoveListLocked();

                    mDataConnectionState[phoneId] = state;
                    mDataConnectionNetworkType[phoneId] = networkType;
                }

                boolean needsNotify = false;
                // State has been cleared for this APN Type
                if (preciseState == null) {
                    // We try clear the state and check if the state was previously not cleared
                    needsNotify = mPreciseDataConnectionStates.get(phoneId).remove(apnType) != null;
                } else {
                    // We need to check to see if the state actually changed
                    PreciseDataConnectionState oldPreciseState =
                            mPreciseDataConnectionStates.get(phoneId).put(apnType, preciseState);
                    needsNotify = !preciseState.equals(oldPreciseState);
                }

                if (needsNotify) {
                    for (Record r : mRecords) {
                        if (r.matchPhoneStateListenerEvent(
                                PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE)
                                && idMatch(r.subId, subId, phoneId)) {
                            try {
                                r.callback.onPreciseDataConnectionStateChanged(preciseState);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }

        broadcastDataConnectionStateChanged(state, apn, apnType, subId);
    }

    /**
     * Stub to satisfy the ITelephonyRegistry aidl interface; do not use this function.
     * @see #notifyDataConnectionFailedForSubscriber
     */
    public void notifyDataConnectionFailed(String apnType) {
        loge("This function should not be invoked");
    }

    public void notifyDataConnectionFailedForSubscriber(int phoneId, int subId, String apnType) {
        if (!checkNotifyPermission("notifyDataConnectionFailed()")) {
            return;
        }
        if (VDBG) {
            log("notifyDataConnectionFailedForSubscriber: subId=" + subId
                    + " apnType=" + apnType);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mPreciseDataConnectionStates.get(phoneId).put(
                        apnType,
                        new PreciseDataConnectionState(
                                TelephonyManager.DATA_UNKNOWN,
                                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                                ApnSetting.getApnTypesBitmaskFromString(apnType), null, null,
                                DataFailCause.NONE));
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onPreciseDataConnectionStateChanged(
                                    mPreciseDataConnectionStates.get(phoneId).get(apnType));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }

            handleRemoveListLocked();
        }
    }

    public void notifyCellLocation(Bundle cellLocation) {
         notifyCellLocationForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cellLocation);
    }

    public void notifyCellLocationForSubscriber(int subId, Bundle cellLocation) {
        log("notifyCellLocationForSubscriber: subId=" + subId
                + " cellLocation=" + cellLocation);
        if (!checkNotifyPermission("notifyCellLocation()")) {
            return;
        }
        if (VDBG) {
            log("notifyCellLocationForSubscriber: subId=" + subId
                + " cellLocation=" + cellLocation);
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCellLocation[phoneId] = cellLocation;
                for (Record r : mRecords) {
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_LOCATION) &&
                            idMatch(r.subId, subId, phoneId) &&
                            checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyCellLocation: cellLocation=" + cellLocation
                                        + " r=" + r);
                            }
                            r.callback.onCellLocationChanged(new Bundle(cellLocation));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPreciseCallState(int phoneId, int subId, int ringingCallState,
                                       int foregroundCallState, int backgroundCallState) {
        if (!checkNotifyPermission("notifyPreciseCallState()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mRingingCallState[phoneId] = ringingCallState;
                mForegroundCallState[phoneId] = foregroundCallState;
                mBackgroundCallState[phoneId] = backgroundCallState;
                mPreciseCallState[phoneId] = new PreciseCallState(
                        ringingCallState, foregroundCallState,
                        backgroundCallState,
                        DisconnectCause.NOT_VALID,
                        PreciseDisconnectCause.NOT_VALID);
                boolean notifyCallAttributes = true;
                if (mCallQuality == null) {
                    log("notifyPreciseCallState: mCallQuality is null, "
                            + "skipping call attributes");
                    notifyCallAttributes = false;
                } else {
                    // If the precise call state is no longer active, reset the call network type
                    // and call quality.
                    if (mPreciseCallState[phoneId].getForegroundCallState()
                            != PreciseCallState.PRECISE_CALL_STATE_ACTIVE) {
                        mCallNetworkType[phoneId] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                        mCallQuality[phoneId] = createCallQuality();
                    }
                    mCallAttributes[phoneId] = new CallAttributes(mPreciseCallState[phoneId],
                            mCallNetworkType[phoneId], mCallQuality[phoneId]);
                }

                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_PRECISE_CALL_STATE)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onPreciseCallStateChanged(mPreciseCallState[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                    if (notifyCallAttributes && r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallAttributesChanged(mCallAttributes[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyDisconnectCause(int phoneId, int subId, int disconnectCause,
                                      int preciseDisconnectCause) {
        if (!checkNotifyPermission("notifyDisconnectCause()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallDisconnectCause[phoneId] = disconnectCause;
                mCallPreciseDisconnectCause[phoneId] = preciseDisconnectCause;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener
                            .LISTEN_CALL_DISCONNECT_CAUSES) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallDisconnectCauseChanged(mCallDisconnectCause[phoneId],
                                    mCallPreciseDisconnectCause[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyImsDisconnectCause(int subId, ImsReasonInfo imsReasonInfo) {
        if (!checkNotifyPermission("notifyImsCallDisconnectCause()")) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mImsReasonInfo.set(phoneId, imsReasonInfo);
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_IMS_CALL_DISCONNECT_CAUSES)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyImsCallDisconnectCause: mImsReasonInfo="
                                        + imsReasonInfo + " r=" + r);
                            }
                            r.callback.onImsCallDisconnectCauseChanged(mImsReasonInfo.get(phoneId));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPreciseDataConnectionFailed(int phoneId, int subId, String apnType,
            String apn, @DataFailureCause int failCause) {
        if (!checkNotifyPermission("notifyPreciseDataConnectionFailed()")) {
            return;
        }

        // precise notify invokes imprecise notify
        notifyDataConnectionFailedForSubscriber(phoneId, subId, apnType);

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mPreciseDataConnectionStates.get(phoneId).put(
                        apnType,
                        new PreciseDataConnectionState(
                                TelephonyManager.DATA_UNKNOWN,
                                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                                ApnSetting.getApnTypesBitmaskFromString(apnType), null, null,
                                failCause));
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onPreciseDataConnectionStateChanged(
                                    mPreciseDataConnectionStates.get(phoneId).get(apnType));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifySrvccStateChanged(int subId, @SrvccState int state) {
        if (!checkNotifyPermission("notifySrvccStateChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifySrvccStateChanged: subId=" + subId + " srvccState=" + state);
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mSrvccState[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifySrvccStateChanged: mSrvccState=" + state + " r=" + r);
                            }
                            r.callback.onSrvccStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyOemHookRawEventForSubscriber(int phoneId, int subId, byte[] rawData) {
        if (!checkNotifyPermission("notifyOemHookRawEventForSubscriber")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifyOemHookRawEventForSubscriber:  r=" + r + " subId=" + subId);
                    }
                    if ((r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_OEM_HOOK_RAW_EVENT))
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onOemHookRawEvent(rawData);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPhoneCapabilityChanged(PhoneCapability capability) {
        if (!checkNotifyPermission("notifyPhoneCapabilityChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyPhoneCapabilityChanged: capability=" + capability);
        }

        synchronized (mRecords) {
            mPhoneCapability = capability;

            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_PHONE_CAPABILITY_CHANGE)) {
                    try {
                        r.callback.onPhoneCapabilityChanged(capability);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyActiveDataSubIdChanged(int activeDataSubId) {
        if (!checkNotifyPermission("notifyActiveDataSubIdChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyActiveDataSubIdChanged: activeDataSubId=" + activeDataSubId);
        }

        mActiveDataSubId = activeDataSubId;
        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE)) {
                    try {
                        r.callback.onActiveDataSubIdChanged(activeDataSubId);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyRadioPowerStateChanged(int phoneId, int subId, @RadioPowerState int state) {
        if (!checkNotifyPermission("notifyRadioPowerStateChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyRadioPowerStateChanged: state= " + state + " subId=" + subId);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mRadioPowerState = state;

                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onRadioPowerStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }

            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyEmergencyNumberList(int phoneId, int subId) {
        if (!checkNotifyPermission("notifyEmergencyNumberList()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                        Context.TELEPHONY_SERVICE);
                mEmergencyNumberList = tm.getEmergencyNumberList();

                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_EMERGENCY_NUMBER_LIST)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onEmergencyNumberListChanged(mEmergencyNumberList);
                            if (VDBG) {
                                log("notifyEmergencyNumberList: emergencyNumberList= "
                                        + mEmergencyNumberList);
                            }
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }

            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyOutgoingEmergencyCall(int phoneId, int subId,
            EmergencyNumber emergencyNumber) {
        if (!checkNotifyPermission("notifyOutgoingEmergencyCall()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mOutgoingCallEmergencyNumber[phoneId] = emergencyNumber;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_OUTGOING_EMERGENCY_CALL)
                                    && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onOutgoingEmergencyCall(emergencyNumber);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyOutgoingEmergencySms(int phoneId, int subId,
            EmergencyNumber emergencyNumber) {
        if (!checkNotifyPermission("notifyOutgoingEmergencySms()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mOutgoingSmsEmergencyNumber[phoneId] = emergencyNumber;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_OUTGOING_EMERGENCY_SMS)
                                    && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onOutgoingEmergencySms(emergencyNumber);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyCallQualityChanged(CallQuality callQuality, int phoneId, int subId,
            int callNetworkType) {
        if (!checkNotifyPermission("notifyCallQualityChanged()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                // merge CallQuality with PreciseCallState and network type
                mCallQuality[phoneId] = callQuality;
                mCallNetworkType[phoneId] = callNetworkType;
                mCallAttributes[phoneId] = new CallAttributes(mPreciseCallState[phoneId],
                        callNetworkType, callQuality);

                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallAttributesChanged(mCallAttributes[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }

            handleRemoveListLocked();
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            pw.println("last known state:");
            pw.increaseIndent();
            for (int i = 0; i < getTelephonyManager().getPhoneCount(); i++) {
                pw.println("Phone Id=" + i);
                pw.increaseIndent();
                pw.println("mCallState=" + mCallState[i]);
                pw.println("mRingingCallState=" + mRingingCallState[i]);
                pw.println("mForegroundCallState=" + mForegroundCallState[i]);
                pw.println("mBackgroundCallState=" + mBackgroundCallState[i]);
                pw.println("mPreciseCallState=" + mPreciseCallState[i]);
                pw.println("mCallDisconnectCause=" + mCallDisconnectCause[i]);
                pw.println("mCallIncomingNumber=" + mCallIncomingNumber[i]);
                pw.println("mServiceState=" + mServiceState[i]);
                pw.println("mVoiceActivationState= " + mVoiceActivationState[i]);
                pw.println("mDataActivationState= " + mDataActivationState[i]);
                pw.println("mUserMobileDataState= " + mUserMobileDataState[i]);
                pw.println("mSignalStrength=" + mSignalStrength[i]);
                pw.println("mMessageWaiting=" + mMessageWaiting[i]);
                pw.println("mCallForwarding=" + mCallForwarding[i]);
                pw.println("mDataActivity=" + mDataActivity[i]);
                pw.println("mDataConnectionState=" + mDataConnectionState[i]);
                pw.println("mCellLocation=" + mCellLocation[i]);
                pw.println("mCellInfo=" + mCellInfo.get(i));
                pw.println("mImsCallDisconnectCause=" + mImsReasonInfo.get(i));
                pw.println("mSrvccState=" + mSrvccState[i]);
                pw.println("mCallPreciseDisconnectCause=" + mCallPreciseDisconnectCause[i]);
                pw.println("mCallQuality=" + mCallQuality[i]);
                pw.println("mCallAttributes=" + mCallAttributes[i]);
                pw.println("mCallNetworkType=" + mCallNetworkType[i]);
                pw.println("mPreciseDataConnectionStates=" + mPreciseDataConnectionStates.get(i));
                pw.println("mOutgoingCallEmergencyNumber=" + mOutgoingCallEmergencyNumber[i]);
                pw.println("mOutgoingSmsEmergencyNumber=" + mOutgoingSmsEmergencyNumber[i]);
                pw.decreaseIndent();
            }
            pw.println("mCarrierNetworkChangeState=" + mCarrierNetworkChangeState);

            pw.println("mPhoneCapability=" + mPhoneCapability);
            pw.println("mActiveDataSubId=" + mActiveDataSubId);
            pw.println("mRadioPowerState=" + mRadioPowerState);
            pw.println("mEmergencyNumberList=" + mEmergencyNumberList);
            pw.println("mDefaultPhoneId=" + mDefaultPhoneId);
            pw.println("mDefaultSubId=" + mDefaultSubId);

            pw.decreaseIndent();

            pw.println("local logs:");
            pw.increaseIndent();
            mLocalLog.dump(fd, pw, args);
            pw.println("listen logs:");
            mListenLog.dump(fd, pw, args);
            pw.decreaseIndent();
            pw.println("registrations: count=" + recordCount);
            pw.increaseIndent();
            for (Record r : mRecords) {
                pw.println(r);
            }
            pw.decreaseIndent();
        }
    }

    //
    // the legacy intent broadcasting
    //

    // Legacy intent action.
    /** Fired when a subscription's phone state changes. */
    private static final String ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED =
            "android.intent.action.SUBSCRIPTION_PHONE_STATE";

    // Legacy intent extra keys, copied from PhoneConstants.
    // Used in legacy intents sent here, for backward compatibility.
    private static final String PHONE_CONSTANTS_SLOT_KEY = "slot";
    private static final String PHONE_CONSTANTS_SUBSCRIPTION_KEY = "subscription";

    private void broadcastServiceStateChanged(ServiceState state, int phoneId, int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneState(state.getState());
        } catch (RemoteException re) {
            // Can't do much
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        Bundle data = new Bundle();
        state.fillInNotifierBundle(data);
        intent.putExtras(data);
        // Pass the subscription along with the intent.
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastSignalStrengthChanged(SignalStrength signalStrength, int phoneId,
            int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneSignalStrength(signalStrength);
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED);
        Bundle data = new Bundle();
        signalStrength.fillInNotifierBundle(data);
        intent.putExtras(data);
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Broadcasts an intent notifying apps of a phone state change. {@code subId} can be
     * a valid subId, in which case this function fires a subId-specific intent, or it
     * can be {@code SubscriptionManager.INVALID_SUBSCRIPTION_ID}, in which case we send
     * a global state change broadcast ({@code TelephonyManager.ACTION_PHONE_STATE_CHANGED}).
     */
    private void broadcastCallStateChanged(int state, String incomingNumber, int phoneId,
                int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                mBatteryStats.notePhoneOff();
                StatsLog.write(StatsLog.PHONE_STATE_CHANGED,
                        StatsLog.PHONE_STATE_CHANGED__STATE__OFF);
            } else {
                mBatteryStats.notePhoneOn();
                StatsLog.write(StatsLog.PHONE_STATE_CHANGED,
                        StatsLog.PHONE_STATE_CHANGED__STATE__ON);
            }
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, callStateToString(state));

        // If a valid subId was specified, we should fire off a subId-specific state
        // change intent and include the subId.
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.setAction(ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
            intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
            intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        }
        // If the phoneId is invalid, the broadcast is for overall call state.
        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
            intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
        }

        // Wakeup apps for the (SUBSCRIPTION_)PHONE_STATE broadcast.
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

        // Create a version of the intent with the number always populated.
        Intent intentWithPhoneNumber = new Intent(intent);
        intentWithPhoneNumber.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, incomingNumber);

        // Send broadcast twice, once for apps that have PRIVILEGED permission and once for those
        // that have the runtime one
        mContext.sendBroadcastAsUser(intentWithPhoneNumber, UserHandle.ALL,
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PHONE_STATE,
                AppOpsManager.OP_READ_PHONE_STATE);
        mContext.sendBroadcastAsUserMultiplePermissions(intentWithPhoneNumber, UserHandle.ALL,
                new String[] { android.Manifest.permission.READ_PHONE_STATE,
                        android.Manifest.permission.READ_CALL_LOG});
    }

    /** Converts TelephonyManager#CALL_STATE_* to TelephonyManager#EXTRA_STATE_*. */
    private static String callStateToString(int callState) {
        switch (callState) {
            case TelephonyManager.CALL_STATE_RINGING:
                return TelephonyManager.EXTRA_STATE_RINGING;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                return TelephonyManager.EXTRA_STATE_OFFHOOK;
            default:
                return TelephonyManager.EXTRA_STATE_IDLE;
        }
    }

    private void broadcastDataConnectionStateChanged(int state, String apn,
                                                     String apnType, int subId) {
        // Note: not reporting to the battery stats service here, because the
        // status bar takes care of that after taking into account all of the
        // required info.
        Intent intent = new Intent(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, dataStateToString(state));

        intent.putExtra(PhoneConstants.DATA_APN_KEY, apn);
        intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void enforceNotifyPermissionOrCarrierPrivilege(String method) {
        if (checkNotifyPermission()) {
            return;
        }

        TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(
                SubscriptionManager.getDefaultSubscriptionId(), method);
    }

    private boolean checkNotifyPermission(String method) {
        if (checkNotifyPermission()) {
            return true;
        }
        String msg = "Modify Phone State Permission Denial: " + method + " from pid="
                + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
        if (DBG) log(msg);
        return false;
    }

    private boolean checkNotifyPermission() {
        return mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkListenerPermission(int events, int subId, String callingPackage,
            @Nullable String callingFeatureId, String message) {
        LocationAccessPolicy.LocationPermissionQuery.Builder locationQueryBuilder =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                .setCallingPackage(callingPackage)
                .setMethod(message + " events: " + events)
                .setCallingPid(Binder.getCallingPid())
                .setCallingUid(Binder.getCallingUid());

        boolean shouldCheckLocationPermissions = false;
        if ((events & ENFORCE_COARSE_LOCATION_PERMISSION_MASK) != 0) {
            locationQueryBuilder.setMinSdkVersionForCoarse(0);
            shouldCheckLocationPermissions = true;
        }

        if ((events & ENFORCE_FINE_LOCATION_PERMISSION_MASK) != 0) {
            // Everything that requires fine location started in Q. So far...
            locationQueryBuilder.setMinSdkVersionForFine(Build.VERSION_CODES.Q);
            shouldCheckLocationPermissions = true;
        }

        if (shouldCheckLocationPermissions) {
            LocationAccessPolicy.LocationPermissionResult result =
                    LocationAccessPolicy.checkLocationPermission(
                            mContext, locationQueryBuilder.build());
            switch (result) {
                case DENIED_HARD:
                    throw new SecurityException("Unable to listen for events " + events + " due to "
                            + "insufficient location permissions.");
                case DENIED_SOFT:
                    return false;
            }
        }

        if ((events & ENFORCE_PHONE_STATE_PERMISSION_MASK) != 0) {
            if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                    mContext, subId, callingPackage, callingFeatureId, message)) {
                return false;
            }
        }

        if ((events & PRECISE_PHONE_STATE_PERMISSION_MASK) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
        }

        if ((events & READ_ACTIVE_EMERGENCY_SESSION_PERMISSION_MASK) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION, null);
        }

        if ((events & PhoneStateListener.LISTEN_OEM_HOOK_RAW_EVENT) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_CALL_DISCONNECT_CAUSES) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_VOICE_ACTIVATION_STATE) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_IMS_CALL_DISCONNECT_CAUSES) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
        }

        return true;
    }

    private void handleRemoveListLocked() {
        int size = mRemoveList.size();
        if (VDBG) log("handleRemoveListLocked: mRemoveList.size()=" + size);
        if (size > 0) {
            for (IBinder b: mRemoveList) {
                remove(b);
            }
            mRemoveList.clear();
        }
    }

    private boolean validateEventsAndUserLocked(Record r, int events) {
        int foregroundUser;
        long callingIdentity = Binder.clearCallingIdentity();
        boolean valid = false;
        try {
            foregroundUser = ActivityManager.getCurrentUser();
            valid = UserHandle.getUserId(r.callerUid) == foregroundUser
                    && r.matchPhoneStateListenerEvent(events);
            if (DBG | DBG_LOC) {
                log("validateEventsAndUserLocked: valid=" + valid
                        + " r.callerUid=" + r.callerUid + " foregroundUser=" + foregroundUser
                        + " r.events=" + r.events + " events=" + events);
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return valid;
    }

    private boolean validatePhoneId(int phoneId) {
        boolean valid = (phoneId >= 0) && (phoneId < mNumPhones);
        if (VDBG) log("validatePhoneId: " + valid);
        return valid;
    }

    private static void log(String s) {
        Rlog.d(TAG, s);
    }

    private static void loge(String s) {
        Rlog.e(TAG, s);
    }

    boolean idMatch(int rSubId, int subId, int phoneId) {

        if(subId < 0) {
            // Invalid case, we need compare phoneId with default one.
            return (mDefaultPhoneId == phoneId);
        }
        if(rSubId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            return (subId == mDefaultSubId);
        } else {
            return (rSubId == subId);
        }
    }

    private boolean checkFineLocationAccess(Record r, int minSdk) {
        LocationAccessPolicy.LocationPermissionQuery query =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(r.callingPackage)
                        .setCallingPid(r.callerPid)
                        .setCallingUid(r.callerUid)
                        .setMethod("TelephonyRegistry push")
                        .setLogAsInfo(true) // we don't need to log an error every time we push
                        .setMinSdkVersionForFine(minSdk)
                        .build();

        return Binder.withCleanCallingIdentity(() -> {
            LocationAccessPolicy.LocationPermissionResult locationResult =
                    LocationAccessPolicy.checkLocationPermission(mContext, query);
            return locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED;
        });
    }

    private boolean checkCoarseLocationAccess(Record r, int minSdk) {
        LocationAccessPolicy.LocationPermissionQuery query =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(r.callingPackage)
                        .setCallingPid(r.callerPid)
                        .setCallingUid(r.callerUid)
                        .setMethod("TelephonyRegistry push")
                        .setLogAsInfo(true) // we don't need to log an error every time we push
                        .setMinSdkVersionForCoarse(minSdk)
                        .build();

        return Binder.withCleanCallingIdentity(() -> {
            LocationAccessPolicy.LocationPermissionResult locationResult =
                    LocationAccessPolicy.checkLocationPermission(mContext, query);
            return locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED;
        });
    }

    private void checkPossibleMissNotify(Record r, int phoneId) {
        int events = r.events;

        if ((events & PhoneStateListener.LISTEN_SERVICE_STATE) != 0) {
            try {
                if (VDBG) log("checkPossibleMissNotify: onServiceStateChanged state=" +
                        mServiceState[phoneId]);
                r.callback.onServiceStateChanged(
                        new ServiceState(mServiceState[phoneId]));
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) != 0) {
            try {
                SignalStrength signalStrength = mSignalStrength[phoneId];
                if (DBG) {
                    log("checkPossibleMissNotify: onSignalStrengthsChanged SS=" + signalStrength);
                }
                r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTH) != 0) {
            try {
                int gsmSignalStrength = mSignalStrength[phoneId]
                        .getGsmSignalStrength();
                if (DBG) {
                    log("checkPossibleMissNotify: onSignalStrengthChanged SS=" +
                            gsmSignalStrength);
                }
                r.callback.onSignalStrengthChanged((gsmSignalStrength == 99 ? -1
                        : gsmSignalStrength));
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_INFO)) {
            try {
                if (DBG_LOC) {
                    log("checkPossibleMissNotify: onCellInfoChanged[" + phoneId + "] = "
                            + mCellInfo.get(phoneId));
                }
                if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) != 0) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onUserMobileDataStateChanged phoneId="
                            + phoneId + " umds=" + mUserMobileDataState[phoneId]);
                }
                r.callback.onUserMobileDataStateChanged(mUserMobileDataState[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) != 0) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onMessageWaitingIndicatorChanged phoneId="
                            + phoneId + " mwi=" + mMessageWaiting[phoneId]);
                }
                r.callback.onMessageWaitingIndicatorChanged(
                        mMessageWaiting[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) != 0) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onCallForwardingIndicatorChanged phoneId="
                        + phoneId + " cfi=" + mCallForwarding[phoneId]);
                }
                r.callback.onCallForwardingIndicatorChanged(
                        mCallForwarding[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_LOCATION)) {
            try {
                if (DBG_LOC) log("checkPossibleMissNotify: onCellLocationChanged mCellLocation = "
                        + mCellLocation[phoneId]);
                if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onCellLocationChanged(new Bundle(mCellLocation[phoneId]));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) != 0) {
            try {
                if (DBG) {
                    log("checkPossibleMissNotify: onDataConnectionStateChanged(mDataConnectionState"
                            + "=" + mDataConnectionState[phoneId]
                            + ", mDataConnectionNetworkType=" + mDataConnectionNetworkType[phoneId]
                            + ")");
                }
                r.callback.onDataConnectionStateChanged(mDataConnectionState[phoneId],
                        mDataConnectionNetworkType[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }
    }

    /**
     * Convert TelephonyManager.DATA_* to string.
     *
     * @return The data state in string format.
     */
    private static String dataStateToString(int state) {
        switch (state) {
            case TelephonyManager.DATA_DISCONNECTED: return "DISCONNECTED";
            case TelephonyManager.DATA_CONNECTING: return "CONNECTING";
            case TelephonyManager.DATA_CONNECTED: return "CONNECTED";
            case TelephonyManager.DATA_SUSPENDED: return "SUSPENDED";
        }
        return "UNKNOWN(" + state + ")";
    }

    /**
     * Returns a string representation of the radio technology (network type)
     * currently in use on the device.
     * @param subId for which network type is returned
     * @return the name of the radio technology
     *
     */
    private String getNetworkTypeName(@Annotation.NetworkType int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
                return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA:
                return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "HSPA";
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return "CDMA";
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return "CDMA - EvDo rev. 0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                return "CDMA - EvDo rev. A";
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "CDMA - EvDo rev. B";
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "CDMA - 1xRTT";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return "CDMA - eHRPD";
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "iDEN";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_GSM:
                return "GSM";
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return "TD_SCDMA";
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "IWLAN";

            //TODO: This network type is marked as hidden because it is not a
            // true network type and we are looking to remove it completely from the available list
            // of network types.  Since this method is only used for logging, in the event that this
            // network type is selected, the log will read as "Unknown."
            //case TelephonyManager.NETWORK_TYPE_LTE_CA:
            //    return "LTE_CA";

            case TelephonyManager.NETWORK_TYPE_NR:
                return "NR";
            default:
                return "UNKNOWN";
        }
    }

    /** Returns a new PreciseCallState object with default values. */
    private static PreciseCallState createPreciseCallState() {
        return new PreciseCallState(PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            DisconnectCause.NOT_VALID,
            PreciseDisconnectCause.NOT_VALID);
    }

    /** Returns a new CallQuality object with default values. */
    private static CallQuality createCallQuality() {
        return new CallQuality(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
