package com.aranaj.androcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ServerManager {
    private static final String TAG = "ServerManager";
    private static final String PREFS_NAME = "ServerPrefs";
    private static final String SERVERS_KEY = "servers";
    private static final String SERVERS_KEY_ENCRYPTED = "servers_encrypted";
    private static final String LAST_CONNECTED_SERVER_KEY = "last_connected_server";
    private static final String MIGRATION_VERSION_KEY = "server_manager_version";
    private static final int CURRENT_VERSION = 2; // Version 2 = encrypted storage

    private final SharedPreferences prefs;
    private final SecureStorage secureStorage;
    private final Gson gson;
    private List<Server> servers;

    public ServerManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        secureStorage = new SecureStorage(context);
        gson = new Gson();
        migrateIfNeeded();
        loadServers();
    }

    /**
     * Migrates server data from plain to encrypted storage if needed.
     */
    private void migrateIfNeeded() {
        int version = prefs.getInt(MIGRATION_VERSION_KEY, 1);
        if (version < CURRENT_VERSION) {
            Log.i(TAG, "Migrating server list to encrypted storage (v" + version + " -> v" + CURRENT_VERSION + ")");
            migrateToEncryptedStorage();
            prefs.edit().putInt(MIGRATION_VERSION_KEY, CURRENT_VERSION).apply();
        }
    }

    /**
     * Migrates existing plain text server list to encrypted storage.
     */
    private void migrateToEncryptedStorage() {
        // Try to load from legacy plain storage
        String legacyJson = prefs.getString(SERVERS_KEY, null);
        if (legacyJson != null && !legacyJson.equals("[]")) {
            // Save to encrypted storage
            secureStorage.saveEncrypted(SERVERS_KEY_ENCRYPTED, legacyJson);
            // Remove legacy plain storage
            prefs.edit().remove(SERVERS_KEY).apply();
            Log.i(TAG, "Successfully migrated server list to encrypted storage");
        }
    }

    private void loadServers() {
        // Try to load from encrypted storage first
        String serversJson = secureStorage.getEncrypted(SERVERS_KEY_ENCRYPTED);

        // Fallback to legacy plain storage for migration
        if (serversJson == null) {
            serversJson = prefs.getString(SERVERS_KEY, "[]");
        }

        Type type = new TypeToken<ArrayList<Server>>(){}.getType();
        try {
            servers = gson.fromJson(serversJson, type);
            if (servers == null) {
                servers = new ArrayList<>();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse server list", e);
            servers = new ArrayList<>();
        }
    }

    public List<Server> getServers() {
        return servers;
    }

    /**
     * Adds a server if no duplicate exists.
     * @return true if added, false if duplicate exists
     */
    public boolean addServer(Server server) {
        if (isDuplicate(server)) {
            return false;
        }
        servers.add(server);
        saveServers();
        return true;
    }

    /**
     * Checks if a server with the same IP and port already exists.
     */
    public boolean isDuplicate(Server server) {
        for (Server existing : servers) {
            if (existing.getIpAddress().equals(server.getIpAddress())
                    && existing.getPort() == server.getPort()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds existing server by IP and port.
     */
    public Server findByAddress(String ip, int port) {
        for (Server server : servers) {
            if (server.getIpAddress().equals(ip) && server.getPort() == port) {
                return server;
            }
        }
        return null;
    }

    public void removeServer(int position) {
        servers.remove(position);
        saveServers();
    }

    public void updateServer(int position, Server server) {
        servers.set(position, server);
        saveServers();
    }

    private void saveServers() {
        String serversJson = gson.toJson(servers);
        secureStorage.saveEncrypted(SERVERS_KEY_ENCRYPTED, serversJson);
    }
    public void setLastConnectedServer(String serverId) {
        prefs.edit().putString(LAST_CONNECTED_SERVER_KEY, serverId).apply();
    }

    public Server getLastConnectedServer() {
        String lastConnectedId = prefs.getString(LAST_CONNECTED_SERVER_KEY, null);
        if (lastConnectedId != null) {
            for (Server server : servers) {
                if (lastConnectedId.equals(server.getId())) {
                    return server;
                }
            }
        }
        return null;
    }

    public void clearLastConnectedServer() {
        prefs.edit().remove(LAST_CONNECTED_SERVER_KEY).apply();
    }
}