package edu.cmu.inmind.multiuser.controller.communication;

/**
 * Created by oscarr on 3/28/17.
 */

import org.zeromq.ZFrame;
import org.zeromq.ZMQ;

import java.util.Arrays;

/**
 * Majordomo Protocol definitions, Java version
 */
public enum MDP {

    /**
     * This is the version of MDP/Client we implement
     */
    C_CLIENT("MDPC01"),

    /**
     * This is the version of MDP/Service we implement
     */
    S_ORCHESTRATOR("MDPW01"),

    // MDP/MainServer commands, as byte values
    S_READY(1),
    S_REQUEST(2),
    S_REPLY(3),
    S_HEARTBEAT(4),
    S_DISCONNECT(5);

    private final byte[] data;

    MDP(String value) {
        this.data = value.getBytes(ZMQ.CHARSET);
    }
    MDP(int value) { //watch for ints>255, will be truncated
        byte b = (byte) (value & 0xFF);
        this.data = new byte[] { b };
    }

    @Override public String toString() {
        return new String(this.data, ZMQ.CHARSET);
    }

    public ZFrame newFrame () {
        return new ZFrame(data);
    }

    public boolean frameEquals (ZFrame frame) {
        return Arrays.equals(data, frame.getData());
    }

    public boolean frameEquals (ZFrame frame, String prefix) {
        return frame.toString().equals(prefix + name() + prefix);
    }
}