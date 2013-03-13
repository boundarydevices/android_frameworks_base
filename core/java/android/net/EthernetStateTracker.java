/*
 * Copyright (C) 2008 The Android Open Source Project
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
/* Copyright 2010-2011 Freescale Semiconductor Inc. */

package android.net;

import static android.net.EthernetManager.ETHERNET_STATE_DISABLING;
import static android.net.EthernetManager.ETHERNET_STATE_DISABLED;
import static android.net.EthernetManager.ETHERNET_STATE_ENABLING;
import static android.net.EthernetManager.ETHERNET_STATE_ENABLED;
import static android.net.EthernetManager.ETHERNET_STATE_UNKNOWN;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.ActivityManagerNative;
import android.net.NetworkInfo;
import android.net.NetworkStateTracker;
import android.net.DhcpInfo;
import android.net.NetworkUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo.State;
import android.os.Message;
import android.os.Parcelable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Config;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.Context;
import android.database.ContentObserver;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Track the state of Ethernet connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * @hide
 */
public class EthernetStateTracker extends NetworkStateTracker {

    private static final boolean LOCAL_LOGD = Config.LOGD || false;
    
    private static final String TAG = "EthernetStateTracker";

    // Event log tags (must be in sync with event-log-tags)
    private static final int EVENTLOG_NETWORK_STATE_CHANGED = 50021;
    private static final int EVENTLOG_DRIVER_STATE_CHANGED = 50023;
    private static final int EVENTLOG_INTERFACE_CONFIGURATION_STATE_CHANGED = 50024;

    // Event codes
    private static final int EVENT_ETHERNET_PLUGGED_AND_UP                     = 1;
    private static final int EVENT_ETHERNET_PLUGGED_AND_DOWN                   = 2;
    private static final int EVENT_ETHERNET_UNPLUGGED_AND_DOWN                 = 3;        
    private static final int EVENT_NETWORK_STATE_CHANGED             = 4;
    private static final int EVENT_SCAN_RESULTS_AVAILABLE            = 5;
    private static final int EVENT_INTERFACE_CONFIGURATION_SUCCEEDED = 6;
    private static final int EVENT_INTERFACE_CONFIGURATION_FAILED    = 7;
    private static final int EVENT_POLL_INTERVAL                     = 8;
    private static final int EVENT_DHCP_START                        = 9;
    private static final int EVENT_DEFERRED_DISCONNECT               = 10;
    private static final int EVENT_DEFERRED_RECONNECT                = 11;
    private static final int EVENT_ETHERNET_UNPLUGGED_AND_UP         = 15;
    private static final int EVENT_ETHERNET_UNKNOWN                  = 16;    
    private static final int EVENT_ETHERNET_DISABLED                 = 17;    
    private static final int EVENT_ETHERNET_ERROR                    = 18;    
    /**
     * The driver is started or stopped. The object will be the state: true for
     * started, false for stopped.
     */
    private static final int EVENT_DRIVER_STATE_CHANGED              = 12;

    /**
     * The driver state indication.
     */
    private static final int DRIVER_STARTED                          = 0;
    private static final int DRIVER_STOPPED                          = 1;
    private static final int DRIVER_HUNG                             = 2;

    /**
     * Interval in milliseconds between polling for connection
     * status items that are not sent via asynchronous events.
     * An example is RSSI (signal strength).
     */
    private static final int POLL_STATUS_INTERVAL_MSECS = 3000;

    /**
     * When a DISCONNECT event is received, we defer handling it to
     * allow for the possibility that the DISCONNECT is about to
     * be followed shortly by a CONNECT to the same network we were
     * just connected to. In such a case, we don't want to report
     * the network as down, nor do we want to reconfigure the network
     * interface, etc. If we get a CONNECT event for another network
     * within the delay window, we immediately handle the pending
     * disconnect before processing the CONNECT.<p/>
     * The five second delay is chosen somewhat arbitrarily, but is
     * meant to cover most of the cases where a DISCONNECT/CONNECT
     * happens to a network.
     */
    private static final int DISCONNECT_DELAY_MSECS = 5000;


