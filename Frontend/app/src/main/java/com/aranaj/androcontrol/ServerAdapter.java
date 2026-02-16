package com.aranaj.androcontrol;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import java.util.List;

public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ServerViewHolder> {
    private List<Server> servers;
    private OnServerActionListener listener;

    public interface OnServerActionListener {
        void onConnect(int position);
        void onDisconnect(int position);
        void onEdit(int position);
        void onDelete(int position);
    }

    public ServerAdapter(List<Server> servers, OnServerActionListener listener) {
        this.servers = servers;
        this.listener = listener;
    }

    @Override
    public ServerViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.server_item, parent, false);
        return new ServerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ServerViewHolder holder, int position) {
        Server server = servers.get(position);
        holder.bind(server, position);
    }

    @Override
    public int getItemCount() {
        return servers.size();
    }

    class ServerViewHolder extends RecyclerView.ViewHolder {
        View statusIndicator;
        TextView serverName;
        TextView serverInfo;
        MaterialButton btnConnect;
        MaterialButton btnEdit;
        MaterialButton btnDelete;

        ServerViewHolder(View itemView) {
            super(itemView);
            statusIndicator = itemView.findViewById(R.id.statusIndicator);
            serverName = itemView.findViewById(R.id.serverName);
            serverInfo = itemView.findViewById(R.id.serverInfo);
            btnConnect = itemView.findViewById(R.id.btnConnect);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }

        void bind(Server server, int position) {
            serverName.setText(server.getName());
            serverInfo.setText(String.format("%s:%d", server.getIpAddress(), server.getPort()));

            // Update status indicator
            if (server.isConnected()) {
                statusIndicator.setBackgroundResource(R.drawable.status_dot);
                btnConnect.setText("Disconnect");
            } else {
                statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
                btnConnect.setText("Connect");
            }

            btnConnect.setOnClickListener(v -> {
                if (server.isConnected()) {
                    listener.onDisconnect(position);
                } else {
                    listener.onConnect(position);
                }
            });

            btnEdit.setOnClickListener(v -> listener.onEdit(position));
            btnDelete.setOnClickListener(v -> listener.onDelete(position));
        }
    }
}