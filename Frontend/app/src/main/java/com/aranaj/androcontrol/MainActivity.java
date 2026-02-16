package com.aranaj.androcontrol;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import android.content.Intent;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONException;
import org.json.JSONObject;

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
    private MaterialButton btnLeftClick, btnMiddleClick, btnRightClick;

    // Navigation drawer
    private DrawerLayout drawerLayout;
    private MaterialToolbar toolbar;

    // Status bar
    private MaterialCardView statusBar;
    private View statusIndicator;
    private TextView statusText, latencyText;

    // Keyboard panel
    private MaterialCardView keyboardPanel;
    private MaterialButton btnToggleKeyboard;
    private TextInputEditText textInput;
    private ToggleButton btnCtrl, btnAlt, btnShift, btnWin;

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
    private static final long DOUBLE_TAP_THRESHOLD = 300;
    private static final long LONG_PRESS_THRESHOLD = 500;
    private boolean hasMoved = false;
    private long lastTapTime = 0;

    private static final int MOVEMENT_BUFFER_MS = 16; // Increased for better performance
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

    // Connection state
    private volatile boolean isConnecting = false;
    private final Object connectionLock = new Object();
    private AlertDialog currentCertificateDialog = null;
    private Runnable pendingCertificateReject = null; // To signal latch when dialog is force-dismissed

    // Haptic feedback
    private Vibrator vibrator;

    // QR Code scanner
    private static final String TAG = "MainActivity";

    private final ActivityResultLauncher<Intent> qrScannerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Log.d(TAG, "QR Scanner returned with code: " + result.getResultCode());
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String qrContent = result.getData().getStringExtra(QRScannerActivity.EXTRA_QR_RESULT);
                    Log.d(TAG, "QR Content: " + qrContent);
                    if (qrContent != null && !qrContent.isEmpty()) {
                        handleQRCodeResult(qrContent);
                    }
                } else {
                    Log.d(TAG, "QR scan cancelled");
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize haptic feedback
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // Setup toolbar and navigation drawer
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawerLayout = findViewById(R.id.drawerLayout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // Status bar
        statusBar = findViewById(R.id.statusBar);
        statusIndicator = findViewById(R.id.statusIndicator);
        statusText = findViewById(R.id.statusText);
        latencyText = findViewById(R.id.latencyText);

        // Touch pad and mouse buttons
        touchPad = findViewById(R.id.touchPad);
        btnLeftClick = findViewById(R.id.btnLeftClick);
        btnMiddleClick = findViewById(R.id.btnMiddleClick);
        btnRightClick = findViewById(R.id.btnRightClick);

        // Keyboard panel
        keyboardPanel = findViewById(R.id.keyboardPanel);
        btnToggleKeyboard = findViewById(R.id.btnToggleKeyboard);
        textInput = findViewById(R.id.textInput);
        btnCtrl = findViewById(R.id.btnCtrl);
        btnAlt = findViewById(R.id.btnAlt);
        btnShift = findViewById(R.id.btnShift);
        btnWin = findViewById(R.id.btnWin);

        executorService = Executors.newFixedThreadPool(3);
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize security components
        secureStorage = new SecureStorage(this);
        heartbeatManager = new HeartbeatManager();
        heartbeatManager.setListener(this);
        protocol = new Protocol();
        protocol.setListener(this);
        protocol.setHeartbeatManager(heartbeatManager);

        serverManager = new ServerManager(this);
        serverList = findViewById(R.id.serverList);
        serverList.setLayoutManager(new LinearLayoutManager(this));

        serverAdapter = new ServerAdapter(serverManager.getServers(), new ServerAdapter.OnServerActionListener() {
            @Override
            public void onConnect(int position) {
                Server server = serverManager.getServers().get(position);
                char[] token = secureStorage.getTokenAsChars(server.getId());
                if (token != null) {
                    server.setAuthTokenChars(token);
                    SecureStorage.clearCharArray(token);
                }
                connectToServer(server);
                // Close drawer when connecting
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
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
            char[] token = secureStorage.getTokenAsChars(lastConnectedServer.getId());
            if (token != null) {
                lastConnectedServer.setAuthTokenChars(token);
                SecureStorage.clearCharArray(token);
            }
            connectToServer(lastConnectedServer);
        }

        serverList.setAdapter(serverAdapter);

        findViewById(R.id.btnAddServer).setOnClickListener(v -> showAddServerDialog());
        findViewById(R.id.btnScanQR).setOnClickListener(v -> startQRScanner());

        setupTouchPad();
        setupClickButtons();
        setupKeyboardPanel();
    }

    private void setupKeyboardPanel() {
        // Toggle keyboard panel and native keyboard
        btnToggleKeyboard.setOnClickListener(v -> {
            if (keyboardPanel.getVisibility() == View.VISIBLE) {
                keyboardPanel.setVisibility(View.GONE);
                btnToggleKeyboard.setText("Keyboard");
                // Hide native keyboard
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null && textInput != null) {
                    imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
                }
            } else {
                keyboardPanel.setVisibility(View.VISIBLE);
                btnToggleKeyboard.setText("Hide Keyboard");
                // Show native keyboard
                if (textInput != null) {
                    textInput.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
                    }
                }
            }
        });

        // Native keyboard input - send characters directly without displaying
        textInput.addTextChangedListener(new TextWatcher() {
            private boolean isClearing = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isClearing) return;
                String text = s.toString();
                if (text.isEmpty()) return;

                // Send each character directly to server
                if (out != null && protocol != null) {
                    for (char c : text.toCharArray()) {
                        sendCharacter(c);
                    }
                }

                // Clear immediately
                isClearing = true;
                s.clear();
                isClearing = false;
            }
        });

        // Capture backspace, delete, enter from native keyboard
        textInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    sendKey("BACKSPACE");
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                    sendKey("DELETE");
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    sendKey("ENTER");
                    return true;
                }
            }
            return false;
        });

        // Special keys
        findViewById(R.id.btnTab).setOnClickListener(v -> sendKey("TAB"));
        findViewById(R.id.btnEsc).setOnClickListener(v -> sendKey("ESC"));

        // Arrow keys
        findViewById(R.id.btnUp).setOnClickListener(v -> sendKeyWithModifiers("UP"));
        findViewById(R.id.btnDown).setOnClickListener(v -> sendKeyWithModifiers("DOWN"));
        findViewById(R.id.btnLeft).setOnClickListener(v -> sendKeyWithModifiers("LEFT"));
        findViewById(R.id.btnRight).setOnClickListener(v -> sendKeyWithModifiers("RIGHT"));

        // Navigation keys
        findViewById(R.id.btnHome).setOnClickListener(v -> sendKeyWithModifiers("HOME"));
        findViewById(R.id.btnEnd).setOnClickListener(v -> sendKeyWithModifiers("END"));
        findViewById(R.id.btnDel).setOnClickListener(v -> sendKey("DELETE"));

        // Number keys
        int[] numKeyIds = {R.id.btnNum1, R.id.btnNum2, R.id.btnNum3, R.id.btnNum4, R.id.btnNum5,
                          R.id.btnNum6, R.id.btnNum7, R.id.btnNum8, R.id.btnNum9, R.id.btnNum0};
        for (int i = 0; i < numKeyIds.length; i++) {
            final char numChar = (i == 9) ? '0' : (char) ('1' + i);
            final String keyName = String.valueOf(numChar);
            findViewById(numKeyIds[i]).setOnClickListener(v -> {
                vibrate(20);
                // Check for modifier combos
                boolean hasComboModifier = (btnCtrl != null && btnCtrl.isChecked()) ||
                                           (btnAlt != null && btnAlt.isChecked()) ||
                                           (btnWin != null && btnWin.isChecked());

                if (hasComboModifier) {
                    // Send as combo: CTRL+1, ALT+2, WIN+3, etc.
                    StringBuilder combo = new StringBuilder();
                    if (btnCtrl != null && btnCtrl.isChecked()) combo.append("CTRL+");
                    if (btnAlt != null && btnAlt.isChecked()) combo.append("ALT+");
                    if (btnShift != null && btnShift.isChecked()) combo.append("SHIFT+");
                    if (btnWin != null && btnWin.isChecked()) combo.append("SUPER+");
                    combo.append(keyName);

                    if (protocol != null) {
                        protocol.sendCommandNoAck("COMBO", combo.toString());
                    }

                    // Reset modifiers
                    if (btnCtrl != null) btnCtrl.setChecked(false);
                    if (btnAlt != null) btnAlt.setChecked(false);
                    if (btnShift != null) btnShift.setChecked(false);
                    if (btnWin != null) btnWin.setChecked(false);
                } else {
                    // Regular number character
                    sendCharacter(numChar);
                }
            });
        }

        // QWERTY letter keys
        int[] letterKeyIds = {
            R.id.btnQ, R.id.btnW, R.id.btnE, R.id.btnR, R.id.btnT, R.id.btnY, R.id.btnU, R.id.btnI, R.id.btnO, R.id.btnP,
            R.id.btnA, R.id.btnS, R.id.btnD, R.id.btnF, R.id.btnG, R.id.btnH, R.id.btnJ, R.id.btnK, R.id.btnL,
            R.id.btnZ, R.id.btnX, R.id.btnC, R.id.btnV, R.id.btnB, R.id.btnN, R.id.btnM
        };
        char[] letterChars = {
            'q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p',
            'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l',
            'z', 'x', 'c', 'v', 'b', 'n', 'm'
        };
        for (int i = 0; i < letterKeyIds.length; i++) {
            final char letter = letterChars[i];
            final String keyName = String.valueOf(Character.toUpperCase(letter));
            findViewById(letterKeyIds[i]).setOnClickListener(v -> {
                vibrate(20);
                // Check for modifier combos (Ctrl, Alt, Win)
                boolean hasComboModifier = (btnCtrl != null && btnCtrl.isChecked()) ||
                                           (btnAlt != null && btnAlt.isChecked()) ||
                                           (btnWin != null && btnWin.isChecked());

                if (hasComboModifier) {
                    // Send as combo: CTRL+C, ALT+F, WIN+D, etc.
                    StringBuilder combo = new StringBuilder();
                    if (btnCtrl != null && btnCtrl.isChecked()) combo.append("CTRL+");
                    if (btnAlt != null && btnAlt.isChecked()) combo.append("ALT+");
                    if (btnShift != null && btnShift.isChecked()) combo.append("SHIFT+");
                    if (btnWin != null && btnWin.isChecked()) combo.append("SUPER+");
                    combo.append(keyName);

                    if (protocol != null) {
                        protocol.sendCommandNoAck("COMBO", combo.toString());
                    }

                    // Reset modifiers
                    if (btnCtrl != null) btnCtrl.setChecked(false);
                    if (btnAlt != null) btnAlt.setChecked(false);
                    if (btnShift != null) btnShift.setChecked(false);
                    if (btnWin != null) btnWin.setChecked(false);
                } else if (btnShift != null && btnShift.isChecked()) {
                    // Uppercase letter
                    sendCharacter(Character.toUpperCase(letter));
                    btnShift.setChecked(false);
                } else {
                    // Regular lowercase letter
                    sendCharacter(letter);
                }
            });
        }

        // Space, Enter, Backspace
        findViewById(R.id.btnSpace).setOnClickListener(v -> {
            vibrate(20);
            sendCharacter(' ');
        });
        findViewById(R.id.btnEnter).setOnClickListener(v -> {
            vibrate(20);
            sendKey("ENTER");
        });
        findViewById(R.id.btnBackspace).setOnClickListener(v -> {
            vibrate(20);
            sendKey("BACKSPACE");
        });

        // Function keys
        int[] fKeyIds = {R.id.btnF1, R.id.btnF2, R.id.btnF3, R.id.btnF4,
                         R.id.btnF5, R.id.btnF6, R.id.btnF7, R.id.btnF8,
                         R.id.btnF9, R.id.btnF10, R.id.btnF11, R.id.btnF12};
        for (int i = 0; i < fKeyIds.length; i++) {
            final int fNum = i + 1;
            findViewById(fKeyIds[i]).setOnClickListener(v -> sendKeyWithModifiers("F" + fNum));
        }
    }

    private void sendCharacter(char c) {
        if (out == null || protocol == null) return;

        try {
            // Send character using the CHAR command (single character typing)
            protocol.sendCommandNoAck("CHAR", String.valueOf(c));
        } catch (Exception e) {
            Log.e(TAG, "Error sending character", e);
        }
    }

    private void sendKey(String keyName) {
        if (out != null && protocol != null) {
            try {
                protocol.sendCommandNoAck("KEY", keyName);
            } catch (Exception e) {
                Log.e(TAG, "Error sending key", e);
            }
        }
    }

    private void sendKeyWithModifiers(String keyName) {
        if (out != null && protocol != null) {
            try {
                vibrate(30);
                StringBuilder combo = new StringBuilder();
                if (btnCtrl != null && btnCtrl.isChecked()) combo.append("CTRL+");
                if (btnAlt != null && btnAlt.isChecked()) combo.append("ALT+");
                if (btnShift != null && btnShift.isChecked()) combo.append("SHIFT+");
                if (btnWin != null && btnWin.isChecked()) combo.append("SUPER+");
                combo.append(keyName);

                // Reset modifiers after use
                boolean hasModifiers = (btnCtrl != null && btnCtrl.isChecked()) ||
                                       (btnAlt != null && btnAlt.isChecked()) ||
                                       (btnShift != null && btnShift.isChecked()) ||
                                       (btnWin != null && btnWin.isChecked());
                if (hasModifiers) {
                    protocol.sendCommandNoAck("COMBO", combo.toString());
                    if (btnCtrl != null) btnCtrl.setChecked(false);
                    if (btnAlt != null) btnAlt.setChecked(false);
                    if (btnShift != null) btnShift.setChecked(false);
                    if (btnWin != null) btnWin.setChecked(false);
                } else {
                    protocol.sendCommandNoAck("KEY", keyName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending key with modifiers", e);
            }
        }
    }

    private void vibrate(int durationMs) {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(durationMs);
                }
            }
        } catch (SecurityException e) {
            // Permission not granted - disable vibration silently
            Log.w(TAG, "VIBRATE permission not granted", e);
            vibrator = null; // Disable future attempts
        } catch (Exception e) {
            Log.e(TAG, "Vibration failed", e);
        }
    }

    private void updateStatusBar(boolean connected, String serverName) {
        mainHandler.post(() -> {
            if (connected) {
                statusBar.setVisibility(View.VISIBLE);
                statusBar.setCardBackgroundColor(getResources().getColor(R.color.success_container, getTheme()));
                statusIndicator.setBackgroundResource(R.drawable.status_dot);
                statusText.setText("Connected to " + serverName);
                statusText.setTextColor(getResources().getColor(R.color.success, getTheme()));
            } else {
                statusBar.setVisibility(View.GONE);
            }
        });
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
                touchPad.setPressed(true);
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
                touchPad.setPressed(false);
                sendAccumulatedMovement();

                long touchDuration = System.currentTimeMillis() - touchStartTime;
                long timeSinceLastTap = System.currentTimeMillis() - lastTapTime;

                if (!hasMoved) {
                    if (touchDuration >= LONG_PRESS_THRESHOLD) {
                        // Long press = right click
                        vibrate(50);
                        sendMouseClick("right");
                    } else if (touchDuration < TAP_THRESHOLD) {
                        if (timeSinceLastTap < DOUBLE_TAP_THRESHOLD) {
                            // Double tap = double click
                            vibrate(30);
                            sendDoubleClick("left");
                            lastTapTime = 0;
                        } else {
                            // Single tap = left click
                            vibrate(20);
                            sendMouseClick("left");
                            lastTapTime = System.currentTimeMillis();
                        }
                    }
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
        // Left click with hold-to-drag support
        if (btnLeftClick != null) {
            btnLeftClick.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        vibrate(20);
                        sendMouseDown("left");
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        sendMouseUp("left");
                        v.setPressed(false);
                        return true;
                }
                return false;
            });
            // Double-tap detection for double-click
            btnLeftClick.setOnClickListener(new View.OnClickListener() {
                private long lastClickTime = 0;
                @Override
                public void onClick(View v) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastClickTime < DOUBLE_TAP_THRESHOLD) {
                        vibrate(30);
                        sendDoubleClick("left");
                        lastClickTime = 0;
                    } else {
                        lastClickTime = currentTime;
                    }
                }
            });
        }

        // Middle click with hold support
        if (btnMiddleClick != null) {
            btnMiddleClick.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        vibrate(20);
                        sendMouseDown("middle");
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        sendMouseUp("middle");
                        v.setPressed(false);
                        return true;
                }
                return false;
            });
        }

        // Right click with hold support
        if (btnRightClick != null) {
            btnRightClick.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        vibrate(20);
                        sendMouseDown("right");
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        sendMouseUp("right");
                        v.setPressed(false);
                        return true;
                }
                return false;
            });
        }
    }

    private void sendMouseDown(String button) {
        if (protocol != null) {
            executorService.execute(() -> {
                protocol.sendCommandNoAck("MOUSEDOWN", button);
            });
        }
    }

    private void sendMouseUp(String button) {
        if (protocol != null) {
            executorService.execute(() -> {
                protocol.sendCommandNoAck("MOUSEUP", button);
            });
        }
    }

    private void showAddServerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_server, null);

        TextInputEditText nameInput = dialogView.findViewById(R.id.serverNameInput);
        TextInputEditText ipInput = dialogView.findViewById(R.id.serverIpInput);
        TextInputEditText portInput = dialogView.findViewById(R.id.serverPortInput);
        TextInputEditText tokenInput = dialogView.findViewById(R.id.serverTokenInput);
        portInput.setText(String.valueOf(serverPort));

        builder.setView(dialogView)
                .setTitle("Add Server")
                .setPositiveButton("Add", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String ip = ipInput.getText().toString().trim();
                    String portStr = portInput.getText().toString().trim();
                    String token = tokenInput.getText().toString();

                    // Validate inputs
                    if (name.isEmpty() || ip.isEmpty()) {
                        Toast.makeText(this, "Name and IP are required", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int port;
                    try {
                        port = portStr.isEmpty() ? 5050 : Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Server server = new Server(name, ip, port, token);

                    // Check for duplicate
                    if (!serverManager.addServer(server)) {
                        Toast.makeText(this, "Server already exists: " + ip + ":" + port, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (!token.isEmpty()) {
                        secureStorage.saveToken(server.getId(), token);
                    }

                    serverAdapter.notifyItemInserted(serverManager.getServers().size() - 1);
                    Toast.makeText(this, "Server added", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditServerDialog(int position) {
        Server server = serverManager.getServers().get(position);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_server, null);

        TextInputEditText nameInput = dialogView.findViewById(R.id.serverNameInput);
        TextInputEditText ipInput = dialogView.findViewById(R.id.serverIpInput);
        TextInputEditText portInput = dialogView.findViewById(R.id.serverPortInput);
        TextInputEditText tokenInput = dialogView.findViewById(R.id.serverTokenInput);

        nameInput.setText(server.getName());
        ipInput.setText(server.getIpAddress());
        portInput.setText(String.valueOf(server.getPort()));

        String existingToken = secureStorage.getToken(server.getId());
        if (existingToken != null) {
            tokenInput.setHint("Token saved (enter new to change)");
        }

        builder.setView(dialogView)
                .setTitle("Edit Server")
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String ip = ipInput.getText().toString().trim();
                    String portStr = portInput.getText().toString().trim();

                    // Validate inputs
                    if (name.isEmpty() || ip.isEmpty()) {
                        Toast.makeText(this, "Name and IP are required", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int port;
                    try {
                        port = portStr.isEmpty() ? server.getPort() : Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    server.setName(name);
                    server.setIpAddress(ip);
                    server.setPort(port);

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
            disconnectFromServerInternal(true);
        });
    }

    /**
     * Performs network disconnect operations on background thread.
     * Called at the start of executor block in connectToServer().
     */
    private void disconnectPreviousServerOnBackground(Server oldServer, SSLSocket oldSocket,
                                                       PrintWriter oldOut, BufferedReader oldIn) {
        try {
            // Stop heartbeat
            if (heartbeatManager != null) {
                heartbeatManager.stop();
            }

            // Disconnect protocol (this does network I/O)
            if (protocol != null) {
                try {
                    protocol.disconnect();
                } catch (Exception e) {
                    Log.w(TAG, "Error disconnecting protocol", e);
                }
            }

            // Close socket (this does network I/O)
            if (oldSocket != null) {
                try {
                    if (!oldSocket.isClosed()) {
                        oldSocket.close();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Error closing socket", e);
                }
            }

            // Update UI on main thread
            mainHandler.post(() -> {
                updateStatusBar(false, null);
                if (oldServer != null) {
                    oldServer.setConnected(false);
                    oldServer.clearAuthToken();
                }
                if (serverAdapter != null) {
                    serverAdapter.notifyDataSetChanged();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error during background disconnect", e);
        }
    }

    /**
     * Internal disconnect implementation.
     * @param showNotification Whether to show toast and clear last connected server
     */
    private void disconnectFromServerInternal(boolean showNotification) {
        try {
            synchronized (connectionLock) {
                isConnecting = false;
            }

            if (protocol != null) {
                protocol.disconnect();
            }

            heartbeatManager.stop();

            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            socket = null;
            out = null;
            in = null;

            // Update UI on main thread
            final Server disconnectedServer = currentServer;
            mainHandler.post(() -> {
                // Dismiss any certificate dialog and signal its callback
                dismissCurrentCertificateDialog();

                updateStatusBar(false, null);
                if (disconnectedServer != null) {
                    disconnectedServer.setConnected(false);
                    disconnectedServer.clearAuthToken();
                    serverAdapter.notifyDataSetChanged();
                }
                if (showNotification) {
                    currentServer = null;
                    serverManager.clearLastConnectedServer();
                    Toast.makeText(this, "Disconnected from server", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error during disconnect", e);
        }
    }

    private void connectToServer(Server server) {
        // Capture previous connection state BEFORE entering synchronized block
        final Server previousServer;
        final SSLSocket previousSocket;
        final PrintWriter previousOut;
        final BufferedReader previousIn;
        final boolean needsDisconnect;

        synchronized (connectionLock) {
            // Cancel any in-progress connection (only UI operations, no network I/O)
            if (isConnecting) {
                Log.d(TAG, "Cancelling in-progress connection");
                // Only dismiss dialog - don't close socket on main thread
                dismissCurrentCertificateDialog();
            }

            // Capture previous connection state for background disconnect
            if (currentServer != null && currentServer.isConnected()) {
                Log.d(TAG, "Will disconnect from current server on background thread");
                previousServer = currentServer;
                previousSocket = socket;
                previousOut = out;
                previousIn = in;
                needsDisconnect = true;
                // Clear references so new connection can use them
                socket = null;
                out = null;
                in = null;
            } else {
                previousServer = null;
                previousSocket = socket;
                previousOut = null;
                previousIn = null;
                needsDisconnect = false;
            }

            isConnecting = true;
            currentServer = server;
        }

        serverIp = server.getIpAddress();
        serverPort = server.getPort();

        tlsHelper = new TlsHelper(this, serverIp, serverPort);

        // Set up TOFU confirmation callback
        tlsHelper.setConfirmationCallback(new TlsHelper.TofuConfirmationCallback() {
            @Override
            public void onFirstUse(String fingerprint, Runnable onConfirm, Runnable onReject) {
                mainHandler.post(() -> showCertificateConfirmationDialog(fingerprint, server, onConfirm, onReject, false));
            }

            @Override
            public void onMismatch(String expectedFingerprint, String actualFingerprint,
                                   Runnable onAcceptNew, Runnable onReject) {
                mainHandler.post(() -> showCertificateMismatchDialog(expectedFingerprint, actualFingerprint,
                        server, onAcceptNew, onReject));
            }
        });

        executorService.execute(() -> {
            try {
                // Disconnect from previous server first (now on background thread - safe!)
                if (needsDisconnect) {
                    disconnectPreviousServerOnBackground(previousServer, previousSocket, previousOut, previousIn);
                } else if (previousSocket != null && !previousSocket.isClosed()) {
                    // Close any leftover socket
                    try {
                        previousSocket.close();
                    } catch (IOException e) {
                        Log.w(TAG, "Error closing old socket", e);
                    }
                }

                // Check if this connection was cancelled
                synchronized (connectionLock) {
                    if (!isConnecting || currentServer != server) {
                        Log.d(TAG, "Connection cancelled before start");
                        return;
                    }
                }

                // Reset protocol state before new connection
                protocol.reset();

                socket = tlsHelper.createSocket(serverIp, serverPort);
                socket.startHandshake();

                // Check again if cancelled during handshake
                synchronized (connectionLock) {
                    if (!isConnecting || currentServer != server) {
                        Log.d(TAG, "Connection cancelled during handshake");
                        if (socket != null && !socket.isClosed()) {
                            socket.close();
                        }
                        return;
                    }
                }

                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                protocol.setStreams(out, in);

                char[] token = server.getAuthTokenChars();
                if (token == null || token.length == 0) {
                    synchronized (connectionLock) {
                        if (currentServer == server) {
                            isConnecting = false;
                        }
                    }
                    mainHandler.post(() -> {
                        Toast.makeText(this, "No auth token configured", Toast.LENGTH_SHORT).show();
                        showTokenInputDialog(server);
                    });
                    socket.close();
                    return;
                }

                // Note: authenticate() clears the token array after use
                if (!protocol.authenticate(token)) {
                    synchronized (connectionLock) {
                        if (currentServer == server) {
                            isConnecting = false;
                        }
                    }
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Authentication failed", Toast.LENGTH_LONG).show();
                        server.setConnected(false);
                        serverAdapter.notifyDataSetChanged();
                    });
                    socket.close();
                    return;
                }

                // Final check before completing connection
                synchronized (connectionLock) {
                    if (!isConnecting || currentServer != server) {
                        Log.d(TAG, "Connection cancelled before completion");
                        if (socket != null && !socket.isClosed()) {
                            socket.close();
                        }
                        return;
                    }
                }

                protocol.start();
                heartbeatManager.setWriter(out);
                heartbeatManager.start();

                synchronized (connectionLock) {
                    if (currentServer == server) {
                        isConnecting = false;
                    }
                }

                mainHandler.post(() -> {
                    // Only update UI if this is still the current server
                    if (currentServer == server) {
                        updateStatusBar(true, server.getName());
                        Toast.makeText(this, "Connected to " + server.getName(), Toast.LENGTH_SHORT).show();
                        server.setConnected(true);
                        serverManager.setLastConnectedServer(server.getId());
                        serverAdapter.notifyDataSetChanged();
                    }
                });

            } catch (IOException | NoSuchAlgorithmException | KeyManagementException e) {
                // Only reset isConnecting if this is still the current connection attempt
                synchronized (connectionLock) {
                    if (currentServer == server) {
                        isConnecting = false;
                    }
                }
                String errorMsg = getConnectionErrorMessage(e);
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    }
                    server.setConnected(false);
                    serverAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                // Only reset isConnecting if this is still the current connection attempt
                synchronized (connectionLock) {
                    if (currentServer == server) {
                        isConnecting = false;
                    }
                }
                // Catch any other exceptions (e.g., security exceptions during TLS handshake)
                Log.e(TAG, "Unexpected error during connection", e);
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    server.setConnected(false);
                    serverAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    /**
     * Cancels any in-progress connection attempt.
     * NOTE: This must be safe to call from the main thread.
     * Network operations (socket.close) happen in the executor when it detects cancellation.
     */
    private void cancelCurrentConnection() {
        // Signal the certificate latch BEFORE dismissing the dialog
        // This unblocks the TLS handshake thread so it can clean up
        if (pendingCertificateReject != null) {
            Log.d(TAG, "Signaling pending certificate reject");
            try {
                new Thread(pendingCertificateReject).start();
            } catch (Exception e) {
                Log.e(TAG, "Error signaling certificate reject", e);
            }
            pendingCertificateReject = null;
        }

        // Dismiss any certificate dialog
        if (currentCertificateDialog != null) {
            try {
                if (currentCertificateDialog.isShowing()) {
                    currentCertificateDialog.dismiss();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing certificate dialog", e);
            }
            currentCertificateDialog = null;
        }

        // NOTE: Don't close socket here - it's network I/O and would crash on main thread.
        // The executor will detect currentServer != server and close it there.

        // Reset protocol state (no network I/O, just clears state)
        if (protocol != null) {
            protocol.reset();
        }
        if (heartbeatManager != null) {
            heartbeatManager.stop();
        }

        out = null;
        in = null;
        isConnecting = false;
    }

    private String getConnectionErrorMessage(Exception e) {
        if (e instanceof javax.net.ssl.SSLException) {
            if (e.getMessage() != null && e.getMessage().contains("fingerprint mismatch")) {
                return "Certificate changed! Server may have been compromised.";
            }
            if (e.getMessage() != null && e.getMessage().contains("User rejected")) {
                return "Certificate verification cancelled.";
            }
            return "TLS error: " + e.getMessage();
        }
        if (e instanceof java.net.ConnectException) {
            return "Cannot reach server. Check IP address and port.";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "Connection timed out. Server may be offline.";
        }
        return "Connection failed: " + e.getMessage();
    }

    /**
     * Shows a dialog for first-use certificate confirmation.
     */
    private void showCertificateConfirmationDialog(String fingerprint, Server server,
                                                    Runnable onConfirm, Runnable onReject,
                                                    boolean isMismatch) {
        // Check if activity is still valid for showing dialog
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "Activity not valid for certificate dialog, rejecting");
            safeRunCallback(onReject);
            return;
        }

        // Cancel any existing certificate dialog and its pending callback
        dismissCurrentCertificateDialog();

        // Check if this connection is still valid
        synchronized (connectionLock) {
            if (!isConnecting || currentServer != server) {
                Log.w(TAG, "Connection no longer valid for certificate dialog");
                safeRunCallback(onReject);
                return;
            }
        }

        // Store reject callback so we can signal it if dialog is force-dismissed
        pendingCertificateReject = onReject;

        try {
            currentCertificateDialog = new AlertDialog.Builder(this)
                    .setTitle("Verify Server Certificate")
                    .setMessage("Connecting to " + server.getName() + " for the first time.\n\n" +
                            "Certificate fingerprint (SHA-256):\n\n" +
                            formatFingerprintForDisplay(fingerprint) + "\n\n" +
                            "Verify this fingerprint matches the one shown on the server " +
                            "before accepting.")
                    .setPositiveButton("Accept", (dialog, which) -> {
                        pendingCertificateReject = null; // Clear so it won't be called on dismiss
                        currentCertificateDialog = null;
                        safeRunCallback(onConfirm);
                    })
                    .setNegativeButton("Reject", (dialog, which) -> {
                        pendingCertificateReject = null; // Clear so it won't be called twice
                        currentCertificateDialog = null;
                        safeRunCallback(onReject);
                    })
                    .setCancelable(false)
                    .create();
            currentCertificateDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing certificate dialog", e);
            pendingCertificateReject = null;
            currentCertificateDialog = null;
            safeRunCallback(onReject);
        }
    }

    /**
     * Shows a warning dialog when certificate doesn't match saved fingerprint.
     */
    private void showCertificateMismatchDialog(String expectedFingerprint, String actualFingerprint,
                                                Server server, Runnable onAcceptNew, Runnable onReject) {
        // Check if activity is still valid for showing dialog
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "Activity not valid for certificate mismatch dialog, rejecting");
            safeRunCallback(onReject);
            return;
        }

        // Cancel any existing certificate dialog and its pending callback
        dismissCurrentCertificateDialog();

        // Check if this connection is still valid
        synchronized (connectionLock) {
            if (!isConnecting || currentServer != server) {
                Log.w(TAG, "Connection no longer valid for certificate mismatch dialog");
                safeRunCallback(onReject);
                return;
            }
        }

        // Store reject callback so we can signal it if dialog is force-dismissed
        pendingCertificateReject = onReject;

        try {
            currentCertificateDialog = new AlertDialog.Builder(this)
                    .setTitle("Certificate Warning")
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage("WARNING: The certificate for " + server.getName() + " has changed!\n\n" +
                            "This could indicate:\n" +
                            "• A man-in-the-middle attack\n" +
                            "• Server certificate was regenerated\n" +
                            "• You're connecting to a different server\n\n" +
                            "Expected fingerprint:\n" +
                            formatFingerprintForDisplay(expectedFingerprint) + "\n\n" +
                            "Current fingerprint:\n" +
                            formatFingerprintForDisplay(actualFingerprint) + "\n\n" +
                            "If you did NOT regenerate the server certificate, REJECT this connection.")
                    .setPositiveButton("Accept New Certificate", (dialog, which) -> {
                        pendingCertificateReject = null;
                        currentCertificateDialog = null;
                        safeRunCallback(onAcceptNew);
                    })
                    .setNegativeButton("Reject", (dialog, which) -> {
                        pendingCertificateReject = null;
                        currentCertificateDialog = null;
                        safeRunCallback(onReject);
                    })
                    .setCancelable(false)
                    .create();
            currentCertificateDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing certificate mismatch dialog", e);
            pendingCertificateReject = null;
            currentCertificateDialog = null;
            safeRunCallback(onReject);
        }
    }

    /**
     * Safely runs a callback on a new thread.
     */
    private void safeRunCallback(Runnable callback) {
        if (callback != null) {
            try {
                new Thread(callback).start();
            } catch (Exception e) {
                Log.e(TAG, "Error running callback", e);
            }
        }
    }

    /**
     * Dismisses the current certificate dialog and signals any pending reject callback.
     */
    private void dismissCurrentCertificateDialog() {
        if (pendingCertificateReject != null) {
            safeRunCallback(pendingCertificateReject);
            pendingCertificateReject = null;
        }
        if (currentCertificateDialog != null) {
            try {
                if (currentCertificateDialog.isShowing()) {
                    currentCertificateDialog.dismiss();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing dialog", e);
            }
            currentCertificateDialog = null;
        }
    }

    /**
     * Formats a fingerprint with line breaks for better readability.
     */
    private String formatFingerprintForDisplay(String fingerprint) {
        if (fingerprint == null) return "";
        // Split into groups of 4 pairs (8 characters + colons)
        StringBuilder sb = new StringBuilder();
        String[] parts = fingerprint.split(":");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0 && i % 8 == 0) {
                sb.append("\n");
            } else if (i > 0) {
                sb.append(":");
            }
            sb.append(parts[i]);
        }
        return sb.toString();
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
                String message = String.format("M:%d,%d", adjustedX, adjustedY);
                out.println(message);
                out.flush();
            });
        }
    }

    private void sendMouseClick(String button) {
        if (out != null && protocol != null) {
            try {
                protocol.sendCommandNoAck("C", button);
            } catch (Exception e) {
                Log.e(TAG, "Error sending mouse click", e);
            }
        }
    }

    private void sendDoubleClick(String button) {
        if (out != null && protocol != null) {
            try {
                protocol.sendCommandNoAck("DBLCLICK", button);
            } catch (Exception e) {
                Log.e(TAG, "Error sending double click", e);
            }
        }
    }

    private void sendScroll(int amount) {
        if (out != null && protocol != null) {
            try {
                protocol.sendCommandNoAck("S", String.valueOf(amount));
            } catch (Exception e) {
                Log.e(TAG, "Error sending scroll", e);
            }
        }
    }

    private void sendText(String text) {
        if (out != null && protocol != null) {
            try {
                protocol.sendCommand("T", text);
            } catch (Exception e) {
                Log.e(TAG, "Error sending text", e);
            }
        }
    }

    // HeartbeatManager.HeartbeatListener implementation
    @Override
    public void onHeartbeatTimeout() {
        mainHandler.post(() -> {
            statusBar.setVisibility(View.VISIBLE);
            statusBar.setCardBackgroundColor(getResources().getColor(R.color.error_container, getTheme()));
            statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
            statusText.setText("Connection lost - Reconnecting...");
            statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
            Toast.makeText(this, "Connection lost (heartbeat timeout)", Toast.LENGTH_LONG).show();
        });
        disconnectFromServer();
    }

    @Override
    public void onHeartbeatRestored() {
        mainHandler.post(() -> {
            Toast.makeText(this, "Connection restored", Toast.LENGTH_SHORT).show();
        });
    }

    // Protocol.ProtocolListener implementation
    @Override
    public void onConnectionLost() {
        mainHandler.post(() -> {
            updateStatusBar(false, null);
            Toast.makeText(this, "Connection lost", Toast.LENGTH_LONG).show();
        });
        disconnectFromServer();
    }

    @Override
    public void onAuthenticationRequired() {
        mainHandler.post(() -> {
            if (currentServer != null) {
                showTokenInputDialog(currentServer);
            }
        });
    }

    @Override
    public void onError(String message) {
        mainHandler.post(() -> {
            Toast.makeText(this, "Error: " + message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Only auto-reconnect if not already connecting and socket is closed
        if (!isConnecting && currentServer != null && (socket == null || socket.isClosed())) {
            char[] token = secureStorage.getTokenAsChars(currentServer.getId());
            if (token != null) {
                currentServer.setAuthTokenChars(token);
                SecureStorage.clearCharArray(token);
            }
            connectToServer(currentServer);
        }
    }

    // QR Code scanning methods
    private void startQRScanner() {
        Log.d(TAG, "Starting QR Scanner Activity");
        Intent intent = new Intent(this, QRScannerActivity.class);
        qrScannerLauncher.launch(intent);
    }

    private void handleQRCodeResult(String qrContent) {
        Log.d(TAG, "handleQRCodeResult called with: " + qrContent);

        // Check if activity is still valid
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "Activity not valid for QR handling");
            return;
        }

        try {
            JSONObject json = new JSONObject(qrContent);
            Log.d(TAG, "JSON parsed successfully");

            String name = json.optString("name", "Server");
            String ip = json.getString("ip");
            int port = json.optInt("port", 5050);
            String token = json.optString("token", "");

            Log.d(TAG, "Parsed - Name: " + name + ", IP: " + ip + ", Port: " + port);

            if (ip.isEmpty()) {
                Toast.makeText(this, "Invalid QR code: missing IP address", Toast.LENGTH_SHORT).show();
                return;
            }

            // Check if server already exists
            Server existingServer = serverManager.findByAddress(ip, port);
            if (existingServer != null) {
                // Server exists - offer to connect
                Toast.makeText(this, "Server already exists", Toast.LENGTH_SHORT).show();
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this)
                        .setTitle("Server Exists")
                        .setMessage("Server \"" + existingServer.getName() + "\" (" + ip + ":" + port + ") already exists. Connect now?")
                        .setPositiveButton("Connect", (dialog, which) -> {
                            char[] existingToken = secureStorage.getTokenAsChars(existingServer.getId());
                            if (existingToken != null) {
                                existingServer.setAuthTokenChars(existingToken);
                                SecureStorage.clearCharArray(existingToken);
                            }
                            connectToServer(existingServer);
                            if (drawerLayout != null) {
                                drawerLayout.closeDrawer(GravityCompat.START);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }

            // Create and save the new server
            Server server = new Server(name, ip, port, token);
            serverManager.addServer(server);

            if (!token.isEmpty()) {
                secureStorage.saveToken(server.getId(), token);
            }

            serverAdapter.notifyItemInserted(serverManager.getServers().size() - 1);

            Toast.makeText(this, "Server added: " + name, Toast.LENGTH_SHORT).show();

            // Ask if user wants to connect immediately
            if (isFinishing() || isDestroyed()) return;
            new AlertDialog.Builder(this)
                    .setTitle("Connect Now?")
                    .setMessage("Server \"" + name + "\" has been added. Connect now?")
                    .setPositiveButton("Connect", (dialog, which) -> {
                        server.setAuthToken(token);
                        connectToServer(server);
                        // Close drawer after connecting
                        if (drawerLayout != null) {
                            drawerLayout.closeDrawer(GravityCompat.START);
                        }
                    })
                    .setNegativeButton("Later", null)
                    .show();

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse QR code: " + qrContent, e);
            Toast.makeText(this, "Invalid QR code format. Content: " + qrContent.substring(0, Math.min(50, qrContent.length())), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error handling QR code", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Dismiss any certificate dialog and signal its callback to unblock TLS threads
        dismissCurrentCertificateDialog();

        synchronized (connectionLock) {
            isConnecting = false;
        }

        heartbeatManager.shutdown();
        protocol.shutdown();
        disconnectFromServer();
        executorService.shutdown();
    }
}