    private EthernetMonitor mEthernetMonitor;
    private EthernetManager mEM;
    private boolean mHaveIpAddress;
    private boolean mObtainingIpAddress;
    private boolean mTornDownByConnMgr;
    /**
     * A DISCONNECT event has been received, but processing it
     * is being deferred.
     */
    private boolean mDisconnectPending;
    /**
     * An operation has been performed as a result of which we expect the next event
     * will be a DISCONNECT.
     */
    private boolean mDisconnectExpected;
    private DhcpHandler mDhcpTarget;
    private DhcpInfo mDhcpInfo;
    private boolean mUseStaticIp = false;
    private int mReconnectCount;

    /* Tracks if any network in the configuration is disabled */
    private AtomicBoolean mIsAnyNetworkDisabled = new AtomicBoolean(false);
    /**
     * Observes the static IP address settings.
     */
    private SettingsObserver mSettingsObserver;
    

    private final AtomicInteger mEthernetState = new AtomicInteger(ETHERNET_STATE_UNKNOWN);

    // Ethernet run states:
    private static final int RUN_STATE_STARTING = 1;
    private static final int RUN_STATE_RUNNING  = 2;
    private static final int RUN_STATE_STOPPING = 3;
    private static final int RUN_STATE_STOPPED  = 4;

    private static final String mRunStateNames[] = {
            "Starting",
            "Running",
            "Stopping",
            "Stopped"
    };
    private int mRunState;

    private boolean mIsScanOnly;

    private String mInterfaceName;
    private static String LS = System.getProperty("line.separator");

    private static String[] sDnsPropNames;

    /**
     * Keep track of whether we last told the battery stats we had started.
     */
    private boolean mReportedRunning = false;

    private boolean mStateTrackerRunning = false;

    private int mStateTrackerLastEvent = EVENT_ETHERNET_UNPLUGGED_AND_DOWN;
    
    /**
     * Most recently set source of starting ETHERNET.
     */
    private final WorkSource mRunningEthernetUids = new WorkSource();

    /**
     * The last reported UIDs that were responsible for starting ETHERNET.
     */
    private final WorkSource mLastRunningEthernetUids = new WorkSource();


    /**
     * A structure for supplying information about a connection in
     * the CONNECTED event message that comes from the EthernetiMonitor
     * thread.
     */
    private static class NetworkStateChangeResult {
        NetworkStateChangeResult(DetailedState state) {
            this.state = state;
        }
        DetailedState state;
    }

    public EthernetStateTracker(Context context, Handler target) {
        super(context, target, ConnectivityManager.TYPE_ETHERNET, 0, "ETHERNET", "");
        Log.i(TAG, "start EthernetStateTracker");
        
        mEthernetMonitor = new EthernetMonitor(this);
        mHaveIpAddress = false;
        mObtainingIpAddress = false;
        setTornDownByConnMgr(false);
        mDisconnectPending = false;
        mStateTrackerRunning = false;

        // Allocate DHCP info object once, and fill it in on each request
        mDhcpInfo = new DhcpInfo();
        mRunState = RUN_STATE_STARTING;

        // Setting is in seconds

        mSettingsObserver = new SettingsObserver(new Handler());

        mInterfaceName = SystemProperties.get("ethernet.interface", "eth0");
        sDnsPropNames = new String[] {
            "dhcp." + mInterfaceName + ".dns1",
            "dhcp." + mInterfaceName + ".dns2"
        };
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the mobile data network.
     * @param hostAddress the IP address of the host to which the route is desired,
     * in network byte order.
     * @return {@code true} on success, {@code false} on failure
     */
    @Override
    public boolean requestRouteToHost(int hostAddress) {
        if (mInterfaceName != null && hostAddress != -1) {
            return NetworkUtils.addHostRoute(mInterfaceName, hostAddress) == 0;
        } else {
            return false;
        }
    }

    /**
     * Helper method: sets the boolean indicating that the connection
     * manager asked the network to be torn down (and so only the connection
     * manager can set it up again).
     * network info updated.
     * @param flag {@code true} if explicitly disabled.
     */
    private void setTornDownByConnMgr(boolean flag) {
        mTornDownByConnMgr = flag;
        updateNetworkInfo();
    }

    /**
     * Return the IP addresses of the DNS servers available for the WLAN
     * network interface.
     * @return a list of DNS addresses, with no holes.
     */
    public String[] getNameServers() {
        return getNameServerList(sDnsPropNames);
    }

    /**
     * Return the name of our WLAN network interface.
     * @return the name of our interface.
     */
    public String getInterfaceName() {
        return mInterfaceName;
    }

    public void enableInterface() {
        if (LOCAL_LOGD) Log.d(TAG, "ENabling interface");
        NetworkUtils.enableInterface(mInterfaceName);
    }


    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.ethernet";
    }

