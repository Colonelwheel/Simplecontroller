import socket
import threading
import time
import re
import keyboard
import mouse
import vgamepad
import math
import logging
import random
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

# Define low-latency socket function
def setup_low_latency_socket(sock):
    """Configure socket for minimal latency with Windows compatibility"""
    try:
        # Check if this is a TCP socket (UDP doesn't use Nagle's algorithm)
        if sock.type == socket.SOCK_STREAM:
            try:
                # Disable Nagle's algorithm for TCP sockets
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                logger.info("Applied TCP_NODELAY setting")
            except (socket.error, OSError) as e:
                logger.warning(f"Could not set TCP_NODELAY: {str(e)}")
        
        # Try to set buffer sizes, but handle platform-specific issues
        try:
            # Start with moderate buffer sizes that are more likely to be accepted
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
            logger.info("Applied buffer size settings")
        except (socket.error, OSError) as e:
            logger.warning(f"Could not set socket buffer sizes: {str(e)}")
        
        logger.info("Low-latency socket configuration applied (with platform compatibility)")
    except Exception as e:
        logger.error(f"Failed to apply low-latency socket configuration: {str(e)}")
        logger.info("Continuing with default socket settings")

# Server configuration
HOST = '0.0.0.0'  # Listen on all interfaces
PORT = 9001       # Port used in your Android app's NetworkClient.kt

# Create virtual Xbox 360 controllers - one for each player
gamepads = {
    'player1': vgamepad.VX360Gamepad(),
    'player2': vgamepad.VX360Gamepad()
}

# Track button states per player
button_states = {
    'player1': {},
    'player2': {}
}

# Track which keys are currently pressed
key_states = {
    'player1': {},
    'player2': {}
}

# Track mouse states per player
mouse_states = {
    'player1': {'left_down': False, 'is_touchpad_active': False},
    'player2': {'left_down': False, 'is_touchpad_active': False}
}

# Track active connections
active_connections = {}

# Helper function for releasing Xbox buttons
def release_xbox_button(button, player_id='player1'):
    """Helper function to release an Xbox button"""
    try:
        if player_id in gamepads:
            gamepads[player_id].release_button(button=button)
            gamepads[player_id].update()
            logger.info(f"{player_id} released button: {button}")
    except Exception as e:
        logger.error(f"Failed to release button for {player_id}: {str(e)}")

# Helper functions
def normalize_value(value):
    """Convert float string to normalized float (-1.0 to 1.0)"""
    try:
        return max(-1.0, min(1.0, float(value)))
    except ValueError:
        logger.error(f"Failed to convert value: {value}")
        return 0.0

def handle_stick_input(x, y, stick_type="LEFT", player_id='player1'):
    """Handle analog stick input with improved handling"""
    x = normalize_value(x)
    y = normalize_value(y)
    
    # Apply deadzone if very close to center
    if abs(x) < 0.05 and abs(y) < 0.05:
        x, y = 0, 0
    
    try:
        if player_id not in gamepads:
            logger.error(f"Unknown player ID: {player_id}")
            return
            
        gamepad = gamepads[player_id]
        
        if stick_type == "LEFT":
            gamepad.left_joystick_float(x_value_float=x, y_value_float=-y)  # Y is inverted for gamepad
        else:
            gamepad.right_joystick_float(x_value_float=x, y_value_float=-y)  # Y is inverted for gamepad
        
        gamepad.update()
        logger.info(f"{player_id} Stick {stick_type}: x={x:.2f}, y={y:.2f}")
    except Exception as e:
        logger.error(f"Error handling stick input for {player_id}: {str(e)}")

def handle_trigger_input(value, trigger="LEFT", player_id='player1'):
    """Handle analog trigger input (0.0 to 1.0)"""
    try:
        value = normalize_value(value)
        # Ensure value is between 0 and 1 for triggers
        value = max(0.0, min(1.0, value))
        
        if player_id not in gamepads:
            logger.error(f"Unknown player ID: {player_id}")
            return
            
        gamepad = gamepads[player_id]
        
        if trigger == "LEFT":
            gamepad.left_trigger_float(value_float=value)
            logger.info(f"{player_id} Left trigger: {value:.2f}")
        else:
            gamepad.right_trigger_float(value_float=value)
            logger.info(f"{player_id} Right trigger: {value:.2f}")
        
        gamepad.update()
    except Exception as e:
        logger.error(f"Error handling trigger input for {player_id}: {str(e)}")

