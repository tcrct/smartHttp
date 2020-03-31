/*******************************************************************************
 * Copyright (c) 2017-2020, org.smartboot. All rights reserved.
 * project name: smart-http
 * file name: HttpMessageProcessor.java
 * Date: 2020-01-01
 * Author: sandao (zhengjunweimail@163.com)
 ******************************************************************************/

package org.smartboot.http.server;

import org.smartboot.http.HttpRequest;
import org.smartboot.http.HttpResponse;
import org.smartboot.http.Pipeline;
import org.smartboot.http.enums.HttpMethodEnum;
import org.smartboot.http.enums.HttpStatus;
import org.smartboot.http.exception.HttpException;
import org.smartboot.http.server.handle.HandlePipeline;
import org.smartboot.http.server.handle.HttpHandle;
import org.smartboot.http.utils.AttachKey;
import org.smartboot.http.utils.Attachment;
import org.smartboot.http.utils.HttpHeaderConstant;
import org.smartboot.http.utils.StringUtils;
import org.smartboot.socket.MessageProcessor;
import org.smartboot.socket.StateMachineEnum;
import org.smartboot.socket.transport.AioSession;

import java.io.IOException;

/**
 * @author 三刀
 * @version V1.0 , 2018/6/10
 */
public class HttpMessageProcessor implements MessageProcessor<BaseHttpRequest> {
    private final AttachKey<Http11Request> ATTACH_KEY_HTTP_REQUEST = AttachKey.valueOf("httpRequest");
    private final HandlePipeline httpPipeline = new HandlePipeline();
    private final HandlePipeline wsPipeline = new HandlePipeline();

    public HttpMessageProcessor() {
        httpPipeline.next(new RFC2612RequestHandle());
        wsPipeline.next(new WebSocketHandle());
    }

    @Override
    public void process(AioSession<BaseHttpRequest> session, BaseHttpRequest baseHttpRequest) {
        try {
            Attachment attachment = session.getAttachment();
            HttpRequest request;
            HttpResponse response;
            HandlePipeline pipeline;
            if (baseHttpRequest.isWebsocket()) {
                WebSocketRequest webSocketRequest = attachment.get(HttpRequestProtocol.ATTACH_KEY_WS_REQ);
                request = webSocketRequest;
                response = webSocketRequest.getResponse();
                pipeline = wsPipeline;
            } else {
                Http11Request http11Request = attachment.get(ATTACH_KEY_HTTP_REQUEST);
                if (http11Request == null) {
                    http11Request = new Http11Request(baseHttpRequest);
                    attachment.put(ATTACH_KEY_HTTP_REQUEST, http11Request);
                }
                request = http11Request;
                response = http11Request.getResponse();
                pipeline = httpPipeline;
            }

            try {
                pipeline.doHandle(request, response);
            } catch (HttpException e) {
                e.printStackTrace();
                response.setHttpStatus(HttpStatus.valueOf(e.getHttpCode()));
                response.getOutputStream().write(e.getDesc().getBytes());
            } catch (Exception e) {
                e.printStackTrace();
                response.setHttpStatus(HttpStatus.INTERNAL_SERVER_ERROR);
                response.getOutputStream().write(e.fillInStackTrace().toString().getBytes());
            }
            if (!((AbstractOutputStream) response.getOutputStream()).isClosed()) {
                response.getOutputStream().close();
            }
            //Post请求没有读完Body，关闭通道
            if (HttpMethodEnum.POST.getMethod().equals(request.getMethod())
                    && !StringUtils.startsWith(request.getContentType(), HttpHeaderConstant.Values.X_WWW_FORM_URLENCODED)
                    && request.getInputStream().available() > 0) {
                session.close(false);
            } else {
                ((Reset) request).reset();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    @Override
    public void stateEvent(AioSession<BaseHttpRequest> session, StateMachineEnum stateMachineEnum, Throwable throwable) {
        switch (stateMachineEnum) {
            case NEW_SESSION:
                Attachment attachment = new Attachment();
                attachment.put(HttpRequestProtocol.ATTACH_KEY_REQUEST, new BaseHttpRequest(session));
                session.setAttachment(attachment);
                break;
            case PROCESS_EXCEPTION:
                throwable.printStackTrace();
                session.close();
                break;
//            case INPUT_SHUTDOWN:
//                LOGGER.error("inputShutdown", throwable);
//                break;
//            case OUTPUT_EXCEPTION:
//                LOGGER.error("", throwable);
//                break;
//            case INPUT_EXCEPTION:
//                LOGGER.error("",throwable);
//                break;
//            case SESSION_CLOSED:
//                System.out.println("closeSession");
//                LOGGER.info("connection closed:{}");
//                break;
            case DECODE_EXCEPTION:
                throwable.printStackTrace();
                break;
//                default:
//                    System.out.println(stateMachineEnum);
        }
    }

    public Pipeline pipeline(HttpHandle httpHandle) {
        return httpPipeline.next(httpHandle);
    }

    public Pipeline pipeline() {
        return httpPipeline;
    }
}
