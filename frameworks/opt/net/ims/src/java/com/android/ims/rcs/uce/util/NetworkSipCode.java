/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.ims.rcs.uce.util;

/**
 * Define the network sip code and the reason.
 */
public class NetworkSipCode {
    public static final int SIP_CODE_OK = 200;
    public static final int SIP_CODE_ACCEPTED = 202;
    public static final int SIP_CODE_BAD_REQUEST = 400;
    public static final int SIP_CODE_FORBIDDEN = 403;
    public static final int SIP_CODE_NOT_FOUND = 404;
    public static final int SIP_CODE_REQUEST_TIMEOUT = 408;
    public static final int SIP_CODE_INTERVAL_TOO_BRIEF = 423;
    public static final int SIP_CODE_TEMPORARILY_UNAVAILABLE = 480;
    public static final int SIP_CODE_BUSY = 486;
    public static final int SIP_CODE_SERVER_INTERNAL_ERROR = 500;
    public static final int SIP_CODE_SERVICE_UNAVAILABLE = 503;
    public static final int SIP_CODE_SERVER_TIMEOUT = 504;
    public static final int SIP_CODE_BUSY_EVERYWHERE = 600;
    public static final int SIP_CODE_DECLINE = 603;
    public static final int SIP_CODE_DOES_NOT_EXIST_ANYWHERE = 604;

    public static final String SIP_OK = "OK";
    public static final String SIP_ACCEPTED = "Accepted";
    public static final String SIP_BAD_REQUEST = "Bad Request";
    public static final String SIP_SERVICE_UNAVAILABLE = "Service Unavailable";
    public static final String SIP_INTERNAL_SERVER_ERROR = "Internal Server Error";
    public static final String SIP_NOT_REGISTERED = "User not registered";
    public static final String SIP_NOT_AUTHORIZED_FOR_PRESENCE = "not authorized for presence";
}