def handle_touchpad_input(x, y, player_id='player1'):
    """
    Handle touchpad input for mouse movement with momentum-based edge handling.
    
    Key features:
    1. Uses relative movement tracking with edge detection
    2. Adds momentum when at edges to continue movement beyond boundaries
    3. Prevents cursor "snapping" when lifting/placing finger
    4. Preserves movement intent across touches
    5. Handles jitter and improves precision
    """
    # Ensure the player state exists and is fully initialized
    if player_id not in mouse_states:
        # Initialize player state if doesn't exist
        logger.info(f"Creating new state for player ID: {player_id}")
        mouse_states[player_id] = {}
        
    # Get the player's state
    mouse_state = mouse_states[player_id]
    
    # Make sure all required state fields are initialized
    if 'total_dx' not in mouse_state:
        mouse_state['total_dx'] = 0           # Track total distance moved
    if 'total_dy' not in mouse_state:
        mouse_state['total_dy'] = 0           # Track total distance moved
    if 'momentum_x' not in mouse_state:
        mouse_state['momentum_x'] = 0         # Store movement momentum for edges
    if 'momentum_y' not in mouse_state:
        mouse_state['momentum_y'] = 0         # Store movement momentum for edges
    if 'is_touchpad_active' not in mouse_state:
        mouse_state['is_touchpad_active'] = False
    if 'touch_active' not in mouse_state:
        mouse_state['touch_active'] = False   # Is a touch currently in progress
    if 'edge_active_x' not in mouse_state:
        mouse_state['edge_active_x'] = False  # Are we in edge-momentum mode for X
    if 'edge_active_y' not in mouse_state:
        mouse_state['edge_active_y'] = False  # Are we in edge-momentum mode for Y
    if 'last_time' not in mouse_state:
        mouse_state['last_time'] = time.time()
    if 'last_sent_time' not in mouse_state:
        mouse_state['last_sent_time'] = time.time()
    if 'last_dx' not in mouse_state:
        mouse_state['last_dx'] = 0
    if 'last_dy' not in mouse_state:
        mouse_state['last_dy'] = 0
    
    # Normalize input values to -1.0 to 1.0 range
    x = normalize_value(x)
    y = normalize_value(y)
    
    # Calculate timing info
    now = time.time()
    dt = now - mouse_state.get('last_time', now)
    mouse_state['last_time'] = now
    
    # Touch lifecycle handling
    if not mouse_state.get('touch_active', False) or mouse_state.get('first_input', True):
        # New touch starting - record position but don't move yet
        mouse_state['first_input'] = False
        mouse_state['touch_active'] = True
        mouse_state['start_x'] = x
        mouse_state['start_y'] = y
        mouse_state['prev_x'] = x
        mouse_state['prev_y'] = y
        
        # Don't clear momentum immediately - this preserves direction
        # between touches for better continuous movement
        logger.info(f"{player_id} Touchpad: New touch at ({x:.2f}, {y:.2f})")
        return
    
    # Calculate CHANGE in position from previous touch position
    dx_raw = x - mouse_state['prev_x']
    dy_raw = y - mouse_state['prev_y']
    
    # Update tracking for next time
    mouse_state['prev_x'] = x
    mouse_state['prev_y'] = y
    
    # Skip if the change is negligible (reduces jitter)
    if abs(dx_raw) < 0.0008 and abs(dy_raw) < 0.0008:
        # Preserve any existing edge momentum even when no movement
        if mouse_state.get('edge_active_x', False) or mouse_state.get('edge_active_y', False):
            _handle_edge_momentum(mouse_state, player_id)
        return
    
    # Very high sensitivity with dynamic scaling
    base_sensitivity = 150
    
    # Apply dynamic scaling for different movement sizes
    if abs(dx_raw) < 0.01:  # Very small movements (precision)
        dx_raw *= 1.5
    elif abs(dx_raw) > 0.08:  # Large movements
        dx_raw *= 2.2
    
    if abs(dy_raw) < 0.01:  # Very small movements (precision)
        dy_raw *= 1.5
    elif abs(dy_raw) > 0.08:  # Large movements
        dy_raw *= 2.2
    
    # Calculate pixel movement
    dx = int(dx_raw * base_sensitivity)
    dy = int(dy_raw * base_sensitivity)
    
    # Always ensure small intentional movements register
    if dx == 0 and abs(dx_raw) > 0.002:
        dx = 1 if dx_raw > 0 else -1
    if dy == 0 and abs(dy_raw) > 0.002:
        dy = 1 if dy_raw > 0 else -1
    
    # Edge detection - critical for continuous movement beyond boundaries
    # Detect when we're at edges of the touchpad
    edge_x = abs(x) > 0.9  # Near horizontal edge
    edge_y = abs(y) > 0.9  # Near vertical edge
    
    # Store momentum when we detect movement near the edges
    if edge_x and abs(dx) > 2:
        mouse_state['momentum_x'] = dx
        mouse_state['edge_active_x'] = True
        
    if edge_y and abs(dy) > 2:
        mouse_state['momentum_y'] = dy
        mouse_state['edge_active_y'] = True
    
    # Apply both regular movement and edge momentum
    final_dx = dx
    final_dy = dy
    
    # Track total movement (useful for debugging)
    mouse_state['total_dx'] += final_dx
    mouse_state['total_dy'] += final_dy
    
    # Store movement for next cycle
    mouse_state['last_dx'] = final_dx
    mouse_state['last_dy'] = final_dy
    mouse_state['last_sent_time'] = now
    
    # Ensure touchpad is marked active if we have movement
    if not mouse_state['is_touchpad_active'] and (abs(final_dx) > 0 or abs(final_dy) > 0):
        mouse_state['is_touchpad_active'] = True
        
    # Apply the movement
    if mouse_state['is_touchpad_active']:
        try:
            # Only player1 controls the mouse
            if player_id == 'player1':
                # Move mouse relative to current position
                mouse.move(final_dx, final_dy, absolute=False)
                
                # Log occasionally
                if random.random() < 0.005:  # Log 0.5% of movements
                    logger.info(f"{player_id} Mouse: dx={final_dx}, dy={final_dy}, " + 
                               f"at_edge=({edge_x}, {edge_y})")
        except Exception as e:
            logger.error(f"Mouse movement error for {player_id}: {str(e)}")
            
    # After regular movement, handle edge momentum - this allows continued 
    # movement even when at the edges of the touchpad
    if edge_x or edge_y:
        _handle_edge_momentum(mouse_state, player_id)

