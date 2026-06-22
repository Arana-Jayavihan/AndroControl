package com.aranaj.androcontrol;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private MaterialButton btnSystemKeyboard;
    private MaterialButton btnReconnect;
    private TextInputEditText textInput;
    private ToggleButton btnCtrl, btnAlt, btnShift, btnWin;
    private boolean systemKeyboardVisible = false;

    // Edge scroll zones (the touchpad's own left/right edges)
    private View scrollZoneLeft, scrollZoneRight;
    private int scrollZoneWidthPx = 0;
    private boolean scrollLeftEnabled = false;
    private boolean scrollRightEnabled = false;
    private int scrollDirectionFactor = 1;
    private boolean edgeScrolling = false;
    private float edgeLastY = 0f;

    // Settings
    private SettingsManager settingsManager;

    // Receives the "Disconnect" action from the foreground-service notification.
    private BroadcastReceiver disconnectReceiver;

    // Suppresses the "connection lost" toast when we deliberately closed the link (e.g. unpair).
    private volatile boolean suppressConnectionLostToast = false;

    // Skips the next onResume auto-reconnect when returning from an in-app screen
    // (QR scanner / Settings) rather than from the background, so it doesn't stack
    // a connection attempt (and its dialogs) on top of, e.g., the QR connect prompt.
    private boolean suppressResumeReconnect = false;

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
    // Server to auto-reconnect to when the app returns to the foreground.
    // Set on a successful connect; cleared only on a deliberate user disconnect.
    private Server reconnectTarget;

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

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Best-effort: the foreground service still runs if denied, just without a visible notification.
            });

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
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Apply system window insets (status/nav bars, cutout, IME) so content
        // is never hidden behind system UI in edge-to-edge mode.
        View root = findViewById(R.id.mainContainer);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            // Keep the "system keyboard" toggle in sync if the IME is dismissed by the user.
            boolean imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime());
            systemKeyboardVisible = imeVisible;
            updateSystemKeyboardButton();
            return windowInsets;
        });

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
        btnSystemKeyboard = findViewById(R.id.btnSystemKeyboard);
        btnReconnect = findViewById(R.id.btnReconnect);
        textInput = findViewById(R.id.textInput);
        btnCtrl = findViewById(R.id.btnCtrl);
        btnAlt = findViewById(R.id.btnAlt);
        btnShift = findViewById(R.id.btnShift);
        btnWin = findViewById(R.id.btnWin);

        // Edge scroll zones
        scrollZoneLeft = findViewById(R.id.scrollZoneLeft);
        scrollZoneRight = findViewById(R.id.scrollZoneRight);

        executorService = Executors.newFixedThreadPool(3);
        mainHandler = new Handler(Looper.getMainLooper());

        // Settings
        settingsManager = new SettingsManager(this);

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
                // Deliberate user disconnect — don't auto-reconnect on the next resume.
                reconnectTarget = null;
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
        setupControlButtons();
        applyScrollbarSettings();

        // Listen for the notification's "Disconnect" action.
        disconnectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reconnectTarget = null; // user-initiated; don't auto-reconnect
                disconnectFromServer();
            }
        };
        ContextCompat.registerReceiver(this, disconnectReceiver,
                new IntentFilter(ConnectionService.ACTION_DISCONNECT_REQUEST),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // Ask for notification permission so the ongoing-connection notification is visible (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            suppressResumeReconnect = true; // returning here is in-app navigation
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Wires the control-row buttons: system keyboard toggle and quick reconnect.
     * (The on-screen keyboard panel toggle is wired in {@link #setupKeyboardPanel()}.)
     */
    private void setupControlButtons() {
        if (btnSystemKeyboard != null) {
            btnSystemKeyboard.setOnClickListener(v -> toggleSystemKeyboard());
        }
        if (btnReconnect != null) {
            btnReconnect.setOnClickListener(v -> reconnectLastServer());
        }
    }

    /** Shows or hides the Android system (IME) keyboard, independent of the on-screen panel. */
    private void toggleSystemKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null || textInput == null) return;

        if (systemKeyboardVisible) {
            imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
            systemKeyboardVisible = false;
        } else {
            textInput.requestFocus();
            imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
            systemKeyboardVisible = true;
        }
        updateSystemKeyboardButton();
    }

    /** Reflects the current system-keyboard state in the toggle button. */
    private void updateSystemKeyboardButton() {
        if (btnSystemKeyboard != null) {
            btnSystemKeyboard.setChecked(systemKeyboardVisible);
        }
    }

    /** A human-readable name for this device, sent to the server during pairing. */
    private String getDeviceName() {
        String name = android.os.Build.MODEL;
        if (name == null || name.trim().isEmpty()) {
            name = android.os.Build.MANUFACTURER;
        }
        if (name == null || name.trim().isEmpty()) {
            name = "Android device";
        }
        return name.trim();
    }

    /** Reconnects to the most recently connected server (the quick-reconnect button). */
    private void reconnectLastServer() {
        Server target = (currentServer != null) ? currentServer : serverManager.getRecentServer();
        if (target == null) {
            Toast.makeText(this, R.string.msg_no_recent_server, Toast.LENGTH_SHORT).show();
            return;
        }

        char[] token = secureStorage.getTokenAsChars(target.getId());
        if (token != null) {
            target.setAuthTokenChars(token);
            SecureStorage.clearCharArray(token);
        }
        Toast.makeText(this, getString(R.string.msg_reconnecting_to, target.getName()), Toast.LENGTH_SHORT).show();
        connectToServer(target);
    }

    /**
     * Applies the saved scroll-zone position, width and direction to the touchpad edges.
     * Safe to call repeatedly (e.g. on resume after returning from Settings).
     */
    private void applyScrollbarSettings() {
        scrollLeftEnabled = settingsManager.isScrollbarOnLeft();
        scrollRightEnabled = settingsManager.isScrollbarOnRight();
        scrollDirectionFactor = settingsManager.getScrollDirectionFactor();
        scrollZoneWidthPx = Math.round(
                settingsManager.getScrollbarWidthDp() * getResources().getDisplayMetrics().density);

        configureScrollZone(scrollZoneLeft, scrollLeftEnabled, scrollZoneWidthPx);
        configureScrollZone(scrollZoneRight, scrollRightEnabled, scrollZoneWidthPx);
    }

    private void configureScrollZone(View zone, boolean visible, int widthPx) {
        if (zone == null) return;
        zone.setVisibility(visible ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams lp = zone.getLayoutParams();
        lp.width = widthPx;
        zone.setLayoutParams(lp);
    }

    /** @return true if a touch at the given X (within the touchpad) falls in an active scroll zone. */
    private boolean isInScrollZone(float x) {
        if (scrollZoneWidthPx <= 0) return false;
        int w = touchPad.getWidth();
        if (scrollLeftEnabled && x <= scrollZoneWidthPx) return true;
        if (scrollRightEnabled && x >= w - scrollZoneWidthPx) return true;
        return false;
    }

    private void setupKeyboardPanel() {
        // Toggle ONLY the on-screen QWERTY panel (system keyboard has its own button).
        btnToggleKeyboard.setOnClickListener(v -> {
            boolean show = keyboardPanel.getVisibility() != View.VISIBLE;
            keyboardPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            btnToggleKeyboard.setText(show ? R.string.btn_hide : R.string.btn_onscreen_keyboard);
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
        // Modifier-aware so combos like WIN+ENTER / WIN+SHIFT+ENTER work
        // (sends a COMBO when Ctrl/Alt/Shift/Win are active, otherwise plain Enter).
        findViewById(R.id.btnEnter).setOnClickListener(v -> sendKeyWithModifiers("ENTER"));
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
            if (vibrator == null || !vibrator.hasVibrator()) return;

            int amplitude = settingsManager.getHapticAmplitude();
            if (amplitude <= 0) return; // Haptics disabled by user

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Honour the configured intensity where the device supports amplitude control.
                int amp = vibrator.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE;
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amp));
            } else {
                vibrator.vibrate(durationMs);
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
                statusText.setText(getString(R.string.status_connected_to, serverName));
                statusText.setTextColor(getResources().getColor(R.color.success, getTheme()));
                // Keep the session alive in the background.
                ConnectionService.start(this, serverName);
            } else {
                statusBar.setVisibility(View.GONE);
                ConnectionService.stop(this);
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
                        int scrollAmount = (int)(deltaY * SCROLL_SENSITIVITY) * scrollDirectionFactor;
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
                // If the touch starts on a configured edge, treat it as a scroll gesture.
                if (isInScrollZone(event.getX())) {
                    edgeScrolling = true;
                    edgeLastY = event.getY();
                    return true;
                }
                edgeScrolling = false;
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
                if (edgeScrolling) {
                    float currentY = event.getY();
                    float edgeDelta = edgeLastY - currentY;
                    if (Math.abs(edgeDelta) > SCROLL_THRESHOLD) {
                        int scrollAmount = (int) (edgeDelta * SCROLL_SENSITIVITY) * scrollDirectionFactor;
                        if (scrollAmount != 0) {
                            sendScroll(scrollAmount);
                        }
                        edgeLastY = currentY;
                    }
                    return true;
                }
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
                if (edgeScrolling) {
                    // Edge scroll gesture — no click handling.
                    edgeScrolling = false;
                    return true;
                }
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
        // sendCommandNoAck is already async and ordered (single-thread sender);
        // don't wrap it in another executor or press/release can reorder.
        if (protocol != null) {
            protocol.sendCommandNoAck("MOUSEDOWN", button);
        }
    }

    private void sendMouseUp(String button) {
        if (protocol != null) {
            protocol.sendCommandNoAck("MOUSEUP", button);
        }
    }

    private void showAddServerDialog() {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_server, null);

        TextInputEditText nameInput = dialogView.findViewById(R.id.serverNameInput);
        TextInputEditText ipInput = dialogView.findViewById(R.id.serverIpInput);
        TextInputEditText portInput = dialogView.findViewById(R.id.serverPortInput);
        TextInputEditText tokenInput = dialogView.findViewById(R.id.serverTokenInput);
        portInput.setText(String.valueOf(serverPort));

        builder.setView(dialogView)
                .setTitle(R.string.dialog_add_title)
                .setPositiveButton(R.string.action_add, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String ip = ipInput.getText().toString().trim();
                    String portStr = portInput.getText().toString().trim();
                    String token = tokenInput.getText().toString();

                    // Validate inputs
                    if (name.isEmpty() || ip.isEmpty()) {
                        Toast.makeText(this, R.string.msg_name_ip_required, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int port;
                    try {
                        port = portStr.isEmpty() ? 5050 : Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.msg_invalid_port, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Server server = new Server(name, ip, port, token);

                    // Check for duplicate
                    if (!serverManager.addServer(server)) {
                        Toast.makeText(this, getString(R.string.msg_server_exists_at, ip, port), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (!token.isEmpty()) {
                        secureStorage.saveToken(server.getId(), token);
                    }

                    serverAdapter.notifyItemInserted(serverManager.getServers().size() - 1);
                    Toast.makeText(this, R.string.msg_server_added, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_cancel, null);
        secureShow(builder);
    }

    private void showEditServerDialog(int position) {
        Server server = serverManager.getServers().get(position);
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
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
            tokenInput.setHint(R.string.hint_token_saved);
        }

        builder.setView(dialogView)
                .setTitle(R.string.dialog_edit_title)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String ip = ipInput.getText().toString().trim();
                    String portStr = portInput.getText().toString().trim();

                    // Validate inputs
                    if (name.isEmpty() || ip.isEmpty()) {
                        Toast.makeText(this, R.string.msg_name_ip_required, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int port;
                    try {
                        port = portStr.isEmpty() ? server.getPort() : Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.msg_invalid_port, Toast.LENGTH_SHORT).show();
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
                .setNeutralButton(R.string.action_unpair, (dialog, which) -> confirmUnpair(server))
                .setNegativeButton(R.string.action_cancel, null);
        secureShow(builder);
    }

    /** Confirms then unpairs this device from the given server. */
    private void confirmUnpair(Server server) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_unpair_title)
                .setMessage(getString(R.string.dialog_unpair_message, server.getName()))
                .setPositiveButton(R.string.action_unpair, (d, w) -> unpairDevice(server))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Unpairs this device from the server. With mTLS the credential is this device's
     * client certificate (shared across servers), so unpairing means asking the
     * connected server to revoke it. When not connected we can't revoke remotely;
     * the user should revoke the device on the server (or connect first).
     */
    private void unpairDevice(Server server) {
        boolean connectedToThis = currentServer == server
                && protocol != null && protocol.isConnected();

        if (connectedToThis) {
            reconnectTarget = null;
            suppressConnectionLostToast = true; // server will close the link after revoking
            executorService.execute(() -> {
                try {
                    protocol.sendUnpair(); // server revokes this device's certificate, then closes
                } catch (Exception e) {
                    Log.w(TAG, "Unpair request failed", e);
                }
                mainHandler.post(() -> {
                    disconnectFromServer();
                    Toast.makeText(this, R.string.msg_unpaired, Toast.LENGTH_SHORT).show();
                });
            });
        } else {
            // Forget any stored enrollment token; remote revocation needs a connection.
            secureStorage.removeToken(server.getId());
            Toast.makeText(this, R.string.msg_unpair_offline, Toast.LENGTH_LONG).show();
        }
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
                                                       PrintWriter oldOut, BufferedReader oldIn,
                                                       boolean clearOldToken) {
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
                    // Don't wipe the token when reconnecting to the SAME server object —
                    // the in-progress connection still needs it to authenticate.
                    if (clearOldToken) {
                        oldServer.clearAuthToken();
                    }
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
                    Toast.makeText(this, R.string.msg_disconnected, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error during disconnect", e);
        }
    }

    private void connectToServer(Server server) {
        suppressConnectionLostToast = false;
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
                    // Only clear the old token if we're switching to a DIFFERENT server object;
                    // reconnecting to the same one must keep its token for re-authentication.
                    disconnectPreviousServerOnBackground(previousServer, previousSocket, previousOut, previousIn,
                            previousServer != server);
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

                // mTLS identity handshake: the server either recognizes our client
                // certificate, or asks us to pair using the enrollment token.
                char[] enrollToken = server.getAuthTokenChars(); // may be null once paired
                Protocol.AuthOutcome outcome = protocol.establishSession(
                        enrollToken, settingsManager.getClientDeviceId(), getDeviceName());

                switch (outcome) {
                    case AUTHENTICATED:
                        break; // certificate recognized
                    case PAIRED:
                        // Discard the enrollment token; the client certificate is the credential now.
                        secureStorage.removeToken(server.getId());
                        server.clearAuthToken();
                        break;
                    case NEEDS_ENROLLMENT:
                        synchronized (connectionLock) {
                            if (currentServer == server) isConnecting = false;
                        }
                        mainHandler.post(() -> {
                            Toast.makeText(this, R.string.msg_no_token, Toast.LENGTH_SHORT).show();
                            showTokenInputDialog(server);
                        });
                        socket.close();
                        return;
                    case LOCKED:
                        synchronized (connectionLock) {
                            if (currentServer == server) isConnecting = false;
                        }
                        mainHandler.post(() -> {
                            Toast.makeText(this, R.string.msg_locked, Toast.LENGTH_LONG).show();
                            server.setConnected(false);
                            serverAdapter.notifyDataSetChanged();
                        });
                        socket.close();
                        return;
                    case BUSY:
                        synchronized (connectionLock) {
                            if (currentServer == server) isConnecting = false;
                        }
                        mainHandler.post(() -> {
                            Toast.makeText(this, R.string.msg_server_busy, Toast.LENGTH_LONG).show();
                            server.setConnected(false);
                            serverAdapter.notifyDataSetChanged();
                        });
                        socket.close();
                        return;
                    case FAILED:
                    default:
                        synchronized (connectionLock) {
                            if (currentServer == server) isConnecting = false;
                        }
                        mainHandler.post(() -> {
                            Toast.makeText(this, R.string.msg_pairing_failed, Toast.LENGTH_LONG).show();
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
                        Toast.makeText(this, getString(R.string.msg_connected_to, server.getName()), Toast.LENGTH_SHORT).show();
                        server.setConnected(true);
                        serverManager.setLastConnectedServer(server.getId());
                        serverManager.setRecentServer(server.getId());
                        reconnectTarget = server;
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
                        Toast.makeText(this, getString(R.string.msg_connection_failed, e.getMessage()), Toast.LENGTH_LONG).show();
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
            currentCertificateDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_cert_verify_title)
                    .setMessage(getString(R.string.dialog_cert_verify_message,
                            server.getName(), formatFingerprintForDisplay(fingerprint)))
                    .setPositiveButton(R.string.action_accept, (dialog, which) -> {
                        pendingCertificateReject = null; // Clear so it won't be called on dismiss
                        currentCertificateDialog = null;
                        safeRunCallback(onConfirm);
                    })
                    .setNegativeButton(R.string.action_reject, (dialog, which) -> {
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
            currentCertificateDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_cert_warning_title)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(getString(R.string.dialog_cert_warning_message,
                            server.getName(),
                            formatFingerprintForDisplay(expectedFingerprint),
                            formatFingerprintForDisplay(actualFingerprint)))
                    .setPositiveButton(R.string.action_accept_new_cert, (dialog, which) -> {
                        pendingCertificateReject = null;
                        currentCertificateDialog = null;
                        safeRunCallback(onAcceptNew);
                    })
                    .setNegativeButton(R.string.action_reject, (dialog, which) -> {
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
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_token_input, null);
        TextInputEditText tokenInput = dialogView.findViewById(R.id.tokenInput);

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_auth_title)
                .setMessage(R.string.dialog_auth_message)
                .setView(dialogView)
                .setPositiveButton(R.string.btn_connect, (dialog, which) -> {
                    String token = tokenInput.getText() != null ? tokenInput.getText().toString() : "";
                    if (!token.isEmpty()) {
                        secureStorage.saveToken(server.getId(), token);
                        server.setAuthToken(token);
                        connectToServer(server);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null);
        secureShow(builder);
    }

    /**
     * Creates and shows a dialog with FLAG_SECURE so its contents (e.g. the pairing
     * token) can't be captured in screenshots, the Recents thumbnail, or screen recordings.
     */
    private void secureShow(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.show();
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
        if (protocol != null) {
            int adjustedX = (int) (deltaX * MOVEMENT_SENSITIVITY);
            int adjustedY = (int) (deltaY * MOVEMENT_SENSITIVITY);
            // Routed through the ordered single-thread sender (also avoids touching
            // the raw writer, which could be nulled mid-drag on disconnect).
            protocol.sendCommandNoAck("M", adjustedX + "," + adjustedY);
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


    // HeartbeatManager.HeartbeatListener implementation
    @Override
    public void onHeartbeatTimeout() {
        mainHandler.post(() -> {
            statusBar.setVisibility(View.VISIBLE);
            statusBar.setCardBackgroundColor(getResources().getColor(R.color.error_container, getTheme()));
            statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
            statusText.setText(R.string.status_reconnecting);
            statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
            Toast.makeText(this, R.string.msg_heartbeat_lost, Toast.LENGTH_LONG).show();
        });
        disconnectFromServer();
    }

    @Override
    public void onHeartbeatRestored() {
        mainHandler.post(() -> {
            Toast.makeText(this, R.string.msg_connection_restored, Toast.LENGTH_SHORT).show();
        });
    }

    // Protocol.ProtocolListener implementation
    @Override
    public void onConnectionLost() {
        final boolean suppress = suppressConnectionLostToast;
        suppressConnectionLostToast = false;
        mainHandler.post(() -> {
            updateStatusBar(false, null);
            if (!suppress) {
                Toast.makeText(this, R.string.msg_connection_lost, Toast.LENGTH_LONG).show();
            }
        });
        disconnectFromServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply scroll-bar preferences in case they changed in Settings.
        applyScrollbarSettings();
        // Skip auto-reconnect when returning from an in-app screen (QR scanner /
        // Settings); only reconnect when genuinely returning from the background.
        if (suppressResumeReconnect) {
            suppressResumeReconnect = false;
            return;
        }
        // Auto-reconnect to the last connected server when returning to the foreground
        // (e.g. after the app was minimized). connectToServer() shows a toast on failure.
        if (!isConnecting && reconnectTarget != null && (socket == null || socket.isClosed())) {
            Server target = reconnectTarget;
            char[] token = secureStorage.getTokenAsChars(target.getId());
            if (token != null) {
                target.setAuthTokenChars(token);
                SecureStorage.clearCharArray(token);
            }
            connectToServer(target);
        }
    }

    // QR Code scanning methods
    private void startQRScanner() {
        Log.d(TAG, "Starting QR Scanner Activity");
        suppressResumeReconnect = true; // returning here is in-app navigation, not a background return
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
            String fingerprint = json.optString("fp", "");

            Log.d(TAG, "Parsed - Name: " + name + ", IP: " + ip + ", Port: " + port);

            if (ip.isEmpty()) {
                Toast.makeText(this, R.string.msg_qr_missing_ip, Toast.LENGTH_SHORT).show();
                return;
            }

            // Pin the server certificate fingerprint from the QR so the first
            // connection is verified against it (no trust-on-first-use window).
            if (!fingerprint.isEmpty()) {
                TlsHelper.pinFingerprint(this, ip, port, fingerprint);
            }

            // Check if server already exists
            Server existingServer = serverManager.findByAddress(ip, port);
            if (existingServer != null) {
                // Server exists - offer to connect
                Toast.makeText(this, R.string.msg_server_exists, Toast.LENGTH_SHORT).show();
                if (isFinishing() || isDestroyed()) return;
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dialog_server_exists_title)
                        .setMessage(getString(R.string.dialog_server_exists_message, existingServer.getName(), ip, port))
                        .setPositiveButton(R.string.btn_connect, (dialog, which) -> {
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
                        .setNegativeButton(R.string.action_cancel, null)
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

            Toast.makeText(this, getString(R.string.msg_server_added_named, name), Toast.LENGTH_SHORT).show();

            // Ask if user wants to connect immediately
            if (isFinishing() || isDestroyed()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_connect_now_title)
                    .setMessage(getString(R.string.dialog_connect_now_message, name))
                    .setPositiveButton(R.string.btn_connect, (dialog, which) -> {
                        server.setAuthToken(token);
                        connectToServer(server);
                        // Close drawer after connecting
                        if (drawerLayout != null) {
                            drawerLayout.closeDrawer(GravityCompat.START);
                        }
                    })
                    .setNegativeButton(R.string.action_later, null)
                    .show();

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse QR code: " + qrContent, e);
            Toast.makeText(this, getString(R.string.msg_invalid_qr, qrContent.substring(0, Math.min(50, qrContent.length()))), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error handling QR code", e);
            Toast.makeText(this, getString(R.string.msg_error, e.getMessage()), Toast.LENGTH_LONG).show();
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

        if (disconnectReceiver != null) {
            try {
                unregisterReceiver(disconnectReceiver);
            } catch (Exception ignored) {
            }
            disconnectReceiver = null;
        }

        synchronized (connectionLock) {
            isConnecting = false;
        }

        heartbeatManager.shutdown();
        protocol.shutdown();

        if (isChangingConfigurations()) {
            // Being recreated (e.g. theme change). Close this instance's socket off
            // the main thread, but KEEP the persisted last-connected server so the
            // recreated activity auto-reconnects in onCreate.
            final SSLSocket oldSocket = socket;
            socket = null;
            out = null;
            in = null;
            new Thread(() -> {
                try {
                    if (oldSocket != null && !oldSocket.isClosed()) {
                        oldSocket.close();
                    }
                } catch (IOException ignored) {
                }
            }, "ConfigChangeSocketClose").start();
            ConnectionService.stop(this);
            executorService.shutdownNow();
            return;
        }

        disconnectFromServer();
        ConnectionService.stop(this);
        executorService.shutdown();
    }
}
