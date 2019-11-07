package org.smartboot.http.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartboot.http.HttpRequest;
import org.smartboot.http.enums.MethodEnum;
import org.smartboot.http.enums.State;
import org.smartboot.http.utils.Consts;
import org.smartboot.http.utils.EmptyInputStream;
import org.smartboot.http.utils.HttpHeaderConstant;
import org.smartboot.http.utils.StringUtils;
import org.smartboot.socket.extension.decoder.DelimiterFrameDecoder;
import org.smartboot.socket.extension.decoder.SmartDecoder;
import org.smartboot.socket.transport.AioSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * @author 三刀
 * @version V1.0 , 2018/8/31
 */
public final class Http11Request implements HttpRequest {
    private static final Logger LOGGER = LoggerFactory.getLogger(Http11Request.class);
    State _state = State.method;
    MethodEnum _methodEnum;
    /**
     * 原始的完整请求
     */
    String _originalUri;
    String _protocol;
    Map<String, String> _headers = new HashMap<>();
    String tmpHeaderName;

    SmartDecoder bodyContentDecoder;
    /**
     * Header Value解码器是否启用
     */
    boolean headValueDecoderEnable = false;
    /**
     * 消息头Value值解码器
     */
    private DelimiterFrameDecoder headerValueDecoder = null;
    /**
     * 请求参数
     */
    private Map<String, String[]> parameters;
    private InputStream inputStream;
    private String requestUri;
    private String contentType;
    /**
     * 跟在URL后面的请求信息
     */
    private String queryString;
    private String scheme;
    private int contentLength = -1;
    private AioSession<Http11Request> aioSession;

    private String remoteAddr;

    private String remoteHost;

    Http11Request(AioSession<Http11Request> aioSession) {
        this.aioSession = aioSession;
    }

    @Override
    public String getHeader(String headName) {
        return _headers.get(headName);
    }

    @Override
    public Map<String, String> getHeaders() {
        return _headers;
    }

    @Override
    public InputStream getInputStream() {
        return this.inputStream == null ? new EmptyInputStream() : this.inputStream;
    }

    void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public String getRequestURI() {
        return requestUri;
    }

    public void setRequestURI(String uri) {
        this.requestUri = uri;
    }

    @Override
    public String getProtocol() {
        return _protocol;
    }

    @Override
    public MethodEnum getMethodRange() {
        return _methodEnum;
    }

    @Override
    public String getRequestURL() {
        return _originalUri;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getQueryString() {
        return queryString;
    }

    void setQueryString(String queryString) {
        this.queryString = queryString;
    }

    @Override
    public String getContentType() {
        return contentType == null ? contentType = _headers.get(HttpHeaderConstant.Names.CONTENT_TYPE) : contentType;
    }

    @Override
    public int getContentLength() {
        return contentLength;
    }

    void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }

    @Override
    public String getParameter(String name) {
        String[] arr = (name != null ? getParameterValues(name) : null);
        return (arr != null && arr.length > 0 ? arr[0] : null);
    }

    @Override
    public String[] getParameterValues(String name) {
        if (parameters != null) {
            return parameters.get(name);
        }
        parameters = new HashMap<>();
        //识别url中的参数
        String urlParamStr = queryString;
        if (StringUtils.isNotBlank(urlParamStr)) {
            urlParamStr = StringUtils.substringBefore(urlParamStr, "#");
            decodeParamString(urlParamStr, parameters);
        }

        //识别body中的参数
        if (bodyContentDecoder == null) {
            return getParameterValues(name);
        }
        ByteBuffer buffer = bodyContentDecoder.getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        decodeParamString(new String(bytes), parameters);
        return getParameterValues(name);
    }

    @Override
    public Map<String, String[]> getParameters() {
        if (parameters == null) {
            getParameter("");
        }
        return parameters;
    }

    /**
     * Returns the Internet Protocol (IP) address of the client
     * or last proxy that sent the request.
     * For HTTP servlets, same as the value of the
     * CGI variable <code>REMOTE_ADDR</code>.
     *
     * @return a <code>String</code> containing the
     * IP address of the client that sent the request
     */
    @Override
    public String getRemoteAddr() {
        if (remoteAddr != null) {
            return remoteAddr;
        }
        try {
            InetSocketAddress remote = aioSession.getRemoteAddress();
            InetAddress address = remote.getAddress();
            if (address == null) {
                remoteAddr = remote.getHostString();
            } else {
                remoteAddr = address.getHostAddress();
            }
        } catch (IOException e) {
            LOGGER.error("", e);
        }
        return remoteAddr;
    }

    /**
     * Returns the fully qualified name of the client
     * or the last proxy that sent the request.
     * If the engine cannot or chooses not to resolve the hostname
     * (to improve performance), this method returns the dotted-string form of
     * the IP address. For HTTP servlets, same as the value of the CGI variable
     * <code>REMOTE_HOST</code>.
     *
     * @return a <code>String</code> containing the fully
     * qualified name of the client
     */
    @Override
    public String getRemoteHost() {
        if (remoteHost != null) {
            return remoteHost;
        }
        try {
            remoteHost = aioSession.getRemoteAddress().getHostString();
        } catch (IOException e) {
            LOGGER.error("", e);
        }
        return remoteHost;
    }

    private void decodeParamString(String paramStr, Map<String, String[]> paramMap) {
        if (StringUtils.isBlank(paramStr)) {
            return;
        }
        String[] uriParamStrArray = StringUtils.split(paramStr, "&");
        for (String param : uriParamStrArray) {
            int index = param.indexOf("=");
            if (index == -1) {
                continue;
            }
            try {
                String key = StringUtils.substring(param, 0, index);
                String value = URLDecoder.decode(StringUtils.substring(param, index + 1), "utf8");
                String[] values = paramMap.get(key);
                if (values == null) {
                    paramMap.put(key, new String[]{value});
                } else {
                    String[] newValue = new String[values.length + 1];
                    System.arraycopy(values, 0, newValue, 0, values.length);
                    newValue[values.length] = value;
                    paramMap.put(key, newValue);
                }

            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 获取解码器
     *
     * @return
     */
    public DelimiterFrameDecoder getHeaderValueDecoder() {
        if (headerValueDecoder == null) {
            headerValueDecoder = new DelimiterFrameDecoder(new byte[]{Consts.CR}, 1024);
        }
        return headerValueDecoder;
    }

    public void rest() {
        _state = State.method;
        _headers.clear();
        tmpHeaderName = null;
        headValueDecoderEnable = false;
        if (headerValueDecoder != null) {
            headerValueDecoder.reset();
        }
        bodyContentDecoder = null;
        _originalUri = null;
        parameters = null;
        contentType = null;
        contentLength = -1;
    }

//    @Override
//    public String toString() {
//        return ToStringBuilder.reflectionToString(this);
//    }
}