# Helper function to handle edge momentum
def _handle_edge_momentum(mouse_state, player_id):
    """Process edge momentum to allow movement beyond touchpad boundaries"""
    momentum_x = 0
    momentum_y = 0
    momentum_active = False
    
    # Apply momentum with decay for X axis
    if mouse_state.get('edge_active_x', False):
        # Get current momentum with decay factor
        decay = 0.95  # Gradual decrease in momentum (95% of previous value)
        momentum_x = int(mouse_state['momentum_x'] * decay)
        
        # Stop if momentum is too small
        if abs(momentum_x) < 2:
            mouse_state['edge_active_x'] = False
            momentum_x = 0
        else:
            mouse_state['momentum_x'] = momentum_x
            momentum_active = True
    
    # Apply momentum with decay for Y axis
    if mouse_state.get('edge_active_y', False):
        # Get current momentum with decay factor
        decay = 0.95  # Gradual decrease in momentum
        momentum_y = int(mouse_state['momentum_y'] * decay)
        
        # Stop if momentum is too small
        if abs(momentum_y) < 2:
            mouse_state['edge_active_y'] = False
            momentum_y = 0
        else:
            mouse_state['momentum_y'] = momentum_y
            momentum_active = True
    
    # Apply momentum if active
    if momentum_active and (momentum_x != 0 or momentum_y != 0):
        # Track total movement
        mouse_state['total_dx'] += momentum_x
        mouse_state['total_dy'] += momentum_y
        
        try:
            # Only player1 controls the mouse
            if player_id == 'player1':
                # Move mouse based on momentum
                mouse.move(momentum_x, momentum_y, absolute=False)
                
                # Log occasionally
                if random.random() < 0.02:  # Log 2% of momentum movements
                    logger.info(f"{player_id} Momentum: dx={momentum_x}, dy={momentum_y}")
        except Exception as e:
            logger.error(f"Mouse momentum error for {player_id}: {str(e)}")
            
        # Schedule next momentum update after a very short delay
        if momentum_active:
            threading.Timer(
                0.02,  # 20ms delay between momentum updates
                lambda: _handle_edge_momentum(mouse_state, player_id)
            ).start()

