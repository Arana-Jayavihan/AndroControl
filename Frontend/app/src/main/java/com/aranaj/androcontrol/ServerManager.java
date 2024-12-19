package com.aranaj.androcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ServerManager {
    private static final String PREFS_NAME = "ServerPrefs";
    private static final String SERVERS_KEY = "servers";
    private final SharedPreferences prefs;
    private final Gson gson;
    private List<Server> servers;

    public ServerManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
        loadServers();
    }

    private void loadServers() {
        String serversJson = prefs.getString(SERVERS_KEY, "[]");
        Type type = new TypeToken<ArrayList<Server>>(){}.getType();
        servers = gson.fromJson(serversJson, type);
    }

    public List<Server> getServers() {
        return servers;
    }

    public void addServer(Server server) {
        servers.add(server);
        saveServers();
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
        prefs.edit().putString(SERVERS_KEY, serversJson).apply();
    }
}