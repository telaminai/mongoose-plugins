package com.fluxtion.server.plugin.connector.multicast;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;

public interface NetworkHelper {

    /**
     * Finds and returns the NetworkInterface object for the loopback interface.
     *
     * @return The loopback NetworkInterface, or null if none is found.
     */
    static NetworkInterface getLoopbackInterface() {
        try {
            // Get an Enumeration of all network interfaces on the system
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            // The Collections.list() utility converts the legacy Enumeration to a modern List
            for (NetworkInterface nif : Collections.list(interfaces)) {

                // Crucial Check: Use the isLoopback() method
                if (nif.isLoopback()) {
                    System.out.println("Found loopback interface: " + nif.getDisplayName());
                    return nif;
                }
            }
        } catch (SocketException e) {
            System.err.println("Error accessing network interfaces: " + e.getMessage());
        }
        return null;
    }
}