def handle_wait_command(command, player_id='player1'):
    """Handle a wait command"""
    try:
        # Extract milliseconds from WAIT_X command
        wait_ms = int(command.split("_")[1])
        # Sleep for the specified time
        time.sleep(wait_ms / 1000.0)
        logger.info(f"{player_id} waited for {wait_ms}ms")
        return True
    except (ValueError, IndexError) as e:
        logger.error(f"Invalid wait command from {player_id}: {command} - {str(e)}")
        return False

def handle_key_press(key, player_id='player1'):
    """Handle a directional key press with state tracking"""
    if player_id not in key_states:
        key_states[player_id] = {}
    
    # Mark this key as pressed in our state tracker
    key_states[player_id][key] = True
    logger.info(f"{player_id} Key press: {key}")
    
    # Press key (only player1 controls keyboard)
    if player_id == 'player1':
        try:
            keyboard.press(key.lower())
        except Exception as e:
            logger.error(f"Failed to press key {key}: {str(e)}")

def handle_key_release(key, player_id='player1'):
    """Handle a directional key release with state tracking"""
    if player_id not in key_states:
        key_states[player_id] = {}
    
    # Mark this key as released in our state tracker
    if key in key_states[player_id]:
        del key_states[player_id][key]
    
    logger.info(f"{player_id} Key release: {key}")
    
    # Release key (only player1 controls keyboard)
    if player_id == 'player1':
        try:
            keyboard.release(key.lower())
        except Exception as e:
            logger.error(f"Failed to release key {key}: {str(e)}")

def handle_button_press(command, player_id='player1'):
    """Handle various button commands with proper release handling"""
    if player_id not in mouse_states or player_id not in gamepads:
        logger.error(f"Unknown player ID: {player_id}")
        return False
        
    # Get the player's states
    mouse_state = mouse_states[player_id]
    gamepad = gamepads[player_id]
    
    # Handle key commands for the new reliable protocol
    if command.startswith("KEY_DOWN:"):
        key = command.split(":", 1)[1]
        handle_key_press(key, player_id)
        return True
        
    if command.startswith("KEY_UP:"):
        key = command.split(":", 1)[1]
        handle_key_release(key, player_id)
        return True
        
    if command.startswith("KEY_SYNC:"):
        key = command.split(":", 1)[1]
        # Just treat this as a key press to ensure it stays active
        handle_key_press(key, player_id)
        return True
    
    # Check if this is a wait command
    if command.startswith("WAIT_"):
        return handle_wait_command(command, player_id)
    
    # Process special commands
    if command == "MOUSE_LEFT_DOWN":
        try:
            # Only player1 controls the mouse to avoid conflicts
            if player_id == 'player1':
                mouse.press(button="left")
                mouse_state['left_down'] = True
                logger.info(f"{player_id} Mouse left button pressed")
        except Exception as e:
            logger.error(f"Failed to press mouse button for {player_id}: {str(e)}")
        return True
    
    if command == "MOUSE_LEFT_UP":
        try:
            # Only player1 controls the mouse to avoid conflicts
            if player_id == 'player1':
                mouse.release(button="left")
                mouse_state['left_down'] = False
                logger.info(f"{player_id} Mouse left button released")
        except Exception as e:
            logger.error(f"Failed to release mouse button for {player_id}: {str(e)}")
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
            logger.info(f"{player_id} Xbox button explicitly released: {command}")
        except Exception as e:
            logger.error(f"Failed to release Xbox button for {player_id}: {str(e)}")
        return True
    
    # Check if it's an Xbox button press
    if command in xbox_buttons:
        try:
            # Press the button
            gamepad.press_button(button=xbox_buttons[command])
            gamepad.update()
            logger.info(f"{player_id} Xbox button pressed: {command}")
            
            # Only auto-release if not a HOLD command
            if not command.endswith("_HOLD"):
                # Schedule the button release after a short delay (100ms)
                release_timer = threading.Timer(
                    0.1, 
                    lambda: release_xbox_button(xbox_buttons[command], player_id)
                )
                release_timer.daemon = True
                release_timer.start()
                logger.info(f"Scheduled auto-release for {player_id} {command}")
            else:
                logger.info(f"Hold mode - no auto-release for {player_id} {command}")
        except Exception as e:
            logger.error(f"Failed to press Xbox button for {player_id}: {str(e)}")
        return True
    
    # Handle keyboard input (common keys)
    try:
        # Only player1 controls the keyboard to avoid conflicts
        if player_id == 'player1':
            # For regular keyboard presses (not through key state system)
            keyboard.press_and_release(command.lower())
            logger.info(f"{player_id} Keyboard key pressed: {command}")
        return True
    except Exception as e:
        logger.error(f"Failed to process command for {player_id}: {command} - {str(e)}")
        return False

