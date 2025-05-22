#!/usr/bin/env python3
"""
Complete Controller Server

A server that handles both touchpad/mouse and gamepad controls.
Combines stability-focused mouse handling with full gamepad support.

Usage: python merged_controller.py
"""

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
import os
import pyautogui
from datetime import datetime
from collections import deque

# Configure pyautogui for mouse handling
pyautogui.FAILSAFE = False   # disable the top-left "panic" feature
pyautogui.PAUSE = 0          # remove PyAutoGUI's default 0.1 s pause

DELTA_GAIN = 40.0         # 40 px per 1.0 delta feels close to Windows default

# Directory for logs
LOG_DIR = "touchpad_logs"
os.makedirs(LOG_DIR, exist_ok=True)

# Set up logging
timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
log_file = os.path.join(LOG_DIR, f"controller_server_{timestamp}.log")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(log_file),
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

class StabilitySmoother:
    """Mouse movement smoother focused on stability over responsiveness"""
    def __init__(self):
        # Longer buffer for stronger smoothing
        self.buffer_size = 5
        self.x_buffer = deque([0.0] * self.buffer_size, maxlen=self.buffer_size)
        self.y_buffer = deque([0.0] * self.buffer_size, maxlen=self.buffer_size)
        
        # State tracking
        self.prev_x = 0.0
        self.prev_y = 0.0
        self.last_dx = 0.0
        self.last_dy = 0.0
        self.last_time = time.time()
        self.frame_count = 0
        self.touch_active = False
        
        # Adjustable parameters - set conservative defaults
        self.smoothing_factor = 0.7    # Higher = more smoothing (0.0-1.0)
        self.sensitivity = 50.0        # Lower = less jumpy (30-150)
        self.deadzone = 0.01           # Ignore movements smaller than this
        self.max_speed = 15.0          # Maximum pixels to move in one update
        
    def reset(self):
        """Reset the smoother state for a new touch sequence"""
        self.x_buffer = deque([0.0] * self.buffer_size, maxlen=self.buffer_size)
        self.y_buffer = deque([0.0] * self.buffer_size, maxlen=self.buffer_size)
        self.last_dx = 0.0
        self.last_dy = 0.0
        self.frame_count = 0
        self.last_time = time.time()
        self.touch_active = True
        logger.info("Smoother reset for new touch")
        
    def end_touch(self):
        """End the current touch session"""
        self.touch_active = False
        logger.info("Touch ended")
        
    def is_active(self):
        """Check if touch is currently active"""
        return self.touch_active
        
    def update_position(self, x, y):
        """Update the current raw position"""
        self.prev_x = x
        self.prev_y = y
        
    def process_movement(self, x, y):
        """
        Process a movement update with stability focus
        
        Args:
            x, y: Current normalized position (-1.0 to 1.0)
            
        Returns:
            tuple: Final (dx, dy) in pixels for mouse movement
        """
        # Skip processing if touch not active
        if not self.touch_active:
            self.prev_x = x
            self.prev_y = y
            return (0, 0)
            
        # Calculate time delta
        now = time.time()
        dt = now - self.last_time
        self.last_time = now
        
        # Increment frame counter
        self.frame_count += 1
        
        # Calculate raw delta
        delta_x = x - self.prev_x
        delta_y = y - self.prev_y
        
        # Update stored position
        self.prev_x = x
        self.prev_y = y
        
        # Skip tiny movements (deadzone)
        if abs(delta_x) < self.deadzone and abs(delta_y) < self.deadzone:
            return (0, 0)
            
        # Add to smoothing buffer
        self.x_buffer.append(delta_x)
        self.y_buffer.append(delta_y)
        
        # Determine smoothing factor based on context
        # More smoothing for first few frames to eliminate initial jump
        effective_smoothing = self.smoothing_factor
        if self.frame_count < 5:
            effective_smoothing = min(0.9, self.smoothing_factor + 0.2)
            
        # More smoothing for very rapid updates (potential jitter)
        if dt < 0.010:  # Less than 10ms
            effective_smoothing = min(0.9, effective_smoothing + 0.1)
            
        # Apply exponential smoothing
        smoothed_dx = delta_x * (1 - effective_smoothing) + self.last_dx * effective_smoothing
        smoothed_dy = delta_y * (1 - effective_smoothing) + self.last_dy * effective_smoothing
        
        # Store for next iteration
        self.last_dx = smoothed_dx
        self.last_dy = smoothed_dy
        
        # Linear sensitivity with no boost for small movements
        # This is more predictable and less jumpy
        scaled_dx = smoothed_dx * self.sensitivity
        scaled_dy = smoothed_dy * self.sensitivity
        
        # Apply speed limiting to prevent large jumps
        if abs(scaled_dx) > self.max_speed:
            scaled_dx = self.max_speed if scaled_dx > 0 else -self.max_speed
            
        if abs(scaled_dy) > self.max_speed:
            scaled_dy = self.max_speed if scaled_dy > 0 else -self.max_speed
        
        # Convert to integers for mouse movement
        final_dx = int(scaled_dx)
        final_dy = int(scaled_dy)
        
        # Ensure small intentional movements aren't lost
        if abs(smoothed_dx) > self.deadzone*2 and final_dx == 0:
            final_dx = 1 if smoothed_dx > 0 else -1
            
        if abs(smoothed_dy) > self.deadzone*2 and final_dy == 0:
            final_dy = 1 if smoothed_dy > 0 else -1
            
        # Log large movements for analysis
        if abs(final_dx) > 10 or abs(final_dy) > 10:
            logger.warning(f"Large movement: dx={final_dx}, dy={final_dy}, raw=({delta_x:.3f}, {delta_y:.3f})")
            
        return (final_dx, final_dy)

