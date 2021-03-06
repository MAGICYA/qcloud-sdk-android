package com.tencent.cos.xml.transfer;


import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.tencent.cos.xml.CosXmlSimpleService;
import com.tencent.cos.xml.common.COSRequestHeaderKey;
import com.tencent.cos.xml.common.ClientErrorCode;
import com.tencent.cos.xml.exception.CosXmlClientException;
import com.tencent.cos.xml.exception.CosXmlServiceException;
import com.tencent.cos.xml.listener.CosXmlProgressListener;
import com.tencent.cos.xml.listener.CosXmlResultListener;
import com.tencent.cos.xml.model.CosXmlRequest;
import com.tencent.cos.xml.model.CosXmlResult;
import com.tencent.cos.xml.model.object.GetObjectRequest;
import com.tencent.cos.xml.model.object.HeadObjectRequest;
import com.tencent.cos.xml.utils.DigestUtils;
import com.tencent.qcloud.core.common.QCloudTaskStateListener;
import com.tencent.qcloud.core.http.HttpTask;
import com.tencent.qcloud.core.logger.QCloudLogger;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by bradyxiao on 2018/8/23.
 * Copyright 2010-2018 Tencent Cloud. All Rights Reserved.
 */

public final class COSXMLDownloadTask extends COSXMLTask{

    private final static String TAG = COSXMLUploadTask.class.getSimpleName();

    private String localSaveDirPath;
    private String localSaveFileName;

    /** 下载 */
    private long rangeStart = 0L;
    private long rangeEnd = -1L;
    private long fileOffset = 0L;
    private String eTag;
    private long hasWriteDataLen = 0L;
    private HeadObjectRequest headObjectRequest;
    private GetObjectRequest getObjectRequest;
    private SharedPreferences sharedPreferences;

    COSXMLDownloadTask(Context context, CosXmlSimpleService cosXmlService, String region, String bucket, String cosPath, String localSaveDirPath, String localSaveFileName){
        this.region = region;
        this.bucket = bucket;
        this.cosPath = cosPath;
        this.localSaveDirPath = localSaveDirPath;
        this.localSaveFileName = localSaveFileName;
        this.cosXmlService = cosXmlService;
        if(context != null){
            sharedPreferences = context.getSharedPreferences("COSXMLDOWNLOADTASK", 0);
        }
    }

    COSXMLDownloadTask(Context context, CosXmlSimpleService cosXmlService, GetObjectRequest getObjectRequest){

        this(context, cosXmlService, getObjectRequest.getRegion(), getObjectRequest.getBucket(),
                getObjectRequest.getPath(cosXmlService.getConfig()), getObjectRequest.getSavePath(), getObjectRequest.getSaveFileName());
        this.queries = getObjectRequest.getQueryString();
        this.headers = getObjectRequest.getRequestHeaders();
        this.isNeedMd5 = getObjectRequest.isNeedMD5();
        //需要取出range字段
        if(this.headers != null && this.headers.containsKey(COSRequestHeaderKey.RANGE)){
            List<String> ranges = this.headers.get(COSRequestHeaderKey.RANGE);
            String range = ranges.get(0);
            int index1 = range.indexOf("=");
            int index2 = range.indexOf("-");
            rangeStart = Long.valueOf(range.substring(index1 + 1, index2));
            String end = range.substring(index2 + 1);
            if(!TextUtils.isEmpty(end)){
                rangeEnd = Long.valueOf(end);
            }
        }
        this.fileOffset = getObjectRequest.getFileOffset();
    }

    protected void download(){
        run();
    }

