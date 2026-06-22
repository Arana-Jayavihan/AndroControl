package com.aranaj.androcontrol;

import java.util.Arrays;
import java.util.UUID;

public class Server {
    private String id;
    private String name;
    private String ipAddress;
    private int port;
    private boolean isConnected;
    private transient char[] authToken; // Not serialized, stored separately in SecureStorage

    public Server(String name, String ipAddress, int port) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.ipAddress = ipAddress;
        this.port = port;
        this.isConnected = false;
        this.authToken = null;
    }

    public Server(String name, String ipAddress, int port, String authToken) {
        this(name, ipAddress, port);
        setAuthToken(authToken);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public boolean isConnected() { return isConnected; }
    public void setConnected(boolean connected) { isConnected = connected; }
    public String getId() { return id; }

    /**
     * Gets a copy of the auth token as char[].
     * Caller is responsible for clearing with SecureStorage.clearCharArray().
     */
    public char[] getAuthTokenChars() {
        if (authToken == null) return null;
        return Arrays.copyOf(authToken, authToken.length);
    }

    /**
     * Sets the auth token from a String.
     * The internal copy will be stored as char[].
     */
    public void setAuthToken(String authToken) {
        clearAuthToken();
        if (authToken != null && !authToken.isEmpty()) {
            this.authToken = authToken.toCharArray();
        }
    }

    /**
     * Sets the auth token from a char[].
     * Makes an internal copy; caller should clear their copy.
     */
    public void setAuthTokenChars(char[] authToken) {
        clearAuthToken();
        if (authToken != null && authToken.length > 0) {
            this.authToken = Arrays.copyOf(authToken, authToken.length);
        }
    }

    /**
     * Securely clears the auth token from memory.
     */
    public void clearAuthToken() {
        if (authToken != null) {
            Arrays.fill(authToken, '\0');
            authToken = null;
        }
    }
}
