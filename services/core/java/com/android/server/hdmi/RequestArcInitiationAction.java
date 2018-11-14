/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import java.util.List;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;

/**
 * Feature action that handles ARC action initiated by TV devices.
 *
 * <p>This action is created by TV's hot plug event of ARC port.
 */
final class RequestArcInitiationAction extends RequestArcAction {
    private static final String TAG = "RequestArcInitiationAction";

    /**
     * @Constructor
     *
     * For more details look at {@link RequestArcAction#RequestArcAction}.
     */
    RequestArcInitiationAction(HdmiCecLocalDevice source, int avrAddress) {
        super(source, avrAddress);
    }

    @Override
    boolean start() {
        // Seq #38
        mState = STATE_WATING_FOR_REQUEST_ARC_REQUEST_RESPONSE;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);

        HdmiCecMessage command = HdmiCecMessageBuilder.buildRequestArcInitiation(
                getSourceAddress(), mAvrAddress);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    // Turn off ARC status if <Request ARC Initiation> fails.
                    tv().setArcStatus(false);
                    finish();
                }
            }
        });

        HdmiCecMessage audioRequestCommand = HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                getSourceAddress(), mAvrAddress, physicalAddressToParam(), true);
        sendCommand(audioRequestCommand, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    HdmiLogger.debug("Failed to send <System Audio Mode Request>:" + error);
                }
            }
        });
        return true;
    }

    private int physicalAddressToParam() {
        HdmiCecLocalDeviceTv localTv = tv();
        int arcPortId = localTv.getAvrDeviceInfo().getPortId();
        int portId = HdmiDeviceInfo.PORT_INVALID;
        int devicePowerStatus = HdmiControlManager.POWER_STATUS_UNKNOWN;
        List<HdmiDeviceInfo> hdmiDeviceInfoList = localTv.getDeviceInfoList(false);
        for (HdmiDeviceInfo hdmiDeviceInfo : hdmiDeviceInfoList) {
            portId = hdmiDeviceInfo.getPortId();
            devicePowerStatus = hdmiDeviceInfo.getDevicePowerStatus();
            if (hdmiDeviceInfo.getDeviceType() != HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM
                    && portId == arcPortId) {
                if (devicePowerStatus == HdmiControlManager.POWER_STATUS_ON) {
                    return hdmiDeviceInfo.getPhysicalAddress();
                }
            }
        }
        return getSystemAudioModeRequestParam();
    }

    private int getSystemAudioModeRequestParam() {
        // <System Audio Mode Request> takes the physical address of the source device
        // as a parameter. Get it from following candidates, in the order listed below:
        // 1) physical address of the active source
        // 2) active routing path
        // 3) physical address of TV
        if (tv().getActiveSource().isValid()) {
            return tv().getActiveSource().physicalAddress;
        }
        int param = tv().getActivePath();
        return param != Constants.INVALID_PHYSICAL_ADDRESS
                ? param : Constants.PATH_INTERNAL;
    }
}
