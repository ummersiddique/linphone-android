/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.core

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.contacts.ContactsManager
import org.linphone.core.tools.Log
import org.linphone.notifications.NotificationsManager
import org.linphone.telecom.TelecomManager
import org.linphone.ui.call.CallActivity
import org.linphone.utils.ActivityMonitor
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class CoreContext @UiThread constructor(val context: Context) : HandlerThread("Core Thread") {
    companion object {
        private const val TAG = "[Core Context]"
    }

    lateinit var core: Core

    val contactsManager = ContactsManager()

    val notificationsManager = NotificationsManager(context)

    val telecomManager = TelecomManager(context)

    @get:AnyThread
    val sdkVersion: String by lazy {
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkBuildType = org.linphone.core.BuildConfig.BUILD_TYPE
        "$sdkVersion ($sdkBranch, $sdkBuildType)"
    }

    private val activityMonitor = ActivityMonitor()

    private val mainThread = Handler(Looper.getMainLooper())

    val greenToastToShowEvent: MutableLiveData<Event<Pair<String, Int>>> by lazy {
        MutableLiveData<Event<Pair<String, Int>>>()
    }

    val redToastToShowEvent: MutableLiveData<Event<Pair<String, Int>>> by lazy {
        MutableLiveData<Event<Pair<String, Int>>>()
    }

    @SuppressLint("HandlerLeak")
    private lateinit var coreThread: Handler

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        @WorkerThread
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (!addedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${addedDevices.size}] new device(s) have been added:")
                for (device in addedDevices) {
                    Log.i("$TAG Added device [${device.id}][${device.productName}][${device.type}]")
                }
                core.reloadSoundDevices()
            }
        }

        @WorkerThread
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (!removedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${removedDevices.size}] existing device(s) have been removed")
                for (device in removedDevices) {
                    Log.i(
                        "$TAG Removed device [${device.id}][${device.productName}][${device.type}]"
                    )
                }
                core.reloadSoundDevices()
            }
        }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            Log.i("$TAG Global state changed [$state]")
        }

        @WorkerThread
        override fun onConfiguringStatus(
            core: Core,
            status: ConfiguringState?,
            message: String?
        ) {
            Log.i("$TAG Configuring state changed [$status]")
            if (status == ConfiguringState.Successful) {
                val text = context.getString(
                    org.linphone.R.string.assistant_qr_code_provisioning_done
                )
                greenToastToShowEvent.postValue(Event(Pair(text, org.linphone.R.drawable.smiley)))
            } else if (status == ConfiguringState.Failed) {
                val text = context.getString(
                    org.linphone.R.string.assistant_qr_code_provisioning_done
                )
                redToastToShowEvent.postValue(
                    Event(Pair(text, org.linphone.R.drawable.warning_circle))
                )
            }
        }

        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            Log.i("$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed [$state]")
            when (state) {
                Call.State.OutgoingInit -> {
                    val conferenceInfo = core.findConferenceInformationFromUri(call.remoteAddress)
                    // Do not show outgoing call view for conference calls, wait for connected state
                    if (conferenceInfo == null) {
                        postOnMainThread {
                            showCallActivity()
                        }
                    } else {
                        Log.i(
                            "$TAG Call peer address matches known conference, delaying in-call UI until Connected state"
                        )
                    }
                }
                Call.State.Connected -> {
                    postOnMainThread {
                        showCallActivity()
                    }
                }
                else -> {
                }
            }
        }

        @WorkerThread
        override fun onTransferStateChanged(core: Core, transfered: Call, state: Call.State) {
            Log.i(
                "$TAG Transferred call [${transfered.remoteAddress.asStringUriOnly()}] state changed [$state]"
            )
            if (state == Call.State.Connected) {
                val message = context.getString(
                    org.linphone.R.string.toast_call_transfer_successful
                )
                val icon = org.linphone.R.drawable.phone_transfer

                greenToastToShowEvent.postValue(Event(Pair(message, icon)))
            }
        }
    }

    private val loggingServiceListener = object : LoggingServiceListenerStub() {
        @WorkerThread
        override fun onLogMessageWritten(
            logService: LoggingService,
            domain: String,
            level: LogLevel,
            message: String
        ) {
            when (level) {
                LogLevel.Error -> android.util.Log.e(domain, message)
                LogLevel.Warning -> android.util.Log.w(domain, message)
                LogLevel.Message -> android.util.Log.i(domain, message)
                LogLevel.Fatal -> android.util.Log.wtf(domain, message)
                else -> android.util.Log.d(domain, message)
            }
            FirebaseCrashlytics.getInstance().log("[$domain] [${level.name}] $message")
        }
    }

    init {
        (context as Application).registerActivityLifecycleCallbacks(activityMonitor)
    }

    @WorkerThread
    override fun run() {
        Log.i("$TAG Creating Core")
        Looper.prepare()

        Factory.instance().loggingService.addListener(loggingServiceListener)
        Log.i("$TAG Crashlytics enabled, register logging service listener")

        Log.i("=========================================")
        Log.i("==== Linphone-android information dump ====")
        Log.i("VERSION=${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
        Log.i("PACKAGE=${BuildConfig.APPLICATION_ID}")
        Log.i("BUILD TYPE=${BuildConfig.BUILD_TYPE}")
        Log.i("=========================================")

        val looper = Looper.myLooper() ?: return
        coreThread = Handler(looper)

        core = Factory.instance().createCoreWithConfig(corePreferences.config, context)
        core.isAutoIterateEnabled = true
        core.addListener(coreListener)

        coreThread.postDelayed({ startCore() }, 50)

        Looper.loop()
    }

    override fun quit(): Boolean {
        destroyCore()
        return super.quit()
    }

    override fun quitSafely(): Boolean {
        destroyCore()
        return super.quitSafely()
    }

    @WorkerThread
    fun startCore() {
        Log.i("$TAG Configuring Core")
        core.videoCodecPriorityPolicy = CodecPriorityPolicy.Auto

        val oldVersion = corePreferences.linphoneConfigurationVersion
        val newVersion = "6.0.0"
        if (oldVersion == "5.2") {
            Log.i("$TAG Migrating configuration from  [$oldVersion] to [$newVersion]")
            val policy = core.videoActivationPolicy.clone()
            policy.automaticallyAccept = true
            policy.automaticallyAcceptDirection = MediaDirection.RecvOnly
            core.videoActivationPolicy = policy
        }

        updateFriendListsSubscriptionDependingOnDefaultAccount()

        computeUserAgent()
        Log.i("$TAG Core has been configured with user-agent [${core.userAgent}], starting it")
        core.start()

        contactsManager.onCoreStarted(core)
        telecomManager.onCoreStarted(core)
        notificationsManager.onCoreStarted(core)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, coreThread)

        corePreferences.linphoneConfigurationVersion = newVersion

        Log.i("$TAG Report Core created and started")
    }

    @WorkerThread
    private fun destroyCore() {
        if (!::core.isInitialized) {
            return
        }

        val state = core.globalState
        if (state != GlobalState.On) {
            Log.w("$TAG Core is in state [$state], do not continue destroy process")
            return
        }
        Log.w("$TAG Stopping Core and destroying context related objects")

        postOnMainThread {
            (context as Application).unregisterActivityLifecycleCallbacks(activityMonitor)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)

        core.stopAsync()

        contactsManager.onCoreStopped(core)
        telecomManager.onCoreStopped(core)
        notificationsManager.onCoreStopped(core)

        // It's very unlikely the process will survive until the Core reaches GlobalStateOff sadly
        Log.w("$TAG Core is shutting down but probably won't reach Off state")
    }

    @AnyThread
    fun isReady(): Boolean {
        return ::core.isInitialized
    }

    @AnyThread
    fun postOnCoreThread(
        @WorkerThread lambda: (core: Core) -> Unit
    ) {
        coreThread.post {
            lambda.invoke(core)
        }
    }

    @AnyThread
    fun postOnMainThread(
        @UiThread lambda: () -> Unit
    ) {
        mainThread.post {
            lambda.invoke()
        }
    }

    @UiThread
    fun onForeground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in foreground, PUBLISHING presence as Online")
                core.consolidatedPresence = ConsolidatedPresence.Online
            }
        }
    }

    @UiThread
    fun onBackground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in background, un-PUBLISHING presence info")
                // We don't use ConsolidatedPresence.Busy but Offline to do an unsubscribe,
                // Flexisip will handle the Busy status depending on other devices
                core.consolidatedPresence = ConsolidatedPresence.Offline
            }
        }
    }

    @WorkerThread
    fun isAddressMyself(address: Address): Boolean {
        val found = core.accountList.find {
            it.params.identityAddress?.weakEqual(address) ?: false
        }
        return found != null
    }

    @WorkerThread
    fun startAudioCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        val params = core.createCallParams(null)
        params?.isVideoEnabled = true
        params?.videoDirection = MediaDirection.Inactive
        startCall(address, params, forceZRTP, localAddress)
    }

    @WorkerThread
    fun startVideoCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        val params = core.createCallParams(null)
        params?.isVideoEnabled = true
        params?.videoDirection = MediaDirection.SendRecv
        startCall(address, params, forceZRTP, localAddress)
    }

    @WorkerThread
    fun startCall(
        address: Address,
        callParams: CallParams? = null,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        if (!core.isNetworkReachable) {
            Log.e("$TAG Network unreachable, abort outgoing call")
            return
        }

        val currentCall = core.currentCall
        if (currentCall != null) {
            Log.w(
                "$TAG Found current call [${currentCall.remoteAddress.asStringUriOnly()}], pausing it first"
            )
            currentCall.pause()
        }

        val params = callParams ?: core.createCallParams(null)
        if (params == null) {
            val call = core.inviteAddress(address)
            Log.w("$TAG Starting call $call without params")
            return
        }

        if (forceZRTP) {
            params.mediaEncryption = MediaEncryption.ZRTP
        }
        /*if (LinphoneUtils.checkIfNetworkHasLowBandwidth(context)) {
            Log.w("$TAG Enabling low bandwidth mode!")
            params.isLowBandwidthEnabled = true
        }*/

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(address)

        if (localAddress != null) {
            val account = core.accountList.find { account ->
                account.params.identityAddress?.weakEqual(localAddress) ?: false
            }
            if (account != null) {
                params.account = account
                Log.i(
                    "$TAG Using account matching address ${localAddress.asStringUriOnly()} as From"
                )
            } else {
                Log.e(
                    "$TAG Failed to find account matching address ${localAddress.asStringUriOnly()}"
                )
            }
        }

        val call = core.inviteAddressWithParams(address, params)
        Log.i("$TAG Starting call $call")
    }

    @WorkerThread
    fun switchCamera() {
        val currentDevice = core.videoDevice
        Log.i("$TAG Current camera device is $currentDevice")

        for (camera in core.videoDevicesList) {
            if (camera != currentDevice && camera != "StaticImage: Static picture") {
                Log.i("$TAG New camera device will be $camera")
                core.videoDevice = camera
                break
            }
        }

        val call = core.currentCall
        if (call == null) {
            Log.w("$TAG Switching camera while not in call")
            return
        }
        call.update(null)
    }

    @WorkerThread
    fun showSwitchCameraButton(): Boolean {
        return core.isVideoCaptureEnabled && core.videoDevicesList.size > 2 // Count StaticImage camera
    }

    @WorkerThread
    fun answerCall(call: Call) {
        Log.i("$TAG Answering call $call")
        val params = core.createCallParams(call)
        if (params == null) {
            Log.w("$TAG Answering call without params!")
            call.accept()
            return
        }

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(call.remoteAddress)

        /*if (LinphoneUtils.checkIfNetworkHasLowBandwidth(context)) {
            Log.w("$TAG Enabling low bandwidth mode!")
            params.isLowBandwidthEnabled = true
        }*/

        if (call.callLog.wasConference()) {
            // Prevent incoming group call to start in audio only layout
            // Do the same as the conference waiting room
            params.isVideoEnabled = true
            params.videoDirection = if (core.videoActivationPolicy.automaticallyInitiate) MediaDirection.SendRecv else MediaDirection.RecvOnly
            Log.i(
                "$TAG Enabling video on call params to prevent audio-only layout when answering"
            )
        }

        call.acceptWithParams(params)
    }

    @WorkerThread
    fun terminateCall(call: Call) {
        if (call.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(call.state)) {
            val reason = if (call.core.callsNb > 1) Reason.Busy else Reason.Declined
            Log.i("$TAG Declining call [${call.remoteAddress.asStringUriOnly()}] with reason [$reason]")
            call.decline(reason)
        } else {
            Log.i("$TAG Terminating call [${call.remoteAddress.asStringUriOnly()}]")
            call.terminate()
        }
    }

    @UiThread
    fun showCallActivity() {
        Log.i("$TAG Starting Call activity")
        val intent = Intent(context, CallActivity::class.java)
        // This flag is required to start an Activity from a Service context
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        context.startActivity(intent)
    }

    @WorkerThread
    fun updateFriendListsSubscriptionDependingOnDefaultAccount() {
        val account = core.defaultAccount
        if (account != null) {
            val enabled = account.params.domain == corePreferences.defaultDomain
            if (enabled != core.isFriendListSubscriptionEnabled) {
                core.isFriendListSubscriptionEnabled = enabled
                Log.i(
                    "$TAG Friend list(s) subscription are now ${if (enabled) "enabled" else "disabled"}"
                )
            }
        } else {
            Log.e("$TAG Default account is null, do not touch friend lists subscription")
        }
    }

    @WorkerThread
    private fun computeUserAgent() {
        val deviceName = AppUtils.getDeviceName(context)
        val appName = context.getString(org.linphone.R.string.app_name)
        val androidVersion = BuildConfig.VERSION_NAME
        val userAgent = "$appName/$androidVersion ($deviceName) LinphoneSDK"
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkUserAgent = "$sdkVersion ($sdkBranch)"
        core.setUserAgent(userAgent, sdkUserAgent)
    }
}
