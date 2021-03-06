package com.tencent.cos.xml.common;


/**
 * Created by bradyxiao on 2018/10/31.
 * Copyright 2010-2018 Tencent Cloud. All Rights Reserved.
 */

public enum ClientErrorCode {

    INVALID_ARGUMENT(10000, "InvalidArgument"),
    INVALID_CREDENTIALS(10001, "InvalidCredentials"),
    BAD_REQUEST(10002, "BadRequest"),
    SINK_SOURCE_NOT_FOUND(10003, "SinkSourceNotFound"),

    INTERNAL_ERROR(20000, "InternalError"),
    SERVERERROR(20001, "ServerError"),
    IO_ERROR(20002, "IOError"),
    POOR_NETWORK(20003, "NetworkError"),

    USER_CANCELLED(30000, "UserCancelled"),
    ALREADY_FINISHED(30001, "AlreadyFinished");

    private int code;
    private String errorMsg;

    ClientErrorCode(int code, String errorMsg){
        this.code = code;
        this.errorMsg = errorMsg;
    }

    public void setErrorMsg(String errorMsg){
        this.errorMsg = errorMsg;
    }

    public int getCode() {
        return code;
    }

    public String getErrorMsg() {
        return errorMsg;
    }
}
