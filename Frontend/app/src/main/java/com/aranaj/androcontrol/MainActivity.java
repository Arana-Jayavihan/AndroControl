package com.aranaj.androcontrol;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

public class MainActivity extends AppCompatActivity implements
        HeartbeatManager.HeartbeatListener,
        Protocol.ProtocolListener {

    private View touchPad;
    private Button btnLeftClick, btnMiddleClick, btnRightClick;

    private String serverIp = "";
    private int serverPort = 5050;
    private SSLSocket socket;
    private PrintWriter out;
    private BufferedReader in;
    private ExecutorService executorService;
    private Handler mainHandler;

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
    private ServerAdapter serverAdapter;
    private RecyclerView serverList;
    private Server currentServer;

    // Security components
    private TlsHelper tlsHelper;
    private SecureStorage secureStorage;
    private HeartbeatManager heartbeatManager;
    private Protocol protocol;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        touchPad = findViewById(R.id.touchPad);
        btnLeftClick = findViewById(R.id.btnLeftClick);
        btnMiddleClick = findViewById(R.id.btnMiddleClick);
        btnRightClick = findViewById(R.id.btnRightClick);

        executorService = Executors.newFixedThreadPool(3);
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize security components
        secureStorage = new SecureStorage(this);
        heartbeatManager = new HeartbeatManager();
        heartbeatManager.setListener(this);
        protocol = new Protocol();
        protocol.setListener(this);

        serverManager = new ServerManager(this);
        serverList = findViewById(R.id.serverList);
        serverList.setLayoutManager(new LinearLayoutManager(this));

        serverAdapter = new ServerAdapter(serverManager.getServers(), new ServerAdapter.OnServerActionListener() {
            @Override
            public void onConnect(int position) {
                Server server = serverManager.getServers().get(position);
                // Load token from secure storage
                String token = secureStorage.getToken(server.getId());
                server.setAuthToken(token);
                connectToServer(server);
            }

            @Override
            public void onDisconnect(int position) {
                disconnectFromServer();
            }

            @Override
            public void onEdit(int position) {
                showEditServerDialog(position);
            }

            @Override
            public void onDelete(int position) {
                Server server = serverManager.getServers().get(position);
                secureStorage.removeToken(server.getId());
                serverManager.removeServer(position);
                serverAdapter.notifyItemRemoved(position);
            }
        });

        Server lastConnectedServer = serverManager.getLastConnectedServer();
        if (lastConnectedServer != null) {
            String token = secureStorage.getToken(lastConnectedServer.getId());
            lastConnectedServer.setAuthToken(token);
            connectToServer(lastConnectedServer);
        }

        serverList.setAdapter(serverAdapter);

        findViewById(R.id.btnAddServer).setOnClickListener(v -> showAddServerDialog());

        setupTouchPad();
        setupClickButtons();
    }

    private void setupTouchPad() {
        touchPad.setOnTouchListener((v, event) -> {
            if (event.getPointerCount() == 2) {
                return handleTwoFingerGesture(event);
            }
            return handleSingleFingerGesture(event);
        });
    }

    private boolean handleTwoFingerGesture(MotionEvent event) {
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

    private boolean handleSingleFingerGesture(MotionEvent event) {
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
    }

    private void setupClickButtons() {
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
        EditText tokenInput = dialogView.findViewById(R.id.serverTokenInput);
        portInput.setText(String.valueOf(serverPort));

        builder.setView(dialogView)
                .setTitle("Add Server")
                .setPositiveButton("Add", (dialog, which) -> {
                    String name = nameInput.getText().toString();
                    String ip = ipInput.getText().toString();
                    int port = Integer.parseInt(portInput.getText().toString());
                    String token = tokenInput.getText().toString();

                    Server server = new Server(name, ip, port, token);
                    serverManager.addServer(server);

                    // Store token securely
                    if (!token.isEmpty()) {
                        secureStorage.saveToken(server.getId(), token);
                    }

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
        EditText tokenInput = dialogView.findViewById(R.id.serverTokenInput);

        nameInput.setText(server.getName());
        ipInput.setText(server.getIpAddress());
        portInput.setText(String.valueOf(server.getPort()));

        // Load existing token
        String existingToken = secureStorage.getToken(server.getId());
        if (existingToken != null) {
            tokenInput.setHint("Token saved (enter new to change)");
        }

        builder.setView(dialogView)
                .setTitle("Edit Server")
                .setPositiveButton("Save", (dialog, which) -> {
                    server.setName(nameInput.getText().toString());
                    server.setIpAddress(ipInput.getText().toString());
                    server.setPort(Integer.parseInt(portInput.getText().toString()));

                    String newToken = tokenInput.getText().toString();
                    if (!newToken.isEmpty()) {
                        secureStorage.saveToken(server.getId(), newToken);
                        server.setAuthToken(newToken);
                    }

                    serverManager.updateServer(position, server);
                    serverAdapter.notifyItemChanged(position);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disconnectFromServer() {
        executorService.execute(() -> {
            try {
                // Send graceful disconnect
                if (protocol != null) {
                    protocol.disconnect();
                }

                // Stop heartbeat
                heartbeatManager.stop();

                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                socket = null;
                out = null;
                in = null;

                mainHandler.post(() -> {
                    if (currentServer != null) {
                        currentServer.setConnected(false);
                        currentServer = null;
                        serverManager.clearLastConnectedServer();
                        serverAdapter.notifyDataSetChanged();
                    }
                    Toast.makeText(this, "Disconnected from server", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void connectToServer(Server server) {
        currentServer = server;
        serverIp = server.getIpAddress();
        serverPort = server.getPort();

        // Initialize TLS helper for this server
        tlsHelper = new TlsHelper(this, serverIp, serverPort);

        executorService.execute(() -> {
            try {
                // Close existing connection
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }

                // Create TLS socket
                socket = tlsHelper.createSocket(serverIp, serverPort);
                socket.startHandshake();

                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // Setup protocol with streams
                protocol.setStreams(out, in);

                // Authenticate
                String token = server.getAuthToken();
                if (token == null || token.isEmpty()) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "No auth token configured", Toast.LENGTH_SHORT).show();
                        showTokenInputDialog(server);
                    });
                    socket.close();
                    return;
                }

                if (!protocol.authenticate(token)) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Authentication failed", Toast.LENGTH_LONG).show();
                        server.setConnected(false);
                        serverAdapter.notifyDataSetChanged();
                    });
                    socket.close();
                    return;
                }

                // Start protocol handler and heartbeat
                protocol.start();
                heartbeatManager.setWriter(out);
                heartbeatManager.start();

                mainHandler.post(() -> {
                    Toast.makeText(this, "Connected to " + server.getName(), Toast.LENGTH_SHORT).show();
                    server.setConnected(true);
                    serverManager.setLastConnectedServer(server.getId());
                    serverAdapter.notifyDataSetChanged();
                });

            } catch (IOException | NoSuchAlgorithmException | KeyManagementException e) {
                String errorMsg = getConnectionErrorMessage(e);
                mainHandler.post(() -> {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    server.setConnected(false);
                    serverAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    private String getConnectionErrorMessage(Exception e) {
        if (e instanceof javax.net.ssl.SSLException) {
            if (e.getMessage() != null && e.getMessage().contains("fingerprint mismatch")) {
                return "Certificate changed! Server may have been compromised.";
            }
            return "TLS error: " + e.getMessage();
        }
        return "Connection failed: " + e.getMessage();
    }

    private void showTokenInputDialog(Server server) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        EditText tokenInput = new EditText(this);
        tokenInput.setHint("Enter authentication token");

        builder.setTitle("Authentication Required")
                .setMessage("Enter the token shown on the server")
                .setView(tokenInput)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String token = tokenInput.getText().toString();
                    if (!token.isEmpty()) {
                        secureStorage.saveToken(server.getId(), token);
                        server.setAuthToken(token);
                        connectToServer(server);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
                int adjustedX = (int)(deltaX * MOVEMENT_SENSITIVITY);
                int adjustedY = (int)(deltaY * MOVEMENT_SENSITIVITY);

                // Use fire-and-forget for high-frequency mouse movements
                String message = String.format("M:%d,%d", adjustedX, adjustedY);
                out.println(message);
                out.flush();
            });
        }
    }

    private void sendMouseClick(String button) {
        if (out != null) {
            // Use protocol for ACK tracking on clicks
            protocol.sendCommandNoAck("C", button);
        }
    }

    private void sendScroll(int amount) {
        if (out != null) {
            protocol.sendCommandNoAck("S", String.valueOf(amount));
        }
    }

    private void sendText(String text) {
        if (out != null) {
            protocol.sendCommand("T", text);
        }
    }

    // HeartbeatManager.HeartbeatListener implementation
    @Override
    public void onHeartbeatTimeout() {
        Toast.makeText(this, "Connection lost (heartbeat timeout)", Toast.LENGTH_LONG).show();
        disconnectFromServer();
    }

    @Override
    public void onHeartbeatRestored() {
        Toast.makeText(this, "Connection restored", Toast.LENGTH_SHORT).show();
    }

    // Protocol.ProtocolListener implementation
    @Override
    public void onConnectionLost() {
        Toast.makeText(this, "Connection lost", Toast.LENGTH_LONG).show();
        disconnectFromServer();
    }

    @Override
    public void onAuthenticationRequired() {
        if (currentServer != null) {
            showTokenInputDialog(currentServer);
        }
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, "Error: " + message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentServer != null && (socket == null || socket.isClosed())) {
            String token = secureStorage.getToken(currentServer.getId());
            currentServer.setAuthToken(token);
            connectToServer(currentServer);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        heartbeatManager.shutdown();
        protocol.shutdown();
        disconnectFromServer();
        executorService.shutdown();
    }
}
