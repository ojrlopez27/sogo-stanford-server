package edu.cmu.inmind.multiuser.controller.resources;


import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ServiceManager;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by oscarr on 3/21/17.
 */
public class ResourceLocator {
    private static ConcurrentHashMap<String, Queue> syncMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<ServiceManager, String> serviceManagers = new ConcurrentHashMap<>();
    private static Cache<String, Object> cache;
    private static ConcurrentHashMap<Integer, String[]> componentsSubscriptions = new ConcurrentHashMap<>();
    /** This ServiceManager is only used for Stateless and Pool components */
    private static ServiceManager statelessServManager;



    public static ConcurrentHashMap<ServiceManager, String> getServiceManagers() {
        return serviceManagers;
    }

    /**
     * Cache Memmory
     */
    static{
        cache = CacheBuilder.newBuilder()
                .maximumSize(1000)
                //.expireAfterWrite(10, TimeUnit.MINUTES)
                //.removalListener(MY_LISTENER)
                .build();
    }

    public static void toCache(String key, Object value){
        cache.put( key, value );
    }

    public static Object fromCache(String key){
        return cache.asMap().get(key);
    }

    public static void addComponentSubscriptions(int hashcode, String[] messages) {
        if( hashcode <=0 || messages == null || messages.length <= 0 ){
//            ExceptionHandler.handle( new MultiuserException(ErrorMessages.ANY_ELEMENT_IS_NULL, "hashcode: "
//                    + hashcode, "messages: " + messages) );
        }
        componentsSubscriptions.put( hashcode, messages );
    }

    public static String[] getComponentsSubscriptions(int hashcode) {
        return componentsSubscriptions.get(hashcode);
    }




    /** ======================== ZeroMQ Contexts =================================== **/

    /**
     * ZMQ docs: You should create and use exactly one context in your process. Technically, the context is the
     * container for all sockets in a single process, and acts as the transport for inproc sockets, which are the
     * fastest way to connect threads in one process. If at runtime a process has two contexts, these are like separate
     * ZeroMQ instances
     */
    private static ZContext context;
    private static CopyOnWriteArrayList<ZMQ.Socket> sockets = new CopyOnWriteArrayList<>();
    private static CopyOnWriteArrayList<ZContext> contexts = new CopyOnWriteArrayList<>();


    public static ZContext getContext() {
        if( context == null ){
            context = new ZContext();
        }
        // contexts keeps a record about which owner has released its context.
        // at initialization, nobody has released it.
        ZContext ctx = ZContext.shadow(context);
        contexts.add(ctx);
        return ctx;
    }

    public static ZMQ.Socket createSocket(ZContext ctx, int type){
        ZMQ.Socket socket = ctx.createSocket(type);
        sockets.add(socket);
        return socket;
    }
}
