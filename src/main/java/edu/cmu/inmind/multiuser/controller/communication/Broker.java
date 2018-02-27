package edu.cmu.inmind.multiuser.controller.communication;

/**
 * Created by oscarr on 3/28/17.
 */

import edu.cmu.inmind.multiuser.controller.common.Utils;
import edu.cmu.inmind.multiuser.controller.resources.ResourceLocator;
import org.zeromq.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *  Majordomo Protocol broker
 *  A minimal implementation of http://rfc.zeromq.org/spec:7 and spec:8
 */
public class Broker implements Utils.NamedRunnable{

    // We'd normally pull these from config data
    private static final String INTERNAL_SERVICE_PREFIX = "mmi.";
    private static final int HEARTBEAT_LIVENESS = 5; // 3-5 is reasonable
    private static final int HEARTBEAT_INTERVAL = 2500; // msecs
    private static final int HEARTBEAT_EXPIRY = HEARTBEAT_INTERVAL * HEARTBEAT_LIVENESS;
    private AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private ConcurrentHashMap<Service, Boolean> statusResponseMsgs = new ConcurrentHashMap<>();
    private int port;
    private ZMQ.Poller items;


    // ---------------------------------------------------------------------

    /**
     * This defines a single service.
     */
    private static class Service {
        public final String name; // Service name
        Deque<ZMsg> requests; // List of client requests
        Deque<Worker> waiting; // List of waiting workers

        public Service(String name) {
            this.name = name;
            this.requests = new ArrayDeque<>();
            this.waiting = new ArrayDeque<>();
        }

        @Override
        public String toString() {
            return "service " + name;
        }
    }

    /**
     * This defines one worker, idle or active (orchestrator).
     */
    private static class Worker{
        String identity;// Identity of worker
        ZFrame address;// Address frame to route to
        Service service; // Owning service, if known
        long expiry;// Expires at unless heartbeat

        public Worker(String identity, ZFrame address) {
            this.address = address;
            this.identity = identity;
            this.expiry = System.currentTimeMillis() + HEARTBEAT_INTERVAL * HEARTBEAT_LIVENESS;
        }
        @Override public String toString() {
            return "worker " + identity + " for service " + (service == null? service : service.toString())
                    + " with address " + address + " and expiration " + expiry;
        }
    }

    // ---------------------------------------------------------------------

    private ZContext ctx;// Our context
    private ZMQ.Socket socket; // Socket for clients & workers

    private long heartbeatAt;// When to send HEARTBEAT
    private ConcurrentHashMap<String, Service> services;// known services
    private ConcurrentHashMap<String, Worker> workers;// known workers
    private Deque<Worker> waiting;// idle workers

    // ---------------------------------------------------------------------


    /**
     * Initialize broker state.
     */
    public Broker(int port) {
        this.services = new ConcurrentHashMap<>();
        this.workers = new ConcurrentHashMap<>();
        this.waiting = new ArrayDeque<>();
        this.heartbeatAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL;
        this.ctx = ResourceLocator.getContext();
        this.socket = ResourceLocator.createSocket(ctx, ZMQ.ROUTER);
        this.port = port;
        this.items = ctx.createPoller(1);
        this.items.register(socket, ZMQ.Poller.POLLIN);
    }

    @Override
    public String getName(){
        return "broker-" + port;
    }

    // ---------------------------------------------------------------------
    @Override
    public void run() {
        try {
            bind("tcp://*:" + port);
            mediate();
        }catch (Throwable e){
            e.printStackTrace();
        }
    }

    /**
     * Main broker work happens here
     */
    public void mediate(){
        while (!isDestroyed.get()) {
            try {
                if (items.poll(HEARTBEAT_INTERVAL) == -1)
                    break; // Interrupted
                if (items.pollin(0)) {
                    ZMsg msg = ZMsg.recvMsg(socket);
                    if (msg == null) {
                        break; // Interrupted
                    }
                    ZFrame sender = msg.pop();
                    ZFrame empty = msg.pop();
                    ZFrame header = msg.pop();
                    if (sender != null && empty != null && header != null) {
                        if (MDP.C_CLIENT.frameEquals(header)) {
                            processClient(sender, msg);
                        } else if (MDP.S_ORCHESTRATOR.frameEquals(header)) {
                            processWorker(sender, msg);
                        } else {
                            msg.destroy();
                        }
                        sender.destroy();
                        empty.destroy();
                        header.destroy();
                    }
                }
                purgeWorkers();
                sendHeartbeats();
            }catch (Throwable e){
                try {
                    if( Utils.isZMQException(e) ) {
                        destroyInCascade(); // interrupted
                    }else{
                        e.printStackTrace();
                    }
                }catch (Throwable t){
                }
            }
        }
    }

