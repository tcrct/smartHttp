/*
 * Copyright (c) 2018, org.smartboot. All rights reserved.
 * project name: smart-socket
 * file name: HttpOutputStream.java
 * Date: 2018-02-17
 * Author: sandao
 */

package org.smartboot.http.server.http11;

import org.apache.commons.lang.StringUtils;
import org.smartboot.http.common.HttpEntityV2;
import org.smartboot.http.common.utils.Consts;
import org.smartboot.http.common.utils.HttpHeaderConstant;
import org.smartboot.http.server.handle.http11.ResponseHandle;
import org.smartboot.socket.transport.AioSession;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * @author 三刀
 * @version V1.0 , 2018/2/3
 */
final class HttpOutputStream extends OutputStream {

    boolean chunkedEnd = false;
    private AioSession aioSession;
    private DefaultHttpResponse response;
    private ByteBuffer cacheBuffer = ByteBuffer.allocate(512);
    private boolean committed = false, closed = false;
    private boolean chunked = false;
    private HttpEntityV2 request;
    private byte[] endChunked = new byte[]{'0', Consts.CR, Consts.LF, Consts.CR, Consts.LF};
    private ResponseHandle responseHandle;

    public HttpOutputStream(AioSession aioSession, DefaultHttpResponse response, HttpEntityV2 request, ResponseHandle responseHandle) {
        this.aioSession = aioSession;
        this.response = response;
        this.request = request;
        this.responseHandle = responseHandle;
    }

    @Override
    public void write(int b) throws IOException {
        if (!committed) {
            writeHead();
            committed = true;
        }
        if (!cacheBuffer.hasRemaining()) {
            flush();
        }
        cacheBuffer.put((byte) b);
        if (!cacheBuffer.hasRemaining()) {
            flush();
        }
    }

    public void write(ByteBuffer buffer) throws IOException {
        if (!committed) {
            writeHead();
            committed = true;
        }
        if (!cacheBuffer.hasRemaining()) {
            flush();
        }
        if (cacheBuffer.remaining() >= buffer.remaining()) {
            cacheBuffer.put(buffer);
        } else {
            while (cacheBuffer.hasRemaining()) {
                cacheBuffer.put(buffer.get());
            }
            write(buffer);
        }

        if (!cacheBuffer.hasRemaining()) {
            flush();
        }
    }

    private void writeHead() throws IOException {
        responseHandle.doHandle(request, new NoneOutputHttpResponseWrap(response));//防止在handle中调用outputStream操作
        chunked = StringUtils.equals(HttpHeaderConstant.Values.CHUNKED, response.getHeader(HttpHeaderConstant.Names.TRANSFER_ENCODING));

        cacheBuffer.put(getBytes(request.getProtocol()))
                .put(Consts.SP)
                .put(getBytes(String.valueOf(response.getHttpStatus().value())))
                .put(Consts.SP)
                .put(getBytes(response.getHttpStatus().getReasonPhrase()))
                .put(Consts.CRLF);


        for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
            byte[] headKey = getBytes(entry.getKey());
            byte[] headVal = getBytes(entry.getValue());

            int needLength = headKey.length + headVal.length + 3;
            if (cacheBuffer.remaining() < needLength) {
                cacheBuffer.flip();
                aioSession.write(cacheBuffer);
                cacheBuffer = ByteBuffer.allocate(512);
            }
            cacheBuffer.put(headKey)
                    .put(Consts.COLON)
                    .put(headVal)
                    .put(Consts.CRLF);
        }
        if (cacheBuffer.remaining() >= 2) {
            cacheBuffer.put(Consts.CRLF);
        } else {
            cacheBuffer.flip();
            aioSession.write(cacheBuffer);
            cacheBuffer = ByteBuffer.allocate(512);
            cacheBuffer.put(Consts.CRLF);
//            aioSession.write(ByteBuffer.wrap(new byte[]{Consts.CR, Consts.LF}));
        }
    }

    @Override
    public void flush() throws IOException {
        if (!committed) {
            writeHead();
            committed = true;
        }
        cacheBuffer.flip();
        if (cacheBuffer.hasRemaining()) {
            ByteBuffer buffer = null;
            if (chunked) {
                byte[] start = getBytes(Integer.toHexString(cacheBuffer.remaining()) + "\r\n");
                buffer = ByteBuffer.allocate(start.length + cacheBuffer.remaining() + Consts.CRLF.length + (chunkedEnd ? endChunked.length : 0));
                buffer.put(start).put(cacheBuffer).put(Consts.CRLF);
                if (chunkedEnd) {
                    buffer.put(endChunked);
                }

            } else {
                buffer = ByteBuffer.allocate(cacheBuffer.remaining());
                buffer.put(cacheBuffer);
            }
            buffer.flip();
            aioSession.write(buffer);
        } else if (chunked && chunkedEnd) {
            aioSession.write(ByteBuffer.wrap(endChunked));
        }
        cacheBuffer.clear();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        chunkedEnd = true;
        flush();
        closed = true;
    }

    private byte[] getBytes(String str) {
//        return str.getBytes(Consts.DEFAULT_CHARSET);
        return str.getBytes();
    }

}
