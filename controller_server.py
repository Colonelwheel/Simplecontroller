import socket
import threading
import time
import re
import keyboard
import mouse
import vgamepad
import math
import logging
from datetime import datetime

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(f"controller_server_{datetime.now().strftime('%Y%m%d_%H%M%S')}.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Server configuration
HOST = '0.0.0.0'  # Listen on all interfaces
PORT = 9001       # Port used in your Android app's NetworkClient.kt

# Create a virtual Xbox 360 controller
gamepad = vgamepad.VX360Gamepad()

# Track button states
button_states = {}
mouse_left_down = False
is_touchpad_active = False

# Helper function for releasing Xbox buttons
def release_xbox_button(button):
    """Helper function to release an Xbox button"""
    try:
        gamepad.release_button(button=button)
        gamepad.update()
    except Exception as e:
        logger.error(f"Failed to release button: {str(e)}")

# Helper functions
def normalize_value(value):
    """Convert float string to normalized float (-1.0 to 1.0)"""
    try:
        return max(-1.0, min(1.0, float(value)))
    except ValueError:
        logger.error(f"Failed to convert value: {value}")
        return 0.0

def handle_stick_input(x, y, stick_type="LEFT"):
    """Handle analog stick input with improved handling"""
    x = normalize_value(x)
    y = normalize_value(y)
    
    # Apply deadzone if very close to center
    if abs(x) < 0.05 and abs(y) < 0.05:
        x, y = 0, 0
    
    try:
        if stick_type == "LEFT":
            gamepad.left_joystick_float(x_value_float=x, y_value_float=-y)  # Y is inverted for gamepad
        else:
            gamepad.right_joystick_float(x_value_float=x, y_value_float=-y)  # Y is inverted for gamepad
        
        gamepad.update()
        logger.info(f"Stick {stick_type}: x={x:.2f}, y={y:.2f}")
    except Exception as e:
        logger.error(f"Error handling stick input: {str(e)}")

def handle_trigger_input(value, trigger="LEFT"):
    """Handle analog trigger input (0.0 to 1.0)"""
    try:
        value = normalize_value(value)
        # Ensure value is between 0 and 1 for triggers
        value = max(0.0, min(1.0, value))
        
        if trigger == "LEFT":
            gamepad.left_trigger_float(value_float=value)
            logger.info(f"Left trigger: {value:.2f}")
        else:
            gamepad.right_trigger_float(value_float=value)
            logger.info(f"Right trigger: {value:.2f}")
        
        gamepad.update()
    except Exception as e:
        logger.error(f"Error handling trigger input: {str(e)}")

def handle_touchpad_input(x, y):
    """Handle touchpad input for mouse movement"""
    global is_touchpad_active
    
    x = normalize_value(x)
    y = normalize_value(y)
    
    # Scale the input to pixels (adjust sensitivity here)
    sensitivity = 15
    dx = int(x * sensitivity)
    dy = int(y * sensitivity)
    
    if not is_touchpad_active and (abs(dx) > 0 or abs(dy) > 0):
        is_touchpad_active = True
    
    if is_touchpad_active:
        try:
            # Move mouse relative to current position
            mouse.move(dx, dy, absolute=False)
            logger.info(f"Mouse move: dx={dx}, dy={dy}")
        except Exception as e:
            logger.error(f"Mouse movement error: {str(e)}")

def handle_wait_command(command):
    """Handle a wait command"""
    try:
        # Extract milliseconds from WAIT_X command
        wait_ms = int(command.split("_")[1])
        # Sleep for the specified time
        time.sleep(wait_ms / 1000.0)
        logger.info(f"Waited for {wait_ms}ms")
        return True
    except (ValueError, IndexError) as e:
        logger.error(f"Invalid wait command: {command} - {str(e)}")
        return False

def handle_button_press(command):
    """Handle various button commands with proper release handling"""
    global mouse_left_down
    
    # Check if this is a wait command
    if command.startswith("WAIT_"):
        return handle_wait_command(command)
    
    # Process special commands
    if command == "MOUSE_LEFT_DOWN":
        try:
            mouse.press(button="left")
            mouse_left_down = True
            logger.info("Mouse left button pressed")
        except Exception as e:
            logger.error(f"Failed to press mouse button: {str(e)}")
        return True
    
    if command == "MOUSE_LEFT_UP":
        try:
            mouse.release(button="left")
            mouse_left_down = False
            logger.info("Mouse left button released")
        except Exception as e:
            logger.error(f"Failed to release mouse button: {str(e)}")
        return True
    
    # Xbox controller buttons - with shortened syntax
    xbox_buttons = {
        # Original syntax
        "BUTTON_A_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_A,
        "BUTTON_B_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_B,
        "BUTTON_X_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_X,
        "BUTTON_Y_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        "BUTTON_LB_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        "BUTTON_RB_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        "BUTTON_START_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_START,
        "BUTTON_BACK_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        "BUTTON_DPAD_UP": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        "BUTTON_DPAD_DOWN": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        "BUTTON_DPAD_LEFT": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        "BUTTON_DPAD_RIGHT": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
        "BUTTON_LSTICK_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        "BUTTON_RSTICK_PRESSED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
        
        # Shorter Xbox syntax
        "X360A": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_A,
        "X360B": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_B,
        "X360X": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_X,
        "X360Y": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        "X360LB": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        "X360RB": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        "X360START": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_START,
        "X360BACK": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        "X360UP": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        "X360DOWN": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        "X360LEFT": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        "X360RIGHT": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
        "X360LS": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        "X360RS": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
        
        # HOLD versions (without auto-release)
        "X360A_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_A,
        "X360B_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_B,
        "X360X_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_X,
        "X360Y_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        "X360LB_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        "X360RB_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        "X360START_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_START,
        "X360BACK_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        "X360UP_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        "X360DOWN_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        "X360LEFT_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        "X360RIGHT_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
        "X360LS_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        "X360RS_HOLD": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
    }

    # Xbox button release commands
    xbox_release_commands = {
        "BUTTON_A_RELEASED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_A,
        "BUTTON_B_RELEASED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_B,
        # ... other original release commands ...
        
        # Release commands for shortened names
        "X360A_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_A,
        "X360B_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_B,
        "X360X_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_X,
        "X360Y_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        "X360LB_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        "X360RB_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        "X360START_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_START,
        "X360BACK_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        "X360UP_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        "X360DOWN_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        "X360LEFT_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        "X360RIGHT_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
        "X360LS_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        "X360RS_RELEASE": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
    }
    
    # Handle explicit button releases if your app sends them
    if command in xbox_release_commands:
        try:
            gamepad.release_button(button=xbox_release_commands[command])
            gamepad.update()
            logger.info(f"Xbox button explicitly released: {command}")
        except Exception as e:
            logger.error(f"Failed to release Xbox button: {str(e)}")
        return True
    
    # Check if it's an Xbox button press
    if command in xbox_buttons:
        try:
            # Press the button
            gamepad.press_button(button=xbox_buttons[command])
            gamepad.update()
            logger.info(f"Xbox button pressed: {command}")
            
            # Only auto-release if not a HOLD command
            if not command.endswith("_HOLD"):
                # Schedule the button release after a short delay (100ms)
                release_timer = threading.Timer(0.1, lambda: release_xbox_button(xbox_buttons[command]))
                release_timer.daemon = True
                release_timer.start()
                logger.info(f"Scheduled auto-release for {command}")
            else:
                logger.info(f"Hold mode - no auto-release for {command}")
        except Exception as e:
            logger.error(f"Failed to press Xbox button: {str(e)}")
        return True
    
    # Handle keyboard input (common keys)
    try:
        keyboard.press_and_release(command.lower())
        logger.info(f"Keyboard key pressed: {command}")
        return True
    except Exception as e:
        logger.error(f"Failed to process command: {command} - {str(e)}")
        return False

def process_timed_sequence(commands):
    """Process a sequence of commands with timing delays"""
    try:
        for cmd in commands:
            cmd = cmd.strip()
            if cmd:  # Skip empty commands
                # Process command and wait for completion
                handle_button_press(cmd)
    except Exception as e:
        logger.error(f"Error in timed sequence: {str(e)}")

def process_command(data):
    """Process incoming command from the Android app"""
    data = data.strip()
    logger.info(f"Received: {data}")
    
    try:
        # Handle trigger input - both original and shortened syntax
        if data.startswith("TRIGGER_L:"):
            value = data.split(":", 1)[1]
            handle_trigger_input(value, "LEFT")
            return
            
        if data.startswith("TRIGGER_R:"):
            value = data.split(":", 1)[1]
            handle_trigger_input(value, "RIGHT")
            return
            
        # Shortened trigger syntax
        if data.startswith("LT:"):
            value = data.split(":", 1)[1]
            handle_trigger_input(value, "LEFT")
            return
            
        if data.startswith("RT:"):
            value = data.split(":", 1)[1]
            handle_trigger_input(value, "RIGHT")
            return
        
        # Check for individual wait command
        if data.startswith("WAIT_"):
            handle_wait_command(data)
            return
        
        # Shortened stick position shortcuts
        if data == "LS_UP":
            handle_stick_input("0.0", "1.0", "LEFT")
            return
        elif data == "LS_DOWN":
            handle_stick_input("0.0", "-1.0", "LEFT")
            return
        elif data == "LS_LEFT":
            handle_stick_input("-1.0", "0.0", "LEFT")
            return
        elif data == "LS_RIGHT":
            handle_stick_input("1.0", "0.0", "LEFT")
            return
        elif data == "RS_UP":
            handle_stick_input("0.0", "1.0", "RIGHT")
            return
        elif data == "RS_DOWN":
            handle_stick_input("0.0", "-1.0", "RIGHT")
            return
        elif data == "RS_LEFT":
            handle_stick_input("-1.0", "0.0", "RIGHT")
            return
        elif data == "RS_RIGHT":
            handle_stick_input("1.0", "0.0", "RIGHT")
            return
        
        # Handle stick/touchpad input (format: "STICK:x,y" or "TOUCHPAD:x,y")
        if ":" in data:
            command, coords = data.split(":", 1)
            if "," in coords:
                x, y = coords.split(",", 1)
                if command == "STICK" or command == "STICK_L" or command == "LS":
                    handle_stick_input(x, y, "LEFT")
                elif command == "STICK_R" or command == "RS":
                    handle_stick_input(x, y, "RIGHT")
                elif command == "TOUCHPAD":
                    handle_touchpad_input(x, y)
                else:
                    logger.warning(f"Unknown coordinate command: {command}")
            else:
                logger.warning(f"Invalid coordinate format: {coords}")
        
        # Handle commands with commas (format: "W,SHIFT" or "A,WAIT_500,B")
        elif "," in data:
            commands = data.split(",")
            
            # Process each command in sequence
            threading.Thread(target=process_timed_sequence, args=(commands,), daemon=True).start()
            logger.info(f"Started command sequence with {len(commands)} commands")
        
        # Handle simple button commands
        else:
            handle_button_press(data)
    except Exception as e:
        logger.error(f"Error processing command '{data}': {str(e)}")

def client_handler(conn, addr):
    """Handle client connection"""
    logger.info(f"Connected by {addr}")
    
    try:
        # Read commands from the client
        buffer = ""
        while True:
            data = conn.recv(1024).decode('utf-8')
            if not data:
                break
                
            # Append to buffer and process complete lines
            buffer += data
            lines = buffer.split("\n")
            
            # Process all complete lines
            for line in lines[:-1]:
                if line.strip():
                    process_command(line)
            
            # Keep the last (potentially incomplete) line
            buffer = lines[-1]
    except ConnectionResetError:
        logger.warning(f"Connection reset by {addr}")
    except Exception as e:
        logger.error(f"Error handling client {addr}: {str(e)}")
    finally:
        # Clean up
        try:
            conn.close()
        except:
            pass
        logger.info(f"Connection from {addr} closed")

def start_server():
    """Start the TCP server to receive commands"""
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    
    try:
        server_socket.bind((HOST, PORT))
        server_socket.listen(5)
        logger.info(f"Server started on {HOST}:{PORT}")
        
        while True:
            conn, addr = server_socket.accept()
            client_thread = threading.Thread(target=client_handler, args=(conn, addr))
            client_thread.daemon = True
            client_thread.start()
    except Exception as e:
        logger.error(f"Server error: {str(e)}")
    finally:
        try:
            server_socket.close()
        except:
            pass
        logger.info("Server stopped")

if __name__ == "__main__":
    logger.info("Simple Controller Server starting up...")
    logger.info("Press Ctrl+C to exit")
    
    try:
        # Start the server in a separate thread
        server_thread = threading.Thread(target=start_server)
        server_thread.daemon = True
        server_thread.start()
        
        # Keep the main thread alive
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        logger.info("Server stopping...")
    except Exception as e:
        logger.error(f"Unexpected error: {str(e)}")
    finally:
        logger.info("Server stopped")