    public void startMonitoring() {
        /*
         * Get a handle on the EthernetManager. This cannot be done in our
         * constructor, because the Ethernet service is not yet registered.
         */
        mEM = (EthernetManager)mContext.getSystemService(Context.ETHERNET_SERVICE);
    }

    public void startEventLoop() {
        mStateTrackerRunning = true;
        mStateTrackerLastEvent = EVENT_ETHERNET_UNPLUGGED_AND_DOWN;
        mEthernetMonitor.startMonitoring();
    }

    public void stopEventLoop() {
        mStateTrackerRunning = false;
        mEthernetMonitor.stopMonitoring();
    }
    /**
     * Ethernet is considered available as long as we have a connection to the
     * driver and there is at least one enabled network. If a teardown
     * was explicitly requested, then Ethernet can be restarted with a reconnect
     * request, so it is considered available. If the driver has been stopped
     * for any reason other than a teardown request, Ethernet is considered
     * unavailable.
     * @return {@code true} if Ethernet connections are possible
     */
    public synchronized boolean isAvailable() {
        /*
         * TODO: Need to also look at scan results to see whether we're
         * in range of any access points. If we have scan results that
         * are no more than N seconds old, use those, otherwise, initiate
         * a scan and wait for the results. This only matters if we
         * allow mobile to be the preferred network.
         */
         return (mTornDownByConnMgr || !isDriverStopped());
    }

    /**
     * {@inheritDoc}
     * There are currently no defined Ethernet subtypes.
     */
    public int getNetworkSubtype() {
        return 0;
    }

    /**
     * Helper method: updates the network info object to keep it in sync with
     * the Ethernet state tracker.
     */
    private void updateNetworkInfo() {
        mNetworkInfo.setIsAvailable(isAvailable());
    }

    /**
     * Report whether the Ethernet connection has successfully acquired an IP address.
     * @return {@code true} if the Ethernet connection has been assigned an IP address.
     */
    public boolean hasIpAddress() {
        return mHaveIpAddress;
    }


    /**
     * Send the tracker a notification that the state of Ethernet connectivity
     * has changed.
     * @param networkId the configured network on which the state change occurred
     * @param newState the new network state
     * @param BSSID when the new state is {@link DetailedState#CONNECTED
     * NetworkInfo.DetailedState.CONNECTED},
     * this is the MAC address of the access point. Otherwise, it
     * is {@code null}.
     */
    void notifyStateChange(DetailedState newState) {
        Message msg = Message.obtain(
            this, EVENT_NETWORK_STATE_CHANGED,
            new NetworkStateChangeResult(newState));
        msg.sendToTarget();
    }

    /**
     * Send the tracker a notification that the Ethernet driver has been stopped.
     */
    void notifyDriverStopped() {
        // Send a driver stopped message to our handler
        Message.obtain(this, EVENT_DRIVER_STATE_CHANGED, DRIVER_STOPPED, 0).sendToTarget();
    }

    /**
     * Send the tracker a notification that the Ethernet driver has been restarted after
     * having been stopped.
     */
    void notifyDriverStarted() {
        // Send a driver started message to our handler
        Message.obtain(this, EVENT_DRIVER_STATE_CHANGED, DRIVER_STARTED, 0).sendToTarget();
    }

    /**
     * Send the tracker a notification that the Ethernet driver has hung and needs restarting.
     */
    void notifyDriverHung() {
        // Send a driver hanged message to our handler
        Message.obtain(this, EVENT_DRIVER_STATE_CHANGED, DRIVER_HUNG, 0).sendToTarget();
    }

    /**
     * Send the tracker a notification that driver has been loaded
     */
    public void notifyEthernetPluggedandUp() {
        sendEmptyMessage(EVENT_ETHERNET_PLUGGED_AND_UP);
    }

