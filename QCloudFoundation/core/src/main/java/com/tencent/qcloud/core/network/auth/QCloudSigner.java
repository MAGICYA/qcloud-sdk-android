package com.tencent.qcloud.core.network.auth;

import com.tencent.qcloud.core.network.QCloudRealCall;
import com.tencent.qcloud.core.network.exception.QCloudClientException;

import okhttp3.Request;

/**
 * Created by wjielai on 2017/9/21.
 * <p>
 * Copyright (c) 2010-2017 Tencent Cloud. All rights reserved.
 */

public interface QCloudSigner {
    /**
     * Sign the given request with the given set of credentials. Modifies the
     * passed-in request to apply the signature.
     *
     * @param request The request to sign.
     * @param credentials The credentials to sign the request with.
     */
    void sign(QCloudRealCall request, QCloudCredentials credentials) throws QCloudClientException;
}