# Global state
smoother = StabilitySmoother()

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
    """Convert string or float to normalized float (-1.0 to 1.0)"""
    try:
        return max(-1.0, min(1.0, float(value)))
    except (ValueError, TypeError):
        logger.error(f"Failed to convert value to float: {value}")
        return 0.0

def parse_float(value):
    """Parse a float without clamping, returning 0.0 on error"""
    try:
        return float(value)
    except (ValueError, TypeError):
        logger.error(f"Failed to convert value: {value}")
        return 0.0

def handle_touchpad(command):
    """Handle touchpad input with stability smoother from first file"""
    # ---------- quick DELTA path ----------
    if command.startswith("DELTA:"):
        try:
            dx, dy = map(float, command[6:].split(",", 1))
            mx = int(dx * DELTA_GAIN)
            my = int(dy * DELTA_GAIN)
            print("HDL", dx, dy, "⇒", mx, my)      # DEBUG
            # comment-out the line you're NOT using:
            # mouse.move(mx, my, absolute=False)   # needs admin
            pyautogui.moveRel(mx, my)              # works without admin
        except ValueError:
            logger.warning(f"Bad DELTA packet: {command}")
        return                                     # ← don't let it fall through
    # ---------------------------------------

    # From here down you're dealing with absolute-position packets
    # (TOUCHPAD: / POS:) if you ever decide to keep them.
    try:
        _, coords = command.split(":", 1)        # coords is now defined
        x, y = coords.split(",", 1)
        x_val = normalize_value(x)
        y_val = normalize_value(y)
        dx, dy = smoother.process_movement(x_val, y_val)
        if dx or dy:
            pyautogui.moveRel(dx, dy) 
    except Exception as e:
        logger.error(f"Error handling touchpad input: {e}")