    /**
     * Send the tracker a notification that driver has been unloaded
     */
    public void notifyEthernetpluggedAndDown() {
        sendEmptyMessage(EVENT_ETHERNET_PLUGGED_AND_DOWN);
    }
    
    /**
     * Send the tracker a notification that driver has been unloaded
     */
    public void notifyEthernetUnpluggedAndDown() {
        sendEmptyMessage(EVENT_ETHERNET_UNPLUGGED_AND_DOWN);
    }

    /**
     * Send the tracker a notification that driver has been unloaded
     */
    public void notifyEthernetUnpluggedAndUp() {
        sendEmptyMessage(EVENT_ETHERNET_UNPLUGGED_AND_UP);
    }

    /**
     * Send the tracker a notification that driver has been unloaded
     */
    public void notifyEthernetUnKnown() {
        sendEmptyMessage(EVENT_ETHERNET_UNKNOWN);
    }

    /**
     * Send the tracker a notification that driver has been unloaded
     */
    public void notifyEthernetError() {
        sendEmptyMessage(EVENT_ETHERNET_ERROR);
    }

    /**
     * Send the tracker a notification that driver has been unloaded
     */
    public void notifyEthernetDisabled() {
        sendEmptyMessage(EVENT_ETHERNET_DISABLED);
    }
    /**
     * Set the interval timer for polling connection information
     * that is not delivered asynchronously.
     */
    private synchronized void checkPollTimer() {
    }

    /**
     * TODO: mRunState is not synchronized in some places
     * address this as part of re-architect.
     *
     * TODO: We are exposing an additional public synchronized call
     * for a wakelock optimization in EthernetService. Remove it
     * when we handle the wakelock in ConnectivityService.
     */
    public synchronized boolean isDriverStopped() {
        return mRunState == RUN_STATE_STOPPED || mRunState == RUN_STATE_STOPPING;
    }

    /**
     * We release the wakelock in EthernetService
     * using a timer.
     *
     * TODO:
     * Releasing wakelock using both timer and
     * a call from ConnectivityService requires
     * a rethink. We had problems where EthernetService
     * could keep a wakelock forever if we delete
     * messages in the asynchronous call
     * from ConnectivityService
     */
    @Override
    public void releaseWakeLock() {
    }



