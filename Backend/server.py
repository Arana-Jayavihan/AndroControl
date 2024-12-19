import socket
import uinput
import threading
import time

HOST = '0.0.0.0'
PORT = 5000

# Create uinput device
device = uinput.Device([
    uinput.REL_X, uinput.REL_Y,
    uinput.BTN_LEFT, uinput.BTN_RIGHT, uinput.BTN_MIDDLE,
    uinput.KEY_A, uinput.KEY_B, uinput.KEY_C, uinput.KEY_D, uinput.KEY_E,
    uinput.KEY_F, uinput.KEY_G, uinput.KEY_H, uinput.KEY_I, uinput.KEY_J,
    uinput.KEY_K, uinput.KEY_L, uinput.KEY_M, uinput.KEY_N, uinput.KEY_O,
    uinput.KEY_P, uinput.KEY_Q, uinput.KEY_R, uinput.KEY_S, uinput.KEY_T,
    uinput.KEY_U, uinput.KEY_V, uinput.KEY_W, uinput.KEY_X, uinput.KEY_Y,
    uinput.KEY_Z, uinput.KEY_SPACE, uinput.KEY_BACKSPACE, uinput.KEY_ENTER,
    uinput.KEY_LEFTSHIFT, uinput.KEY_RIGHTSHIFT,
    uinput.KEY_LEFTCTRL, uinput.KEY_RIGHTCTRL
])

def send_key_sequence(text):
    for char in text:
        try:
            if char == ' ':
                key_event = uinput.KEY_SPACE
            elif char == '\n':
                key_event = uinput.KEY_ENTER
            else:
                key_event = getattr(uinput, f'KEY_{char.upper()}', None)

            if key_event:
                device.emit_click(key_event)
                time.sleep(0.05)
        except Exception as e:
            print(f"Error sending key for '{char}': {e}")

def handle_client(client_socket):
    """Handle incoming messages from the client."""
    try:
        while True:
            data = client_socket.recv(1024).decode('utf-8').strip()
            if not data:
                break

            try:
                if data.startswith('M:'):
                    parts = data.split(':')[1].split(',')
                    if len(parts) == 2:
                        x, y = map(int, parts)
                        try:
                            device.emit(uinput.REL_X, x, syn=False)
                            device.emit(uinput.REL_Y, y)
                        except Exception as error:
                            print(error)

                elif data.startswith('C:'):
                    button = data.split(':')[1]
                    if button == 'left':
                        device.emit(uinput.BTN_LEFT, 1)
                        device.emit(uinput.BTN_LEFT, 0)
                    elif button == 'right':
                        device.emit(uinput.BTN_RIGHT, 1)
                        device.emit(uinput.BTN_RIGHT, 0)
                    elif button == 'middle':
                        device.emit(uinput.BTN_MIDDLE, 1)
                        device.emit(uinput.BTN_MIDDLE, 0)

                elif data.startswith('T:'):
                    text = data.split(':', 1)[1]
                    send_key_sequence(text)

                else:
                    print(f"Unknown command: {data}")

            except Exception as e:
                print(f"Error processing command: {e}")

    except Exception as ex:
        print(f"Client connection error: {ex}")

    finally:
        client_socket.close()

def start_server():
    """Start the socket server."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.bind((HOST, PORT))
        server.listen()
        print(f"Server listening on {HOST}:{PORT}")
        while True:
            client_socket, addr = server.accept()
            print(f"Connection established with {addr}")
            # Handle each client in a separate thread
            client_thread = threading.Thread(target=handle_client, args=(client_socket,))
            client_thread.start()

if __name__ == "__main__":
    start_server()
