package edu.cmu.inmind.multiuser.controller;

import com.google.common.util.concurrent.ServiceManager;
import edu.cmu.inmind.multiuser.controller.common.Constants;
import edu.cmu.inmind.multiuser.controller.common.Utils;
import edu.cmu.inmind.multiuser.controller.communication.*;
import edu.cmu.inmind.multiuser.controller.nlp.IntentionParsing;
import edu.cmu.inmind.multiuser.controller.resources.ResourceLocator;
import org.zeromq.ZMsg;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by oscarr on 3/3/17.
 * This class will control the sessions lifecycle (connect, disconnect, pause, resume)
 */
public class MainServer implements Utils.NamedRunnable{
    /** sessions handled by the session manager */
    /** communication controller that process
     * lifecycle request messages (connect a client, disconnect, etc.)*/
    private ServerCommController serverCommController;
    /** message that is used to reply to clients */
    private ZMsg reply;
    /** this is the id of the session manager. we use it to filter messages that must be
     * processed by the session manager
     */
    private String serviceId = Constants.SESSION_MANAGER_SERVICE;
    private Broker[] brokers;
    private Broker managerBroker;
    private AtomicLong portIncrease = new AtomicLong(0);

    private int numOfPorts;
    private int sessionMngPort;
    private String address;
    private String fullAddress;
    private AtomicBoolean stopped = new AtomicBoolean(false);
    private AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private IntentionParsing intentionParsing;
    private boolean verbose;


    public MainServer() throws Throwable{
        verbose = Boolean.valueOf(Utils.getProperty("verbose"));
        intentionParsing = IntentionParsing.getInstance();
        extractConfig();
        initializeBrokers();
        serverCommController = new ServerCommController(fullAddress, serviceId, null);
    }


    @Override
    public String getName(){
        return Constants.SESSION_MANAGER_SERVICE;
    }

    /**
     * settings information that belongs to the session manager
     */
    private void extractConfig(){
        sessionMngPort = Integer.parseInt(Utils.getProperty("server.port"));
        address = "tcp://*";
        fullAddress = (address.startsWith("tcp:") ? address : "tcp://" + address)
                + (address.lastIndexOf(":") == address.length() - 1 ? sessionMngPort : ":" + sessionMngPort);
        // ...
    }

    /**
     * ZMQ docs: It can be extended to run multiple threads, each managing one socket and one set of clients and
     * workers. This could be interesting for segmenting large architectures. The C code is already organized around
     * a broker class to make this trivial.
     */
    public void initializeBrokers(){
        numOfPorts = 1;
        //if numOfPorts is <= 1, use always managerBroker
        if( numOfPorts > 1 ) {
            brokers = new Broker[numOfPorts];
            for (int i = 0; i < numOfPorts; i++) {
                // Can be called multiple times with different endpoints
                brokers[i] = new Broker(sessionMngPort + (i + 1));
                Utils.execute(brokers[i]);
            }
        }
        managerBroker = new Broker(sessionMngPort);
        Utils.execute( managerBroker );
    }

    /**
     * It waits for new clients that want to connect to MUF. Once a request is received, the system creates an
     * instance of ServerCommController which will start receiving/sending results to the client.
     */
    public void run(){
        try {
            reply = null;
            while (!isDestroyed.get() && !stopped.get() ) {
                processRequest( );
            }
        }catch (Throwable e){
            e.printStackTrace();
        }finally{
            boolean done = false;
            try {
                while (!done) {
                    done = true;
                    for (ServiceManager serviceManager : ResourceLocator.getServiceManagers().keySet()) {
                        // if the sever manager has stopped, we are done!
                        if (!ResourceLocator.getServiceManagers().get(serviceManager)
                                .equals(Constants.SERVICE_MANAGER_STOPPED)) {
                            done = false;
                            break;
                        }
                    }
                }
                System.exit(0);
            }catch (Throwable e){
                e.printStackTrace();
            }
        }
    }


    /**
     * It processes requests from clients related to the session lifecycle: connect, disconnect, pause and resume;
     * and also requests from remote services.
     */
    private void processRequest( ) throws Throwable{
        if( !stopped.get() ) {
            ZMsgWrapper msgRequest;
            msgRequest = serverCommController.receive(reply);
            if( msgRequest != null ) {
                SessionMessage request = getServerRequest(msgRequest);
                if(request.getRequestType().equals(Constants.REQUEST_CONNECT)){
                    SessionMessage sm = new SessionMessage(Constants.SESSION_INITIATED);
                    sm.setPayload("NO_SESSION");
                    send(msgRequest, sm);
                }else if(request.getMessageId().equals(Constants.MSG_REQ_PREF_EXTRACTION)
                        || request.getRequestType().equals(Constants.MSG_REQ_PREF_EXTRACTION)){
                    if (request.getPayload() != null) {
                        String response = intentionParsing.extractPreference(request.getPayload());
                        if (verbose) System.out.println("intention: " + response);
                        send(msgRequest, new SessionMessage(Constants.MSG_REP_PREF_EXTRACTION, response));
                    }
                }else if(request.getRequestType().equals(Constants.MSG_REQ_CLAUSE_BREAKING)
                        || request.getMessageId().equals(Constants.MSG_REQ_CLAUSE_BREAKING)){
                    if (request.getPayload() != null) {
                        Sentence sentence = Utils.fromJson(request.getPayload(), Sentence.class);
                        if(verbose) System.out.println("Receiving sentence: " + sentence.getSentence());
                        List<String> clauses = intentionParsing.clauseBreakSent(sentence.getSentence());
                        if(verbose) System.out.println("clauses: " + Utils.toJson(clauses));
                        send(msgRequest, new SessionMessage(Constants.MSG_REP_CLAUSE_BREAKING, Utils.toJson(clauses)));
                    }
                }
            }
        }
    }

    /**
     * extracts the message (which comes as byte array format) and parses it to an instance of SessionMessage
     * @param msgRequest
     * @return
     */
    private SessionMessage getServerRequest(ZMsgWrapper msgRequest) throws Throwable{
        if( msgRequest != null && msgRequest.getMsg().peekLast() != null ) {
            return Utils.fromJson(msgRequest.getMsg().peekLast().toString(), SessionMessage.class);
        }
        return new SessionMessage();
    }


    private void send(ZMsgWrapper msgRequest, SessionMessage request) throws Throwable{
        if( serverCommController != null ){
            serverCommController.send( msgRequest, request );
        }
    }


    /**
     * MUF runs on its own separate thread
     */
    public void start() throws Throwable{
        Utils.execute(this);
    }


    /**
     * It disconnects all sessions, closes all sockets and stop the multiuser framework.
     */
    public void close() throws Throwable{
        stopped.getAndSet(true);
        SessionMessage sessionMessage = new SessionMessage();
        sessionMessage.setRequestType( Constants.REQUEST_SHUTDOWN_SYSTEM );
        sessionMessage.setMessageId( Constants.SESSION_MANAGER_SERVICE );
        serverCommController.close();
        if( numOfPorts > 0 && brokers != null ) {
            for (Broker broker : brokers) {
                broker.close();
            }
        }
        managerBroker.close();
    }



    public static void main(String args[]) throws Throwable{
        new MainServer().start();
    }
}