    /**
     * Disconnect all workers, destroy context.
     */
    public void close() throws Throwable{
        destroyInCascade();
    }

    public void destroyInCascade() throws Throwable {
        if ( !isDestroyed.get() ) {
            ArrayList<Worker> wrkrs = new ArrayList(workers.values());
            wrkrs.forEach(worker -> {
                try {
                    deleteWorker(worker, true);
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
            });
            ctx = null;
            isDestroyed.getAndSet(true);
        }
    }

    /**
     * Process a request coming from a client.
     */
    private void processClient(ZFrame sender, ZMsg msg) throws Throwable{
        ZFrame serviceFrame = msg.pop();
        // Set reply return address to client sender
        msg.wrap(sender.duplicate());
        if (serviceFrame.toString().startsWith(INTERNAL_SERVICE_PREFIX))
            serviceInternal(serviceFrame, msg);
        else {
            // if orchestrator hasn't sent a response back but receives a new message, then
            // it has to inform that is ready to receive new messages
            Service service = requireService(serviceFrame);
            Worker worker = reactivateWorker(service, msg == null? null : msg.peekFirst() );
            if( worker != null){
                workerWaiting(worker, msg);
            }else{
                dispatch(service, msg);
            }
        }
        serviceFrame.destroy();
    }

    private Worker reactivateWorker(Service service, ZFrame sender) throws Throwable{
        // if broker hasn't received a message back from orchestrator, then reactivate
        // the worker automatically
        Boolean status = statusResponseMsgs.get( service );
        if( status != null && !status ) {
            Worker worker = service.waiting.isEmpty()?
                    (sender == null? null : requireWorker(sender))
                    : service.waiting.peek();
            if( worker != null ) {
                if( worker.service == null )
                    worker.service = service;
                return worker;
            }
        }
        return null;
    }

    /**
     * Process message sent to us by a worker.
     */
    private void processWorker(ZFrame sender, ZMsg msg) throws Throwable {
        ZFrame command = msg.pop();
        boolean workerReady = workers.containsKey(sender.strhex());
        Worker worker = requireWorker(sender);
        if (MDP.S_READY.frameEquals(command)) {
            // Not first command in session || Reserved service name
            if (workerReady
                    || sender.toString().startsWith(INTERNAL_SERVICE_PREFIX)) {
                deleteWorker(worker, true);
            } else {
                // Attach worker to service and mark as idle
                ZFrame serviceFrame = msg.pop();
                worker.service = requireService(serviceFrame);
                workerWaiting(worker);
                serviceFrame.destroy();
            }
        } else if (MDP.S_REPLY.frameEquals(command)) {
            if (workerReady) {
                // Remove & save client return envelope and insert the
                // protocol header and service name, then rewrap envelope.
                ZFrame client = msg.unwrap();
                if( !MDP.S_READY.frameEquals(msg.peekLast(), "\"")) {
                    msg.addFirst(worker.service.name);
                    msg.addFirst(MDP.C_CLIENT.newFrame());
                    msg.wrap(client);
                    msg.send(socket);
                }
                statusResponseMsgs.put( worker.service, true );
                workerWaiting(worker);
            } else {
                deleteWorker(worker, true);
            }
        } else if (MDP.S_HEARTBEAT.frameEquals(command)) {
            if (workerReady) {
                renewExpiration(worker);
                workerWaiting(worker);
            } else {
                deleteWorker(worker, true);
            }
        } else if (MDP.S_DISCONNECT.frameEquals(command)){
            deleteWorker(worker, false);
        }else {
            System.out.println("invalid message: " + command.toString());
        }
        msg.destroy();
    }

    private void renewExpiration(Worker worker){
        worker.expiry = System.currentTimeMillis() + HEARTBEAT_EXPIRY;
    }

    /**
     * Deletes worker from all data structures, and destroys worker.
     */
    private void deleteWorker(Worker worker, boolean disconnect) throws Throwable{
        if (disconnect) {
            sendToWorker(worker, MDP.S_DISCONNECT, null, null);
        }
        if (worker.service != null)
            worker.service.waiting.remove(worker);
        workers.remove(worker.identity);
        worker.address.destroy();
    }

    /**
     * Finds the worker (creates if necessary).
     */
    private Worker requireWorker(ZFrame address) throws Throwable{
        String identity = address.strhex();
        Worker worker = workers.get(identity);
        if (worker == null) {
            worker = new Worker(identity, address.duplicate());
            workers.put(identity, worker);
        }
        return worker;
    }

    /**
     * Locates the service (creates if necessary).
     */
    private Service requireService(ZFrame serviceFrame) throws Throwable{
        String name = serviceFrame.toString();
        Service service = services.get(name);
        if (service == null) {
            service = new Service(name);
            services.put(name, service);
        }
        return service;
    }

    /**
     * Bind broker to endpoint, can call this multiple times. We use a single
     * socket for both clients and workers.
     */
    public void bind(String endpoint) throws Throwable{
        socket.bind(endpoint);
    }

    /**
     * Handle internal service according to 8/MMI specification
     */
    private void serviceInternal(ZFrame serviceFrame, ZMsg msg) throws Throwable{
        String returnCode = "501";
        if ("mmi.service".equals(serviceFrame.toString())) {
            String name = msg.peekLast().toString();
            returnCode = services.containsKey(name) ? "200" : "400";
        }
        msg.peekLast().reset(returnCode.getBytes());
        // Remove & save client return envelope and insert the
        // protocol header and service name, then rewrap envelope.
        ZFrame client = msg.unwrap();
        msg.addFirst(serviceFrame.duplicate());
        msg.addFirst(MDP.C_CLIENT.newFrame());
        msg.wrap(client);
        msg.send(socket);
    }

    /**
     * Send heartbeats to idle workers if it's time
     */
    public synchronized void sendHeartbeats() throws Throwable{
        // Send heartbeats to idle workers if it's time
        if (System.currentTimeMillis() >= heartbeatAt) {
            for (Worker worker : waiting) {
                sendToWorker(worker, MDP.S_HEARTBEAT, null, null);
            }
            heartbeatAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL;
        }
    }

    /**
     * Look for & kill expired workers. Workers are oldest to most recent, so we
     * stop at the first alive worker.
     */
    public synchronized void purgeWorkers() throws Throwable{
        Iterator<Worker> iterator = waiting.iterator();
        while(iterator.hasNext()){
            Worker w = iterator.next();
            if (w.expiry < System.currentTimeMillis()){
                iterator.remove();
                deleteWorker(w, false);
            }
        }
    }

    /**
     * This worker is now waiting for work.
     */
    public synchronized void workerWaiting(Worker worker, ZMsg msg) throws Throwable{
        // Queue to broker and service waiting lists
        waiting.addLast(worker);
        worker.service.waiting.addLast(worker);
        renewExpiration(worker);
        dispatch(worker.service, msg);
    }

    public synchronized void workerWaiting(Worker worker) throws Throwable{
        workerWaiting( worker, null );
    }

    /**
     * Dispatch requests to waiting workers as possible
     */
    private void dispatch(Service service, ZMsg msg) throws Throwable{
        if (msg != null)// Queue message if any
            service.requests.offerLast(msg);
        purgeWorkers();
        while (!service.waiting.isEmpty() && !service.requests.isEmpty()) {
            msg = service.requests.pop();
            Worker worker = service.waiting.pop();
            waiting.remove(worker);
            statusResponseMsgs.put( service, false );
            sendToWorker(worker, MDP.S_REQUEST, null, msg);
            msg.destroy();
        }
    }

    /**
     * Send message to worker. If message is provided, sends that message. Does
     * not destroy the message, this is the caller's job.
     */
    public void sendToWorker(Worker worker, MDP command, String option,
                             ZMsg msgp) throws Throwable{
        if( !Thread.currentThread().isInterrupted() && Thread.currentThread().isAlive() ) {
            ZMsg msg = msgp == null ? new ZMsg() : msgp.duplicate();
            // Stack protocol envelope to start of message
            if (option != null)
                msg.addFirst(new ZFrame(option));
            msg.addFirst(command.newFrame());
            msg.addFirst(MDP.S_ORCHESTRATOR.newFrame());
            // Stack routing envelope to start of message
            msg.wrap(worker.address.duplicate());
            msg.send(socket);
        }
    }
}
