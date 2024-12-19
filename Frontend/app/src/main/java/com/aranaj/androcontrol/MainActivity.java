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
    private int serverPort = 5000;
    private Socket socket;
    private PrintWriter out;
    private ExecutorService executorService;

    private float lastX = 0, lastY = 0;
    private static final int MOVEMENT_THRESHOLD = 5;

    private long touchStartTime;
    private static final long TAP_THRESHOLD = 200;
    private boolean hasMoved = false;

    private String lastSentText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputServerIp = findViewById(R.id.inputServerIp);
        // inputText = findViewById(R.id.inputKey);
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
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartTime = System.currentTimeMillis();
                    lastX = event.getX();
                    lastY = event.getY();
                    hasMoved = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getX() - lastX;
                    float deltaY = event.getY() - lastY;

                    if (Math.abs(deltaX) > MOVEMENT_THRESHOLD || Math.abs(deltaY) > MOVEMENT_THRESHOLD) {
                        hasMoved = true;
                        sendMouseMovement((int) deltaX, (int) deltaY);

                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
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

    private void sendMouseMovement(int deltaX, int deltaY) {
        if (out != null) {
            executorService.execute(() -> {
                out.println("M:" + deltaX + "," + deltaY);
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