    private void realDownload(long rangeStart, long rangeEnd, final long fileOffset){
        getObjectRequest = new GetObjectRequest(bucket, cosPath, localSaveDirPath, localSaveFileName);
        getObjectRequest.setRegion(region);
        if(rangeEnd > 0 || rangeStart > 0){
            getObjectRequest.setRange(rangeStart, rangeEnd);
        }
        getObjectRequest.setFileOffset(fileOffset);
        getObjectRequest.setQueryParameters(queries);
        getObjectRequest.setRequestHeaders(headers);

        if(onSignatureListener != null){
            getObjectRequest.setSign(onSignatureListener.onGetSign(getObjectRequest));
        }

        getHttpMetrics(getObjectRequest, "GetObjectRequest");

        getObjectRequest.setProgressListener(new CosXmlProgressListener() {
            @Override
            public void onProgress(long complete, long target) {
                if(cosXmlProgressListener != null){

                    cosXmlProgressListener.onProgress(hasWriteDataLen + complete, hasWriteDataLen + target);
                }
            }
        });
        cosXmlService.getObjectAsync(getObjectRequest, new CosXmlResultListener() {
            @Override
            public void onSuccess(CosXmlRequest request, CosXmlResult result) {
                if(updateState(TransferState.COMPLETED)){
                    // complete -> success
                    clear();
                    QCloudLogger.d(TAG, taskState.name());
                    mResult = buildCOSXMLTaskResult(result);
                    if(cosXmlResultListener != null){
                        cosXmlResultListener.onSuccess(buildCOSXMLTaskRequest(request), mResult);
                    }

                }
            }

            @Override
            public void onFail(CosXmlRequest request, CosXmlClientException exception, CosXmlServiceException serviceException) {
//                Log.e("STATE", exception != null ? exception.getMessage() : "null");
                if(exception != null && exception.getMessage().toUpperCase().contains("CANCELED")){
                    return;
                }else {
                    if(updateState(TransferState.FAILED)){
                        // failed -> error
//                           QCloudLogger.d(TAG, taskState.name());
                        mException = exception == null ? serviceException : exception;
                        if(cosXmlResultListener != null){
                            cosXmlResultListener.onFail(buildCOSXMLTaskRequest(request), exception, serviceException);
                        }
                    }
                }
            }
        });
    }

    @Override
    public void pause() {
        if(updateState(TransferState.PAUSED)){
            QCloudLogger.d(TAG, taskState.name());
            cosXmlService.cancel(headObjectRequest);
            headObjectRequest = null;
            cosXmlService.cancel(getObjectRequest);
            getObjectRequest = null;
        }
    }

    @Override
    public void cancel(){
        if(updateState(TransferState.CANCELED)){
            QCloudLogger.d(TAG, taskState.name());
            CosXmlClientException cosXmlClientException = new CosXmlClientException(ClientErrorCode.USER_CANCELLED.getCode(), "canceled by user");
            mException = cosXmlClientException;
            clear();
            if(cosXmlResultListener != null){
                cosXmlResultListener.onFail(buildCOSXMLTaskRequest(null), cosXmlClientException, null);
            }
            cosXmlService.cancel(headObjectRequest);
            headObjectRequest = null;
            cosXmlService.cancel(getObjectRequest);
            getObjectRequest = null;
        }
    }

    @Override
    public void resume() {
        if(updateState(TransferState.RESUMED_WAITING)){
            QCloudLogger.d(TAG, taskState.name());
            download();
        }
    }

    @Override
    protected CosXmlRequest buildCOSXMLTaskRequest(CosXmlRequest sourceRequest) {
        COSXMLDownloadTaskRequest cosxmlDownloadTaskRequest = new COSXMLDownloadTaskRequest(region, bucket, cosPath,
                localSaveDirPath, localSaveFileName, headers, queries);
       return cosxmlDownloadTaskRequest;
    }

    @Override
    protected CosXmlResult buildCOSXMLTaskResult(CosXmlResult sourceResult) {
        COSXMLDownloadTaskResult cosxmlDownloadTaskResult = new COSXMLDownloadTaskResult();
        if(sourceResult != null){
            cosxmlDownloadTaskResult.httpCode = sourceResult.httpCode;
            cosxmlDownloadTaskResult.httpMessage = sourceResult.httpMessage;
            cosxmlDownloadTaskResult.headers = sourceResult.headers;
            cosxmlDownloadTaskResult.eTag = eTag;
            cosxmlDownloadTaskResult.accessUrl = sourceResult.accessUrl;
        }
        return cosxmlDownloadTaskResult;
    }

    private String getKey(){
        StringBuffer stringBuffer = new StringBuffer();
        stringBuffer.append("download").append("_")
                .append(region).append("_")
                .append(bucket).append("_")
                .append(cosPath).append("_")
                .append(rangeStart).append("_")
                .append(rangeEnd).append("_")
                .append(fileOffset).append("_")
                .append(localSaveDirPath).append("_")
                .append(localSaveFileName).append("_")
                .append(eTag);
        try {
            return DigestUtils.getSha1(stringBuffer.toString());
        } catch (CosXmlClientException e) {
            return stringBuffer.toString();
        }
    }

    private synchronized String hasExisted(){
        if(sharedPreferences != null){
            return sharedPreferences.getString(getKey(), null);
        }
        return null;
    }