def process_timed_sequence(commands, player_id='player1'):
    """Process a sequence of commands with timing delays"""
    try:
        for cmd in commands:
            cmd = cmd.strip()
            if cmd:  # Skip empty commands
                # Process command and wait for completion
                handle_button_press(cmd, player_id)
    except Exception as e:
        logger.error(f"Error in timed sequence for {player_id}: {str(e)}")

def process_command(data, addr, player_id='player1'):
    """Process incoming command from the Android app"""
    data = data.strip()
    log_data = data
    
    # Reduce log noise by not logging every touchpad movement
    if "TOUCHPAD:" in data or "POS:" in data:
        log_data = f"{data.split(':')[0]}:coordinate_data"
    
    logger.info(f"Received from {addr} ({player_id}): {log_data}")
    
    # Create or update connection record
    addr_key = f"{addr[0]}:{addr[1]}"
    if addr_key not in active_connections:
        active_connections[addr_key] = {
            'player_id': player_id,
            'addr': addr,
            'last_seen': time.time()
        }
    else:
        active_connections[addr_key]['last_seen'] = time.time()
    
    # Handle heartbeat messages
    if data == "PING":
        return "PONG"
        
    # Handle touchpad touch up/down events
    if data == "TOUCHPAD_DOWN":
        if player_id in mouse_states:
            # Mark the touch as active but preserve momentum
            mouse_states[player_id]['touch_active'] = True
            # Set first_input flag to trigger initialization on next position update
            mouse_states[player_id]['first_input'] = True
            logger.info(f"{player_id} Touchpad: finger down")
        return None
        
    if data == "TOUCHPAD_UP":
        if player_id in mouse_states:
            # Mark touch as inactive but preserve momentum and direction
            mouse_states[player_id]['touch_active'] = False
            logger.info(f"{player_id} Touchpad: finger up")
        return None
    
    try:
        # Handle connection and registration messages
        if data.startswith("CONNECT:"):
            try:
                requested_id = data.split(":", 1)[1].strip()
                if requested_id in ['player1', 'player2']:
                    player_id = requested_id
                    active_connections[addr_key]['player_id'] = player_id
                    logger.info(f"Client {addr} connected as {player_id}")
                    return f"CONNECTED:{player_id}"
                else:
                    logger.warning(f"Invalid player ID in connection request: {requested_id}")
                    return "ERROR:invalid_player_id"
            except Exception as e:
                logger.error(f"Error processing connection request: {e}")
                return "ERROR:connection_failed"
                
        # Handle player ID prefix in command
        if data.startswith("REGISTER:"):
            try:
                requested_id = data.split(":", 1)[1].strip()
                if requested_id in ['player1', 'player2']:
                    player_id = requested_id
                    active_connections[addr_key]['player_id'] = player_id
                    logger.info(f"Client {addr} registered as {player_id}")
                    return f"REGISTERED:{player_id}"
                else:
                    logger.warning(f"Invalid player ID request: {requested_id}")
                    return "ERROR:invalid_player_id"
            except Exception as e:
                logger.error(f"Error processing registration: {e}")
                return "ERROR:registration_failed"
        
        # Handle player ID prefix in command
        if ":" in data and data.split(":", 1)[0] in ['player1', 'player2']:
            player_id, data = data.split(":", 1)
            active_connections[addr_key]['player_id'] = player_id
        
        # Handle key state tracking commands
        if data.startswith("KEY_DOWN:"):
            key = data.split(":", 1)[1]
            handle_key_press(key, player_id)
            return None
            
        if data.startswith("KEY_UP:"):
            key = data.split(":", 1)[1]
            handle_key_release(key, player_id)
            return None
            
        if data.startswith("KEY_SYNC:"):
            key = data.split(":", 1)[1]
            handle_key_press(key, player_id)
            return None
        
        # Handle trigger input - both original and shortened syntax
        if data.startswith("TRIGGER_L:"):
            value = data.split(":", 1)[1]
            handle_trigger_input(value, "LEFT", player_id)
            return None
            
        if data.startswith("TRIGGER_R:"):
            value = data.split(":", 1)[1]
            handle_trigger_input(value, "RIGHT", player_id)
            return None
            
        # Shortened trigger syntax
        if data.startswith("LT:"):
            value = data.split(":", 1)[1]
            handle_trigger_input(value, "LEFT", player_id)
            return None
            
        if data.startswith("RT:"):
            value = data.split(":", 1)[1]
            handle_trigger_input(value, "RIGHT", player_id)
            return None
        
        # Check for individual wait command
        if data.startswith("WAIT_"):
            handle_wait_command(data, player_id)
            return None
        
        # Shortened stick position shortcuts
        if data == "LS_UP":
            handle_stick_input("0.0", "1.0", "LEFT", player_id)
            return None
        elif data == "LS_DOWN":
            handle_stick_input("0.0", "-1.0", "LEFT", player_id)
            return None
        elif data == "LS_LEFT":
            handle_stick_input("-1.0", "0.0", "LEFT", player_id)
            return None
        elif data == "LS_RIGHT":
            handle_stick_input("1.0", "0.0", "LEFT", player_id)
            return None
        elif data == "RS_UP":
            handle_stick_input("0.0", "1.0", "RIGHT", player_id)
            return None
        elif data == "RS_DOWN":
            handle_stick_input("0.0", "-1.0", "RIGHT", player_id)
            return None
        elif data == "RS_LEFT":
            handle_stick_input("-1.0", "0.0", "RIGHT", player_id)
            return None
        elif data == "RS_RIGHT":
            handle_stick_input("1.0", "0.0", "RIGHT", player_id)
            return None
        
        # Handle stick/touchpad input (format: "STICK:x,y" or "TOUCHPAD:x,y")
        if ":" in data:
            command, coords = data.split(":", 1)
            if "," in coords:
                x, y = coords.split(",", 1)
                if command == "STICK" or command == "STICK_L" or command == "LS":
                    handle_stick_input(x, y, "LEFT", player_id)
                elif command == "STICK_R" or command == "RS":
                    handle_stick_input(x, y, "RIGHT", player_id)
                elif command == "TOUCHPAD":
                    try:
                        handle_touchpad_input(x, y, player_id)
                    except Exception as e:
                        logger.error(f"Error in touchpad handling: {str(e)}")
                elif command == "POS":  # Position data coming through UDP
                    # Handle generic position data (used by optimized clients)
                    try:
                        handle_touchpad_input(x, y, player_id)
                    except Exception as e:
                        logger.error(f"Error in position handling: {str(e)}")
                else:
                    logger.warning(f"Unknown coordinate command from {player_id}: {command}")
                return None
            else:
                logger.warning(f"Invalid coordinate format from {player_id}: {coords}")
                return None
        
        # Handle commands with commas (format: "W,SHIFT" or "A,WAIT_500,B")
        elif "," in data:
            commands = data.split(",")
            
            # Process each command in sequence
            threading.Thread(
                target=process_timed_sequence, 
                args=(commands, player_id), 
                daemon=True
            ).start()
            logger.info(f"Started command sequence with {len(commands)} commands for {player_id}")
            return None
        
        # Handle simple button commands
        else:
            handle_button_press(data, player_id)
            return None
    except Exception as e:
        logger.error(f"Error processing command '{data}' from {player_id}: {str(e)}")
        return f"ERROR:{str(e)}"

