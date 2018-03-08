package edu.cmu.inmind.multiuser.controller.common;

/**
 * Created by oscarr on 3/3/17.
 */
public class Constants {
    //lifecycle
    public static final String REQUEST_CONNECT = "REQUEST_CONNECT";
    public static final String REQUEST_DISCONNECT = "REQUEST_DISCONNECT";
    public static final String REQUEST_SHUTDOWN_SYSTEM = "REQUEST_SHUTDOWN_SYSTEM";
    public static final boolean VERBOSE = true;

    //communication
    public static final String SESSION_MANAGER_SERVICE = "session-manager";
    public static final int DEFAULT_PORT = 5555;


    //session
    public static final String SESSION_INITIATED = "SESSION_INITIATED";
    public static final String SESSION_CLOSED = "SESSION_CLOSED";

    public static final String SERVICE_MANAGER_STOPPED = "SERVICE_MANAGER_STOPPED";


    public static final String MSG_REQ_PREF_EXTRACTION = "MSG_REQ_PREF_EXTRACTION";
    public static final String MSG_REQ_CLAUSE_BREAKING = "MSG_REQ_CLAUSE_BREAKING";
    public static final String MSG_REP_PREF_EXTRACTION = "MSG_REP_PREF_EXTRACTION";
    public static final String MSG_REP_CLAUSE_BREAKING = "MSG_REP_CLAUSE_BREAKING";
}