    private synchronized void clear(){
        if(sharedPreferences != null){
            sharedPreferences.edit().remove(getKey()).commit();
        }
    }

    private synchronized void save(String absolutePath){
        if(sharedPreferences != null){
            sharedPreferences.edit().putString(getKey(), absolutePath).commit();
        }
    }
    private String getDownloadPath(){
        String path  = null;
        if(localSaveDirPath != null){
            if(!localSaveDirPath.endsWith("/")){
                path = localSaveDirPath + "/";
            }else{
                path = localSaveDirPath;
            }
            File file = new File(path);
            if(!file.exists()){
                file.mkdirs();
            }
            if (localSaveFileName != null){
                path = path + localSaveFileName;
                return path;
            }
            if(cosPath != null){
                int separator = cosPath.lastIndexOf("/");
                if(separator >= 0){
                    path = path + cosPath.substring(separator + 1);
                }else{
                    path = path + cosPath;
                }
            }
        }
        return path;
    }

    protected void run() {
        updateState(TransferState.WAITING); // waiting
        QCloudLogger.d(TAG, taskState.name());
        headObjectRequest = new HeadObjectRequest(bucket, cosPath);
        headObjectRequest.setRegion(region);

        if(onSignatureListener != null){
            headObjectRequest.setSign(onSignatureListener.onGetSign(headObjectRequest));
        }

        getHttpMetrics(headObjectRequest, "HeadObjectRequest");

        headObjectRequest.setTaskStateListener(new QCloudTaskStateListener() {
            @Override
            public void onStateChanged(String taskId, int state) {
                if(state == HttpTask.STATE_EXECUTING){
                    updateState(TransferState.IN_PROGRESS); // running
                    QCloudLogger.d(TAG, taskState.name());
                }
            }
        });

        cosXmlService.headObjectAsync(headObjectRequest, new CosXmlResultListener() {
            @Override
            public void onSuccess(CosXmlRequest request, CosXmlResult result) {
                List<String> eTags = result.headers.get("ETag");
                if(eTags != null && eTags.size() > 0){
                    eTag = eTags.get(0);
                }
                String absolutePath = hasExisted();
                if(absolutePath != null){
                    File file = new File(absolutePath);
                    if(file.exists()){
                        long fileLength = file.length();
                        List<String> contentLengths = result.headers.get("Content-Length");
                        if(contentLengths != null && contentLengths.size() > 0 && Long.valueOf(contentLengths.get(0)) == fileLength){
                            if(updateState(TransferState.COMPLETED)){
                                if(cosXmlProgressListener != null){
                                    cosXmlProgressListener.onProgress(fileLength, fileLength);
                                }
                                if(cosXmlResultListener != null){
                                    cosXmlResultListener.onSuccess(request, result);
                                }
                            }
                            clear();
                            return;
                        }else {
                            hasWriteDataLen = fileLength - fileOffset;
                            realDownload(rangeStart + hasWriteDataLen, rangeEnd, fileOffset + hasWriteDataLen);
                            return;
                        }
                    }
                }
                save(getDownloadPath());
                hasWriteDataLen = 0L;
                realDownload(rangeStart, rangeEnd, fileOffset);
            }

            @Override
            public void onFail(CosXmlRequest request, CosXmlClientException exception, CosXmlServiceException serviceException) {
//                Log.e("STATE", exception != null ? exception.getMessage() : "null");
                if(exception != null && exception.getMessage().toUpperCase().contains("CANCELED")){
                    return;
                }else {
                    if(updateState(TransferState.FAILED)){
                        // failed -> error
//                           QCloudLogger.d(TAG, taskState.name());
                        mException = exception == null ? serviceException : exception;
                        if(cosXmlResultListener != null){
                            cosXmlResultListener.onFail(buildCOSXMLTaskRequest(request), exception, serviceException);
                        }
                    }
                }
            }
        });
    }

    public static class COSXMLDownloadTaskRequest extends GetObjectRequest{

        protected COSXMLDownloadTaskRequest(String region, String bucket, String cosPath, String savePath, String saveFileName, Map<String, List<String>> headers,
                                            Map<String, String> queryStr){
            super(bucket, cosPath, savePath, saveFileName);
            this.setRegion(region);
            this.setRequestHeaders(headers);
            this.setQueryParameters(queryStr);
        }
    }

    public static class COSXMLDownloadTaskResult extends CosXmlResult{
        protected COSXMLDownloadTaskResult(){}

        public String eTag;
    }
}