def clean_inactive_connections():
    """Remove connections that haven't sent data in a while"""
    now = time.time()
    timeout = 30  # 30 seconds timeout
    
    to_remove = []
    for addr_key, conn in active_connections.items():
        if now - conn['last_seen'] > timeout:
            to_remove.append(addr_key)
    
    for addr_key in to_remove:
        logger.info(f"Removing inactive connection: {addr_key} ({active_connections[addr_key]['player_id']})")
        del active_connections[addr_key]

def udp_server():
    """Start UDP server to receive commands"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        
        # Apply UDP-specific low-latency settings with error handling
        try:
            # Set buffer sizes for UDP socket
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
            logger.info("Applied UDP buffer size settings")
        except (socket.error, OSError) as e:
            logger.warning(f"Could not set UDP socket buffer sizes: {str(e)}")
        
        sock.bind((HOST, PORT))
        logger.info(f"UDP server started on {HOST}:{PORT}")
        
        # Set up a housekeeping timer for cleaning inactive connections
        housekeeping_timer = threading.Timer(10.0, clean_inactive_connections)
        housekeeping_timer.daemon = True
        housekeeping_timer.start()
        
        while True:
            try:
                # Receive data from client
                data, addr = sock.recvfrom(1024)
                decoded_data = data.decode('utf-8').strip()
                
                # Determine player ID - either from stored connection or default to player1
                addr_key = f"{addr[0]}:{addr[1]}"
                player_id = active_connections.get(addr_key, {}).get('player_id', 'player1')
                
                # Process command and get any response
                response = process_command(decoded_data, addr, player_id)
                
               # Send response if there is one
                if response:
                    sock.sendto(response.encode('utf-8'), addr)
                
            except Exception as e:
                logger.error(f"Error handling UDP packet: {str(e)}")
                
    except Exception as e:
        logger.error(f"UDP server error: {str(e)}")
    finally:
        sock.close()
        logger.info("UDP server stopped")

def clean_key_states():
    """Clean up any inconsistent keyboard states"""
    try:
        # For player1 only (since they control the keyboard)
        if 'player1' in key_states:
            # Check that all keys marked as pressed are actually pressed
            for key in list(key_states['player1'].keys()):
                try:
                    # If key is not actually pressed according to the keyboard library
                    if not keyboard.is_pressed(key.lower()):
                        # Re-press it to ensure it's active
                        keyboard.press(key.lower())
                        logger.info(f"Re-pressed key: {key}")
                except Exception as e:
                    logger.warning(f"Error checking key state: {key} - {str(e)}")
    except Exception as e:
        logger.error(f"Error in key state cleanup: {str(e)}")

def start_cleanup_scheduler():
    """Schedule regular cleaning of inactive connections and key states"""
    def scheduled_cleanup():
        while True:
            time.sleep(10)  # Run every 10 seconds
            clean_inactive_connections()
            clean_key_states()  # Also check key states periodically
            
    cleanup_thread = threading.Thread(target=scheduled_cleanup)
    cleanup_thread.daemon = True
    cleanup_thread.start()

if __name__ == "__main__":
    logger.info("UDP Controller Server starting up...")
    logger.info("Press Ctrl+C to exit")
    
    try:
        # Start the connection cleanup scheduler
        start_cleanup_scheduler()
        
        # Start the UDP server
        udp_server()
    except KeyboardInterrupt:
        logger.info("Server stopping...")
    except Exception as e:
        logger.error(f"Unexpected error: {str(e)}")
    finally:
        logger.info("Server stopped")