    @Override
    public void handleMessage(Message msg) {
        Intent intent;

        switch (msg.what) {
            case EVENT_ETHERNET_PLUGGED_AND_UP:
                Log.i(TAG,"EVENT_ETHERNET_PLUGGED_AND_UP \n");
                mRunState = RUN_STATE_RUNNING;

                checkUseStaticIp();
                /* Reset notification state on new connection */
                //resetNotificationTimer();
                /*
                 * DHCP requests are blocking, so run them in a separate thread.
                 */
                if(!EthernetNative.IpAvailableCommand() || !mUseStaticIp)
                {
                	Log.i(TAG, "Why: " + EthernetNative.IpAvailableCommand() + " - " + mUseStaticIp);
                    Log.i(TAG, "request the ip address");
                    HandlerThread dhcpThread = new HandlerThread("DHCP Handler Thread");
                    dhcpThread.start();
                    mDhcpTarget = new DhcpHandler(dhcpThread.getLooper(), this);
                    //mDhcpTarget.sendEmptyMessage(EVENT_DHCP_START);
                    setDetailedState(DetailedState.OBTAINING_IPADDR);
                    sendNetworkStateChangeBroadcast();
                    configureInterface();
                }else
                {
                	Log.i(TAG, "Setting static ip address...");
                    mHaveIpAddress = true;
                    configureInterface();
                }
                
                mTornDownByConnMgr = false;

                //requestConnectionInfo();
                /*
                 * Filter out multicast packets. This saves battery power, since
                 * the CPU doesn't have to spend time processing packets that
                 * are going to end up being thrown away.
                 */
                //mEM.initializeMulticastFiltering();
                if(mHaveIpAddress)
                {
                    setDetailedState(DetailedState.CONNECTED);
                    sendNetworkStateChangeBroadcast();
                }
                mStateTrackerLastEvent = msg.what;
                break;
            case EVENT_ETHERNET_UNKNOWN:
                Log.i(TAG,"EVENT_ETHERNET_UNKNOWN \n");
                mRunState = RUN_STATE_STARTING;

                if(mStateTrackerLastEvent == EVENT_ETHERNET_UNPLUGGED_AND_DOWN)
                {                
                    upEthernet();
                    setDetailedState(DetailedState.CONNECTING);
                    sendNetworkStateChangeBroadcast();
                    mHaveIpAddress = false;
                    mObtainingIpAddress = false;
                }else
                {
                    resetConnections(false);
                    if (mDhcpTarget != null) {
                        mDhcpTarget.getLooper().quit();
                        mDhcpTarget = null;
                    }
                    mHaveIpAddress = false;
                    mObtainingIpAddress = false;
                    setDetailedState(DetailedState.DISCONNECTED);
                    sendNetworkStateChangeBroadcast();                     
                }
                mStateTrackerLastEvent = msg.what;
                break;
                
            case EVENT_ETHERNET_PLUGGED_AND_DOWN:
                Log.i(TAG,"EVENT_ETHERNET_PLUGGED_AND_DOWN \n");
                mRunState = RUN_STATE_STOPPED;
                
                if(mStateTrackerLastEvent == EVENT_ETHERNET_UNPLUGGED_AND_DOWN)
                {
                    upEthernet();
                    setDetailedState(DetailedState.CONNECTING);
                    sendNetworkStateChangeBroadcast();
                    mHaveIpAddress = false;
                    mObtainingIpAddress = false;
                }else
                {
                    resetConnections(false);
                    if (mDhcpTarget != null) {
                        mDhcpTarget.getLooper().quit();
                        mDhcpTarget = null;
                    }
                    mHaveIpAddress = false;
                    mObtainingIpAddress = false;
                    setDetailedState(DetailedState.DISCONNECTED);
                    sendNetworkStateChangeBroadcast();                                          
                }
                mStateTrackerLastEvent = msg.what;
                break;

            case EVENT_ETHERNET_UNPLUGGED_AND_UP:                
            case EVENT_ETHERNET_UNPLUGGED_AND_DOWN:

                Log.i(TAG,"EVENT_ETHERNET_UNPLUGGED_AND_DOWN \n");
                mRunState = RUN_STATE_STOPPED;

                resetConnections(false);
                if (mDhcpTarget != null) {
                    mDhcpTarget.getLooper().quit();
                    mDhcpTarget = null;
                }
                mHaveIpAddress = false;
                mObtainingIpAddress = false;
                
            case EVENT_ETHERNET_DISABLED:
                mRunState = RUN_STATE_STOPPED;
                setDetailedState(DetailedState.DISCONNECTED);
                sendNetworkStateChangeBroadcast();    
                mStateTrackerLastEvent = msg.what;            
                break;
            case EVENT_NETWORK_STATE_CHANGED:
                mStateTrackerLastEvent = msg.what;
                break;

            case EVENT_INTERFACE_CONFIGURATION_SUCCEEDED:

                mReconnectCount = 0;
                mHaveIpAddress = true;
                mObtainingIpAddress = false;
                mStateTrackerLastEvent = msg.what;
                if (mNetworkInfo.getDetailedState() != DetailedState.CONNECTED) {
                    setDetailedState(DetailedState.CONNECTED);
                    sendNetworkStateChangeBroadcast();
                } else {
                    msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
                    msg.sendToTarget();
                }
                
                Log.v(TAG, "IP configuration: " + mDhcpInfo);
                
                break;
            case EVENT_INTERFACE_CONFIGURATION_FAILED:                
            case EVENT_ETHERNET_ERROR:
                mRunState = RUN_STATE_STOPPED;
                resetConnections(false);
                if (mDhcpTarget != null) {
                    mDhcpTarget.getLooper().quit();
                    mDhcpTarget = null;
                }
                mHaveIpAddress = false;
                mObtainingIpAddress = false;
                
                setDetailedState(DetailedState.FAILED);
                sendNetworkStateChangeBroadcast();    
                mStateTrackerLastEvent = msg.what;            
                break;

        }
    }


