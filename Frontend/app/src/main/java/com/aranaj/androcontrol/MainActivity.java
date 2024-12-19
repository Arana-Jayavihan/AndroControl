package com.aranaj.androcontrol;

import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import android.view.MotionEvent;
import android.view.View;
import android.content.Intent;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private View touchPad;
    private Button btnLeftClick, btnMiddleClick, btnRightClick;

    private String serverIp = "";
    private int serverPort = 5050;
    private Socket socket;
    private PrintWriter out;
    private ExecutorService executorService;

    private float lastX = 0, lastY = 0;
    private static final int MOVEMENT_THRESHOLD = 5;

    private long touchStartTime;
    private static final long TAP_THRESHOLD = 200;
    private boolean hasMoved = false;

    private static final int MOVEMENT_BUFFER_MS = 7;
    private static final float MOVEMENT_SENSITIVITY = 1.5f;
    private long lastMovementTime = 0;
    private float accumulatedX = 0;
    private float accumulatedY = 0;
    private final Object movementLock = new Object();
    private boolean isScrolling = false;
    private float lastScrollY = 0;
    private static final float SCROLL_THRESHOLD = 5;
    private static final float SCROLL_SENSITIVITY = 0.5f;
    private ServerManager serverManager;
    private static final int SERVER_LIST_REQUEST_CODE = 1001;
    private ServerAdapter serverAdapter;
    private RecyclerView serverList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        touchPad = findViewById(R.id.touchPad);
        btnLeftClick = findViewById(R.id.btnLeftClick);
        btnMiddleClick = findViewById(R.id.btnMiddleClick);
        btnRightClick = findViewById(R.id.btnRightClick);

        executorService = Executors.newSingleThreadExecutor();

        serverManager = new ServerManager(this);
        serverList = findViewById(R.id.serverList);
        serverList.setLayoutManager(new LinearLayoutManager(this));

        serverAdapter = new ServerAdapter(serverManager.getServers(), new ServerAdapter.OnServerActionListener() {
            @Override
            public void onConnect(int position) {
                Server server = serverManager.getServers().get(position);
                serverIp = server.getIpAddress();
                serverPort = server.getPort();
                connectToServer();
                server.setConnected(true);
                serverAdapter.notifyItemChanged(position);
            }

            @Override
            public void onDisconnect(int position) {
                Server server = serverManager.getServers().get(position);
                disconnectFromServer();
                server.setConnected(false);
                serverAdapter.notifyItemChanged(position);
            }

            @Override
            public void onEdit(int position) {
                showEditServerDialog(position);
            }

            @Override
            public void onDelete(int position) {
                serverManager.removeServer(position);
                serverAdapter.notifyItemRemoved(position);
            }
        });

        serverList.setAdapter(serverAdapter);

        findViewById(R.id.btnAddServer).setOnClickListener(v -> showAddServerDialog());

        touchPad.setOnTouchListener((v, event) -> {
            if (event.getPointerCount() == 2) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_DOWN:
                        isScrolling = true;
                        lastScrollY = event.getY(0);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (isScrolling) {
                            float currentY = event.getY(0);
                            float deltaY = lastScrollY - currentY;

                            if (Math.abs(deltaY) > SCROLL_THRESHOLD) {
                                int scrollAmount = (int)(deltaY * SCROLL_SENSITIVITY);
                                sendScroll(scrollAmount);
                                lastScrollY = currentY;
                            }
                            return true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        isScrolling = false;
                        return true;
                }
                return true;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartTime = System.currentTimeMillis();
                    lastX = event.getX();
                    lastY = event.getY();
                    hasMoved = false;
                    synchronized (movementLock) {
                        accumulatedX = 0;
                        accumulatedY = 0;
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getX() - lastX;
                    float deltaY = event.getY() - lastY;

                    if (Math.abs(deltaX) > MOVEMENT_THRESHOLD || Math.abs(deltaY) > MOVEMENT_THRESHOLD) {
                        hasMoved = true;

                        synchronized (movementLock) {
                            accumulatedX += deltaX;
                            accumulatedY += deltaY;
                        }

                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastMovementTime >= MOVEMENT_BUFFER_MS) {
                            sendAccumulatedMovement();
                            lastMovementTime = currentTime;
                        }

                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    sendAccumulatedMovement();

                    long touchDuration = System.currentTimeMillis() - touchStartTime;
                    if (!hasMoved && touchDuration < TAP_THRESHOLD) {
                        sendMouseClick("left");
                        touchPad.performClick();
                    }

                    lastX = 0;
                    lastY = 0;
                    hasMoved = false;
                    return true;
            }
            return false;
        });

        btnLeftClick.setOnClickListener(v -> sendMouseClick("left"));
        btnMiddleClick.setOnClickListener(v -> sendMouseClick("middle"));
        btnRightClick.setOnClickListener(v -> sendMouseClick("right"));
    }

    private void showAddServerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_server, null);

        EditText nameInput = dialogView.findViewById(R.id.serverNameInput);
        EditText ipInput = dialogView.findViewById(R.id.serverIpInput);
        EditText portInput = dialogView.findViewById(R.id.serverPortInput);
        portInput.setText(String.valueOf(serverPort));

        builder.setView(dialogView)
                .setTitle("Add Server")
                .setPositiveButton("Add", (dialog, which) -> {
                    String name = nameInput.getText().toString();
                    String ip = ipInput.getText().toString();
                    int port = Integer.parseInt(portInput.getText().toString());

                    Server server = new Server(name, ip, port);
                    serverManager.addServer(server);
                    serverAdapter.notifyItemInserted(serverManager.getServers().size() - 1);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditServerDialog(int position) {
        Server server = serverManager.getServers().get(position);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_server, null);

        EditText nameInput = dialogView.findViewById(R.id.serverNameInput);
        EditText ipInput = dialogView.findViewById(R.id.serverIpInput);
        EditText portInput = dialogView.findViewById(R.id.serverPortInput);

        nameInput.setText(server.getName());
        ipInput.setText(server.getIpAddress());
        portInput.setText(String.valueOf(server.getPort()));

        builder.setView(dialogView)
                .setTitle("Edit Server")
                .setPositiveButton("Save", (dialog, which) -> {
                    server.setName(nameInput.getText().toString());
                    server.setIpAddress(ipInput.getText().toString());
                    server.setPort(Integer.parseInt(portInput.getText().toString()));

                    serverManager.updateServer(position, server);
                    serverAdapter.notifyItemChanged(position);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disconnectFromServer() {
        executorService.execute(() -> {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                    socket = null;
                    out = null;
                    runOnUiThread(() -> Toast.makeText(this, "Disconnected from server", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void connectToServer() {
        executorService.execute(() -> {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }

                socket = new Socket(serverIp, serverPort);
                out = new PrintWriter(socket.getOutputStream(), true);

                runOnUiThread(() -> Toast.makeText(this, "Connected to server", Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                runOnUiThread(
                        () -> Toast.makeText(this, "Connection failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void sendAccumulatedMovement() {
        synchronized (movementLock) {
            if (accumulatedX != 0 || accumulatedY != 0) {
                sendMouseMovement((int)accumulatedX, (int)accumulatedY);
                accumulatedX = 0;
                accumulatedY = 0;
            }
        }
    }

    private void sendMouseMovement(int deltaX, int deltaY) {
        if (out != null) {
            executorService.execute(() -> {
                // Apply sensitivity multiplier
                int adjustedX = (int)(deltaX * MOVEMENT_SENSITIVITY);
                int adjustedY = (int)(deltaY * MOVEMENT_SENSITIVITY);

                String message = String.format("M:%d,%d\n", adjustedX, adjustedY);
                out.print(message);
                out.flush();
            });
        }
    }

    private void sendMouseClick(String button) {
        if (out != null) {
            executorService.execute(() -> {
                out.println("C:" + button);
                out.flush();
            });
        }
    }

    private void sendScroll(int amount) {
        if (out != null) {
            executorService.execute(() -> {
                String message = String.format("S:%d\n", amount);
                out.print(message);
                out.flush();
            });
        }
    }

    private void sendText(String text) {
        if (out != null) {
            executorService.execute(() -> {
                out.println("T:" + text);
                out.flush();
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (socket != null)
                socket.close();
            executorService.shutdown();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
