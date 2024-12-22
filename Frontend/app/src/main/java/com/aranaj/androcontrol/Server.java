package com.aranaj.androcontrol;
import java.util.UUID;

public class Server {
    private String id;
    private String name;
    private String ipAddress;
    private int port;
    private boolean isConnected;

    public Server(String name, String ipAddress, int port) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.ipAddress = ipAddress;
        this.port = port;
        this.isConnected = false;
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
}