    private void configureInterface() {
        checkPollTimer();
        if (!mUseStaticIp) {
            if (!mHaveIpAddress && !mObtainingIpAddress) {
                mObtainingIpAddress = true;
                if(mDhcpTarget != null) mDhcpTarget.sendEmptyMessage(EVENT_DHCP_START);
            }
        } else {
            int event;
            if (NetworkUtils.configureInterface(mInterfaceName, mDhcpInfo)) {
                mHaveIpAddress = true;
                event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                if (LOCAL_LOGD) Log.v(TAG, "Static IP configuration succeeded");
            } else {
                setRadio(false);
                mHaveIpAddress = false;
                event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                if (LOCAL_LOGD) Log.v(TAG, "Static IP configuration failed");
                setRadio(true);

            }
            sendEmptyMessage(event);
        }
    }

    /**
     * Reset our IP state and send out broadcasts following a disconnect.
     * @param newState the {@code DetailedState} to set. Should be either
     * {@code DISCONNECTED} or {@code FAILED}.
     * @param disableInterface indicates whether the interface should
     * be disabled
     */
    private void handleDisconnectedState(DetailedState newState, boolean disableInterface) {
        if (mDisconnectPending) {
            cancelDisconnect();
        }
        mDisconnectExpected = false;
        resetConnections(disableInterface);
        setDetailedState(newState);
        mDisconnectPending = false;
    }

    /**
     * Resets the Ethernet Connections by clearing any state, resetting any sockets
     * using the interface, stopping DHCP, and disabling the interface.
     */
    public void resetConnections(boolean disableInterface) {
        if (LOCAL_LOGD) Log.d(TAG, "Reset connections and stopping DHCP");
        mHaveIpAddress = false;
        mObtainingIpAddress = false;

        /*
         * Reset connection depends on both the interface and the IP assigned,
         * so it should be done before any chance of the IP being lost.
         */
         NetworkUtils.resetConnections(mInterfaceName);

        if(mDhcpTarget == null) Log.w(TAG,"mDhcpTarget is  null");
        else                    Log.w(TAG,"mDhcpTarget is not null");
        
        // Stop DHCP
        if(mDhcpTarget != null){
            mDhcpTarget.setCancelCallback(true);
            mDhcpTarget.removeMessages(EVENT_DHCP_START);
        }

        if (!NetworkUtils.stopDhcp(mInterfaceName)) {
            Log.e(TAG, "Could not stop DHCP");
        }

        if(disableInterface) {
            if (LOCAL_LOGD) Log.d(TAG, "Disabling interface");
            NetworkUtils.disableInterface(mInterfaceName);
        }

    }


    private void scheduleDisconnect() {
        mDisconnectPending = true;
        if (!hasMessages(EVENT_DEFERRED_DISCONNECT)) {
            sendEmptyMessageDelayed(EVENT_DEFERRED_DISCONNECT, DISCONNECT_DELAY_MSECS);
        }
    }

    private void cancelDisconnect() {
        mDisconnectPending = false;
        removeMessages(EVENT_DEFERRED_DISCONNECT);
    }

    public DhcpInfo getDhcpInfo() {
        return mDhcpInfo;
    }

