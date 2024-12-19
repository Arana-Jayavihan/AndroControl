package com.aranaj.androcontrol;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private EditText inputServerIp;
    private EditText inputText;
    private View touchPad;
    private Button btnSetServerIp, btnLeftClick, btnMiddleClick, btnRightClick;

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

    private String lastSentText = "";
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputServerIp = findViewById(R.id.inputServerIp);
        touchPad = findViewById(R.id.touchPad);
        btnSetServerIp = findViewById(R.id.btnSetServerIp);
        btnLeftClick = findViewById(R.id.btnLeftClick);
        btnMiddleClick = findViewById(R.id.btnMiddleClick);
        btnRightClick = findViewById(R.id.btnRightClick);

        executorService = Executors.newSingleThreadExecutor();

        btnSetServerIp.setOnClickListener(v -> {
            serverIp = inputServerIp.getText().toString().trim();
            if (!serverIp.isEmpty()) {
                connectToServer();
            } else {
                Toast.makeText(this, "Please enter a valid IP", Toast.LENGTH_SHORT).show();
            }
        });

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
                                int scrollAmount = (int) (deltaY * SCROLL_SENSITIVITY);
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
                sendMouseMovement((int) accumulatedX, (int) accumulatedY);
                accumulatedX = 0;
                accumulatedY = 0;
            }
        }
    }

    private void sendMouseMovement(int deltaX, int deltaY) {
        if (out != null) {
            executorService.execute(() -> {
                int adjustedX = (int) (deltaX * MOVEMENT_SENSITIVITY);
                int adjustedY = (int) (deltaY * MOVEMENT_SENSITIVITY);

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
