package edu.cmu.inmind.multiuser.controller.communication;

import edu.cmu.inmind.multiuser.controller.common.Constants;

/**
 * Created by oscarr on 3/3/17.
 */
public class SessionMessage {
    private String requestType = "";
    private String sessionId = "";
    private String url = "";
    private String payload = "";
    private String messageId;

    public SessionMessage(String requestType) {
        this.requestType = requestType;
        if( requestType.equals(Constants.SESSION_CLOSED) ){
            messageId = requestType;
        }
    }

    public SessionMessage(String messageId, String payload, String sessionId) {
        this(messageId, payload);
        this.sessionId = sessionId;
    }

    public SessionMessage(String messageId, String payload) {
        this.messageId = messageId;
        this.payload = payload;
    }

    public SessionMessage() {
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