    private void sendNetworkStateChangeBroadcast() {
        Intent intent = new Intent(EthernetManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(EthernetManager.EXTRA_NETWORK_INFO, mNetworkInfo);
        mContext.sendStickyBroadcast(intent);
    }

    /**
     * Disable Ethernet connectivity by stopping the driver.
     */
    public boolean teardown() {
        if (!mTornDownByConnMgr) {
            if (disconnectAndStop()) {
                setTornDownByConnMgr(true);
                return true;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    /**
     * Reenable Ethernet connectivity by restarting the driver.
     */
    public boolean reconnect() {
        if (mTornDownByConnMgr) {
            if (restart()) {
                setTornDownByConnMgr(false);
                return true;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    /**
     * We want to stop the driver, but if we're connected to a network,
     * we first want to disconnect, the driver is stopped.
     * @return {@code true} if the operation succeeds, which means that the
     * disconnect or stop command was initiated.
     */
    public synchronized boolean disconnectAndStop() {
        boolean ret = true;;
        if (mRunState != RUN_STATE_STOPPING && mRunState != RUN_STATE_STOPPED) {
            resetConnections(false);
            ret = stopDriver();
            mRunState = RUN_STATE_STOPPING;
        }
        return ret;
    }

    public synchronized boolean restart() {
        if (isDriverStopped()) {
            mRunState = RUN_STATE_STARTING;
            resetConnections(false);
            return startDriver();
        }
        return true;
    }

    public int getEthernetState() {
        return mEthernetState.get();
    }

    public void setEthernetState(int ethernetState) {
        mEthernetState.set(ethernetState);
    }

    public boolean isAnyNetworkDisabled() {
        return mIsAnyNetworkDisabled.get();
    }

   /**
     * The EthernetNative interface functions are listed below.
     * The only native call that is not synchronized on
     * EthernetStateTracker is waitForEvent() which waits on a
     * seperate monitor channel.
     *
     * All commands that can cause commands to driver
     * initiated need the driver state to be started.
     * This is done by checking isDriverStopped() to
     * be false.
     */

    /**
     * Load the driver and firmware
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean loadDriver() {
        return EthernetNative.loadDriver();
    }

    /**
     * Unload the driver and firmware
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean unloadDriver() {
        return EthernetNative.unloadDriver();
    }

    /**
     * Start driver
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean startDriver() {
        if (mEthernetState.get() != ETHERNET_STATE_ENABLED) {
            return false;
        }
        return EthernetNative.startDriverCommand();
    }

    /**
     * Stop driver
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean stopDriver() {
        if (mEthernetState.get() != ETHERNET_STATE_ENABLED || mRunState == RUN_STATE_STOPPED) {
            return false;
        }
        return EthernetNative.stopDriverCommand();
    }

    /**
     * up ethernet
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean upEthernet() {
        return EthernetNative.upEthernetCommand();
    }

    /**
     * down ethernet
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean downEthernet() {
        return EthernetNative.downEthernetCommand();
    }
    

    public boolean setRadio(boolean turnOn) {
        return mEM.setEthernetEnabled(turnOn);
    }

    /**
     * {@inheritDoc}
     * There are currently no Ethernet-specific features supported.
     * @param feature the name of the feature
     * @return {@code -1} indicating failure, always
     */
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * {@inheritDoc}
     * There are currently no Ethernet-specific features supported.
     * @param feature the name of the feature
     * @return {@code -1} indicating failure, always
     */
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }



    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("interface ").append(mInterfaceName);
        sb.append(" runState=");
        if (mRunState >= 1 && mRunState <= mRunStateNames.length) {
            sb.append(mRunStateNames[mRunState-1]);
        } else {
            sb.append(mRunState);
        }
        sb.append(mDhcpInfo).append(LS);
        sb.append("haveIpAddress=").append(mHaveIpAddress).
                append(", obtainingIpAddress=").append(mObtainingIpAddress).
                append(", explicitlyDisabled=").append(mTornDownByConnMgr);
        return sb.toString();
    }

    private class DhcpHandler extends Handler {

        private Handler mTarget;
        
        /**
         * Whether to skip the DHCP result callback to the target. For example,
         * this could be set if the network we were requesting an IP for has
         * since been disconnected.
         * <p>
         * Note: There is still a chance where the client's intended DHCP
         * request not being canceled. For example, we are request for IP on
         * A, and he queues request for IP on B, and then cancels the request on
         * B while we're still requesting from A.
         */
        private boolean mCancelCallback;

        /**
         * Instance of the bluetooth headset helper. This needs to be created
         * early because there is a delay before it actually 'connects', as
         * noted by its javadoc. If we check before it is connected, it will be
         * in an error state and we will not disable coexistence.
         */
        
        public DhcpHandler(Looper looper, Handler target) {
            super(looper);
            mTarget = target;
            
        }

        public void handleMessage(Message msg) {
            int event;

            switch (msg.what) {
                case EVENT_DHCP_START:
                    Log.i(TAG,"EVENT_DHCP_START ");

                    synchronized (this) {
                        // A new request is being made, so assume we will callback
                        mCancelCallback = false;
                    }
                    Log.d(TAG, "DhcpHandler: DHCP request started "+ mInterfaceName);
                    if (NetworkUtils.runDhcp(mInterfaceName, mDhcpInfo)) {
                        event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                        if (LOCAL_LOGD) Log.v(TAG, "DhcpHandler: DHCP request succeeded");
                    } else {
                        event = EVENT_INTERFACE_CONFIGURATION_FAILED;
                        Log.i(TAG, "DhcpHandler: DHCP request failed: " +
                            NetworkUtils.getDhcpError());
                    }

                    synchronized (this) {
                        if (!mCancelCallback) {
                            mTarget.sendEmptyMessage(event);
                        }
                    }
                    break;
            }
        }

        public synchronized void setCancelCallback(boolean cancelCallback) {
            mCancelCallback = cancelCallback;
        }

        /**
         * Whether to disable coexistence mode while obtaining IP address. This
         * logic will return true only if the current bluetooth
         * headset/handsfree state is disconnected. This means if it is in an
         * error state, we will NOT disable coexistence mode to err on the side
         * of safety.
         * 
         * @return Whether to disable coexistence mode.
         */

    }
    
    private void checkUseStaticIp() {
        mUseStaticIp = false;
        final ContentResolver cr = mContext.getContentResolver();
        try {
            if (Settings.System.getInt(cr, Settings.System.ETHERNET_USE_STATIC_IP) == 0) {
            	Log.w(TAG, "checkUseStaticIp: ETHERNET_USE_STATIC_IP == 0");
                return;
            }
        } catch (Settings.SettingNotFoundException e) {
        	Log.w(TAG, "checkUseStaticIp: SettingNotFoundException");
            return;
        }

        try {
            String addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_IP);
            if (addr != null) {
                mDhcpInfo.ipAddress = stringToIpAddr(addr);
            } else {
            	Log.i(TAG, "checkUseStaticIp: addr is null");
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_GATEWAY);
            if (addr != null) {
                mDhcpInfo.gateway = stringToIpAddr(addr);
            } else {
            	Log.i(TAG, "checkUseStaticIp: gateway is null");
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_NETMASK);
            if (addr != null) {
                mDhcpInfo.netmask = stringToIpAddr(addr);
            } else {
            	Log.i(TAG, "checkUseStaticIp: netmask is null");
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_DNS1);
            if (addr != null) {
                mDhcpInfo.dns1 = stringToIpAddr(addr);
            } else {
            	Log.i(TAG, "checkUseStaticIp: dns1 is null");
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_DNS2);
            if (addr != null) {
                mDhcpInfo.dns2 = stringToIpAddr(addr);
            } else {
                mDhcpInfo.dns2 = 0;
            }
        } catch (UnknownHostException e) {
            return;
        }
        mUseStaticIp = true;
    }

    private static int stringToIpAddr(String addrString) throws UnknownHostException {
        try {
            String[] parts = addrString.split("\\.");
            if (parts.length != 4) {
                throw new UnknownHostException(addrString);
            }

            int a = Integer.parseInt(parts[0])      ;
            int b = Integer.parseInt(parts[1]) <<  8;
            int c = Integer.parseInt(parts[2]) << 16;
            int d = Integer.parseInt(parts[3]) << 24;

            return a | b | c | d;
        } catch (NumberFormatException ex) {
            throw new UnknownHostException(addrString);
        }
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.ETHERNET_USE_STATIC_IP), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.ETHERNET_STATIC_IP), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.ETHERNET_STATIC_GATEWAY), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.ETHERNET_STATIC_NETMASK), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.ETHERNET_STATIC_DNS1), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.ETHERNET_STATIC_DNS2), false, this);
        }

        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            boolean wasStaticIp = mUseStaticIp;
            int oIp, oGw, oMsk, oDns1, oDns2;
            oIp = oGw = oMsk = oDns1 = oDns2 = 0;
            if (wasStaticIp) {
                oIp = mDhcpInfo.ipAddress;
                oGw = mDhcpInfo.gateway;
                oMsk = mDhcpInfo.netmask;
                oDns1 = mDhcpInfo.dns1;
                oDns2 = mDhcpInfo.dns2;
            }
            checkUseStaticIp();


            boolean changed =
                (wasStaticIp != mUseStaticIp) ||
                    (wasStaticIp && (
                        oIp   != mDhcpInfo.ipAddress ||
                        oGw   != mDhcpInfo.gateway ||
                        oMsk  != mDhcpInfo.netmask ||
                        oDns1 != mDhcpInfo.dns1 ||
                        oDns2 != mDhcpInfo.dns2));

            if (changed) {
                resetConnections(true);
                configureInterface();
                if (mUseStaticIp) {
                    Message msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
                    msg.sendToTarget();
                }
            }
        }
    }
}