def handle_mouse_buttons(command):
    """Handle mouse button commands from first file"""
    try:
        if command == "MOUSE_LEFT_DOWN":
            pyautogui.mouseDown(button="left")   # press
        elif command == "MOUSE_LEFT_UP":
            pyautogui.mouseUp(button="left")     # release
        elif command in ("TOUCHPAD_END", "TOUCH_END"):
            smoother.end_touch()
        elif command == "MOUSE_RESET":
            logger.warning("MOUSE_RESET ignored to prevent jumps")
    except Exception as e:
        logger.error(f"Error handling button command: {e}")

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

    # ➊ Ignore keep-alive packets outright
    if key == "SYNC":          # the key that comes after KEY_SYNC:
        return                 # ← nothing to do

    if player_id not in key_states:
        key_states[player_id] = {}

    key_states[player_id][key] = True
    logger.info(f"{player_id} Key press: {key}")

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

    # ── 1️⃣ Ignore keep-alive packets completely ─────────────────────
    if command.startswith("KEY_SYNC:"):
        return True            # do nothing, report handled
    # ----------------------------------------------------------------

    mouse_state = mouse_states[player_id]
    gamepad = gamepads[player_id]

    # ----- key commands (reliable protocol) -----
    if command.startswith("KEY_DOWN:"):
        key = command.split(":", 1)[1]
        handle_key_press(key, player_id)
        return True

    if command.startswith("KEY_UP:"):
        key = command.split(":", 1)[1]
        handle_key_release(key, player_id)
        return True

 # ➋  KEY_SYNC branch removed – we now ignore these packets
    # ----------------------------------------------
    
    
    # Check if this is a wait command
    if command.startswith("WAIT_"):
        return handle_wait_command(command, player_id)
    
    # Process special commands - Use mouse button handling from first file
    if command == "MOUSE_LEFT_DOWN":
        handle_mouse_buttons(command)
        if player_id in mouse_states:
            mouse_states[player_id]['left_down'] = True
        return True
    
    if command == "MOUSE_LEFT_UP":
        handle_mouse_buttons(command)
        if player_id in mouse_states:
            mouse_states[player_id]['left_down'] = False
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
    if not data:
        return

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

    # 1️⃣  Strip the optional player prefix FIRST
    if data.startswith(("player1:", "player2:")):
        player_id, data = data.split(":", 1)       # now data begins with DELTA:/TOUCHPAD:/POS:
        active_connections[addr_key]['player_id'] = player_id
        
         # --- LOG LEFT-CLICK PACKETS ---------------------------------
    if data.startswith("MOUSE_LEFT"):
        print("BTN", data)            # << add this line
    # ------------------------------------------------------------

    # 2️⃣  Route any movement packet straight to handle_touchpad()
    if data.startswith(("DELTA:", "TOUCHPAD:", "POS:")):
        handle_touchpad(data)
        return                             # ← prevent duplicate work later

    # 3️⃣  Everything below is for buttons / ping / register
    if data in ["MOUSE_LEFT_DOWN", "MOUSE_LEFT_UP",
                "TOUCHPAD_END", "TOUCH_END", "MOUSE_RESET"]:
        handle_mouse_buttons(data)
        return

    # Handle heartbeat messages
    if data == "PING":
        return "PONG"
    
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

    # ─── Ignore keep-alive packets so taps don't fire twice ───
    if data.startswith("KEY_SYNC:"):
        return None
    # ─────────────────────────────────────────────────────────

    
    # Handle key state tracking commands
    if data.startswith("KEY_DOWN:"):
        key = data.split(":", 1)[1]
        handle_key_press(key, player_id)
        return None
        
    if data.startswith("KEY_UP:"):
        key = data.split(":", 1)[1]
        handle_key_release(key, player_id)
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
                handle_touchpad(data)  # Use the stability smoother version
            elif command == "POS":  # Position data coming through UDP
                # Handle generic position data (used by optimized clients)
                handle_touchpad(data)  # Use the stability smoother version
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

    # Anything else just gets logged
    logger.info(f"Command from {addr}: {data}")

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

def udp_server():
    """Run a UDP server for touchpad controls"""
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
                data, addr = sock.recvfrom(1024)
                
                try:
                    decoded_data = data.decode('utf-8').strip()
                    
                    # Determine player ID - either from stored connection or default to player1
                    addr_key = f"{addr[0]}:{addr[1]}"
                    player_id = active_connections.get(addr_key, {}).get('player_id', 'player1')
                    
                    response = process_command(decoded_data, addr, player_id)
                    
                    if response:
                        sock.sendto(response.encode('utf-8'), addr)
                        
                except UnicodeDecodeError:
                    logger.warning(f"Received invalid data from {addr}")
                    
            except Exception as e:
                logger.error(f"Error in UDP server: {e}")
                
    except Exception as e:
        logger.error(f"Fatal error in UDP server: {e}")
    finally:
        sock.close()
        logger.info("UDP server stopped")

if __name__ == "__main__":
    logger.info("=== Complete Controller Server ===")
    logger.info("UDP Controller Server starting up...")
    logger.info(f"Listening on {HOST}:{PORT}")
    logger.info(f"Log file: {log_file}")
    logger.info("Press Ctrl+C to exit")
    
    try:
        # Start the connection cleanup scheduler
        start_cleanup_scheduler()
        
        # Start the UDP server
        udp_server()
        
    except KeyboardInterrupt:
        print("\nServer shutting down...")
        logger.info("Server stopping...")
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
    finally:
        print("Server stopped")
        logger.info("Server stopped")
        
    input("Press Enter to exit...")