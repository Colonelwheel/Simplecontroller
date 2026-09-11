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
import ipaddress
import pyautogui
from datetime import datetime
from collections import deque
from camera_follow import CameraFollowSettings, CameraFollowState

# Configure pyautogui for mouse handling
pyautogui.FAILSAFE = False   # disable the top-left "panic" feature
pyautogui.PAUSE = 0          # remove PyAutoGUI's default 0.1 s pause

DELTA_GAIN = 40.0         # 40 px per 1.0 delta feels close to Windows default

# Directory for logs
# Use AppData instead of the install folder, because Program Files is not writable for normal users.
LOCALAPPDATA = os.environ.get("LOCALAPPDATA", os.getcwd())
LOG_DIR = os.path.join(LOCALAPPDATA, "SimpleControllerReceiver", "touchpad_logs")
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
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 262144)
            logger.info("Applied buffer size settings")
        except (socket.error, OSError) as e:
            logger.warning(f"Could not set socket buffer sizes: {str(e)}")
        
        logger.info("Low-latency socket configuration applied (with platform compatibility)")
    except Exception as e:
        logger.error(f"Failed to apply low-latency socket configuration: {str(e)}")
        logger.info("Continuing with default socket settings")

# Server configuration
HOST = '0.0.0.0'  # Listen on all interfaces
PORT = 42734       # Port used in your Android app's NetworkClient.kt

# Camera Follow settings (normalized stick values use -1.0 through 1.0).
CAMERA_FOLLOW_SETTINGS = CameraFollowSettings(
    enabled_by_default=False,
    movement_deadzone=0.20,
    manual_rs_deadzone=0.15,
    activation_delay_ms=200,
    manual_override_timeout_ms=750,
    strength=0.55,
    maximum_output=0.45,
    ramp_in_ms=180,
    ramp_out_ms=120,
    invert_direction=False,
    allow_sideways_steering=True,
)
CAMERA_FOLLOW_UPDATE_INTERVAL_SECONDS = 0.01
# NetworkClient heartbeats every 2 seconds; this clears assistance after missed input.
CAMERA_FOLLOW_INPUT_LOSS_TIMEOUT_SECONDS = 5.0


def get_lan_ip_candidates():
    """
    Return the most likely LAN IP address plus other possible local IPv4 addresses.

    The primary method uses a UDP socket to ask Windows which local adapter/IP it
    would use for outbound traffic. It does not need the receiver to connect to
    Google for normal operation; it is just a reliable local routing lookup.
    """
    candidates = []

    def add_candidate(ip):
        try:
            parsed = ipaddress.ip_address(ip)
        except ValueError:
            return

        # Skip addresses that phones cannot use to reach this PC.
        if parsed.version != 4:
            return
        if parsed.is_loopback:
            return
        if ip.startswith("169.254."):
            return

        if ip not in candidates:
            candidates.append(ip)

    # Best guess: ask the OS which local IP would be used for outbound traffic.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            add_candidate(s.getsockname()[0])
    except Exception as e:
        logger.warning(f"Could not detect primary LAN IP: {e}")

    # Backup: collect other IPv4s attached to the hostname.
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            add_candidate(info[4][0])
    except Exception as e:
        logger.warning(f"Could not list backup LAN IPs: {e}")

    return candidates


def show_connection_info():
    """Print the IP/port the user should enter in the Android app."""
    ip_candidates = get_lan_ip_candidates()
    primary_ip = ip_candidates[0] if ip_candidates else "Unable to detect IP"

    print()
    print("========================================", flush=True)
    print(" Simple Controller Receiver is running", flush=True)
    print("========================================", flush=True)
    print()
    print("Put this into the Android app:", flush=True)
    print()
    print(f"IP Address: {primary_ip}", flush=True)
    print(f"Port:       {PORT}", flush=True)

    if len(ip_candidates) > 1:
        print()
        print("If that IP does not connect, try one of these:", flush=True)
        for ip in ip_candidates[1:]:
            print(f"  {ip}", flush=True)

    print()
    print("Phone and PC must be on the same Wi-Fi/network.", flush=True)
    print("If it does not connect, allow this app through Windows Firewall.", flush=True)
    print("========================================", flush=True)
    print()

    logger.info(f"Suggested Android app IP: {primary_ip}")
    logger.info(f"Suggested Android app port: {PORT}")
    if len(ip_candidates) > 1:
        logger.info(f"Other possible local IPs: {', '.join(ip_candidates[1:])}")

# Create virtual Xbox 360 controllers - one for each player
gamepads = {
    'player1': vgamepad.VX360Gamepad(),
    'player2': vgamepad.VX360Gamepad()
}

camera_follow_states = {
    player_id: CameraFollowState(CAMERA_FOLLOW_SETTINGS)
    for player_id in gamepads
}
camera_follow_last_right_output = {
    player_id: (0.0, 0.0, "neutral")
    for player_id in gamepads
}
camera_follow_output_locks = {
    player_id: threading.RLock()
    for player_id in gamepads
}
camera_follow_stop_event = threading.Event()
camera_follow_thread = None
camera_follow_last_input_at = {
    player_id: 0.0
    for player_id in gamepads
}
camera_follow_input_lost = {
    player_id: False
    for player_id in gamepads
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

# Track newest stick packet received for each player/stick.
# This prevents old UDP packets from overriding newer positions.
latest_stick_packets = {}

# RELEASE_ALL uses the Android process session/sequence as a barrier. Any older
# packet that arrives afterward is ignored instead of reactivating an output.
release_barriers = {}
release_state_lock = threading.RLock()
release_generations = {player_id: 0 for player_id in gamepads}
pending_button_release_timers = {player_id: set() for player_id in gamepads}

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

def output_packet_is_current(session_id, sequence):
    """Return False only when a packet predates this session's release barrier."""
    with release_state_lock:
        return sequence > release_barriers.get(session_id, -1)

def current_release_generation(player_id):
    with release_state_lock:
        return release_generations.get(player_id, 0)

def release_outputs(player_ids, reason="explicit RELEASE_ALL"):
    """Neutralize receiver-owned outputs after an explicit RELEASE_ALL payload."""
    selected = tuple(player_id for player_id in player_ids if player_id in gamepads)
    if not selected:
        return

    with release_state_lock:
        timers = []
        keys_to_release = set()
        for player_id in selected:
            release_generations[player_id] = release_generations.get(player_id, 0) + 1
            timers.extend(pending_button_release_timers.setdefault(player_id, set()))
            pending_button_release_timers[player_id].clear()
            keys_to_release.update(key_states.setdefault(player_id, {}).keys())
            key_states[player_id].clear()
            button_states.setdefault(player_id, {}).clear()
            mouse_states.setdefault(
                player_id,
                {'left_down': False, 'is_touchpad_active': False}
            ).update(left_down=False, is_touchpad_active=False)

    for timer in timers:
        timer.cancel()

    for key in keys_to_release:
        try:
            keyboard.release(key.lower())
        except Exception as e:
            logger.warning(f"Failed to release key {key}: {e}")

    # Mouse output is shared by Windows, so idempotently release every supported button.
    for mouse_button in ("left", "right", "middle"):
        try:
            pyautogui.mouseUp(button=mouse_button)
        except Exception as e:
            logger.warning(f"Failed to release mouse button {mouse_button}: {e}")

    smoother.end_touch()

    for player_id in selected:
        camera_follow_last_input_at[player_id] = 0.0
        camera_follow_input_lost[player_id] = True
        try:
            with camera_follow_output_locks[player_id]:
                # Keep Camera Follow calculation and virtual-pad reset atomic so a
                # value calculated just before RELEASE_ALL cannot land afterward.
                camera_follow_states[player_id].clear_inputs()
                gamepad = gamepads[player_id]
                gamepad.reset()
                gamepad.update()
                camera_follow_last_right_output[player_id] = (0.0, 0.0, "neutral")
        except Exception as e:
            logger.error(f"Failed to reset virtual gamepad for {player_id}: {e}")

    logger.info(f"Released all outputs for {', '.join(selected)} ({reason})")

def release_all_outputs(session_id, sequence):
    """Apply each global release identity once; duplicates still receive an ACK."""
    with release_state_lock:
        if sequence <= release_barriers.get(session_id, -1):
            return False
        release_barriers[session_id] = sequence

    release_outputs(tuple(gamepads), reason="RELEASE_ALL")
    return True

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
    try:
        if command == "MOUSE_LEFT_DOWN":
            pyautogui.mouseDown(button="left")
        elif command == "MOUSE_LEFT_UP":
            pyautogui.mouseUp(button="left")

        elif command == "MOUSE_RIGHT_DOWN":          # NEW
            pyautogui.mouseDown(button="right")
        elif command == "MOUSE_RIGHT_UP":            # NEW
            pyautogui.mouseUp(button="right")
            
        elif command == "MOUSE_MIDDLE_DOWN":
            pyautogui.mouseDown(button="middle")
        elif command == "MOUSE_MIDDLE_UP":
            pyautogui.mouseUp(button="middle")
            
        elif command in ("TOUCHPAD_END", "TOUCH_END"):
            smoother.end_touch()
        elif command == "MOUSE_RESET":
            logger.warning("MOUSE_RESET ignored to prevent jumps")
    except Exception as e:
        logger.error(f"Error handling button command: {e}")
        
def handle_scroll(command):
    # SCROLL:+/-n   → one notch ≈ 120 units on Windows
    try:
        amount = int(float(command.split(":",1)[1]) * 120)
        pyautogui.scroll(amount)
    except Exception as e:
        logger.error(f"Bad SCROLL packet {command}: {e}")        

def apply_camera_follow_output(player_id, now=None, force=False):
    """Apply the highest-priority RS source to the virtual controller."""
    if player_id not in gamepads or player_id not in camera_follow_states:
        return

    try:
        with camera_follow_output_locks[player_id]:
            x, y, source = camera_follow_states[player_id].step(now)
            previous_x, previous_y, previous_source = camera_follow_last_right_output[player_id]
            changed = (
                abs(x - previous_x) > 0.0001
                or abs(y - previous_y) > 0.0001
                or source != previous_source
            )
            if not force and not changed:
                return

            gamepad = gamepads[player_id]
            gamepad.right_joystick_float(x_value_float=x, y_value_float=-y)
            gamepad.update()
            camera_follow_last_right_output[player_id] = (x, y, source)
    except Exception as e:
        logger.error(f"Failed to apply right-stick output for {player_id}: {e}")

def set_camera_follow_enabled(player_id, enabled):
    """Set an explicit Camera Follow state; duplicate commands are idempotent."""
    if player_id not in camera_follow_states:
        logger.error(f"Unknown player ID for Camera Follow: {player_id}")
        return

    changed = camera_follow_states[player_id].set_enabled(enabled)
    if changed:
        logger.info(f"Camera Follow {'enabled' if enabled else 'disabled'}")
        apply_camera_follow_output(player_id, force=True)

def clear_camera_follow_player(player_id):
    """Clear generated/manual/macro RS state after input loss or disconnect."""
    if player_id not in camera_follow_states:
        return
    with camera_follow_output_locks[player_id]:
        camera_follow_states[player_id].clear_inputs()
        apply_camera_follow_output(player_id, force=True)

def camera_follow_worker():
    while not camera_follow_stop_event.wait(CAMERA_FOLLOW_UPDATE_INTERVAL_SECONDS):
        now = time.monotonic()
        for player_id in tuple(camera_follow_states):
            last_input_at = camera_follow_last_input_at[player_id]
            input_timed_out = (
                last_input_at > 0.0
                and now - last_input_at > CAMERA_FOLLOW_INPUT_LOSS_TIMEOUT_SECONDS
            )
            if input_timed_out:
                if not camera_follow_input_lost[player_id]:
                    camera_follow_input_lost[player_id] = True
                    clear_camera_follow_player(player_id)
                continue
            apply_camera_follow_output(player_id)

def start_camera_follow_worker():
    global camera_follow_thread
    camera_follow_stop_event.clear()
    camera_follow_thread = threading.Thread(
        target=camera_follow_worker, name="CameraFollow", daemon=True
    )
    camera_follow_thread.start()

def stop_camera_follow_worker():
    camera_follow_stop_event.set()
    if camera_follow_thread is not None and camera_follow_thread.is_alive():
        camera_follow_thread.join(timeout=0.25)
    for player_id in tuple(camera_follow_states):
        clear_camera_follow_player(player_id)

def handle_stick_input(x, y, stick_type="LEFT", player_id='player1', source="manual"):
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
            camera_follow_states[player_id].set_left_stick(x, y)
            with camera_follow_output_locks[player_id]:
                gamepad.left_joystick_float(x_value_float=x, y_value_float=-y)  # Y is inverted for gamepad
                gamepad.update()
        else:
            if source == "macro":
                camera_follow_states[player_id].set_macro_right_stick(x, y)
            else:
                camera_follow_states[player_id].set_manual_right_stick(x, y)
            apply_camera_follow_output(player_id, force=True)

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

    # Some app paths wrap every token as KEY_DOWN:<token>.
    # If the token is WAIT_1000, treat it as a delay instead of a keyboard key.
    if key.startswith("WAIT_"):
        handle_wait_command(key, player_id)
        return

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

    # Ignore accidental releases for wait tokens.
    # WAIT_1000 is a delay, not a real key that needs releasing.
    if key.startswith("WAIT_"):
        logger.info(f"{player_id} ignored wait key release: {key}")
        return

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

    # Check wait before key handling.
    # This keeps WAIT_1000 from being treated as a keyboard key.
    if command.startswith("WAIT_"):
        return handle_wait_command(command, player_id)

    # ----- key commands (reliable protocol) -----
    if command.startswith("KEY_DOWN:"):
        key = command.split(":", 1)[1]
        if key.startswith("WAIT_"):
            return handle_wait_command(key, player_id)
        handle_key_press(key, player_id)
        return True

    if command.startswith("KEY_UP:"):
        key = command.split(":", 1)[1]
        if key.startswith("WAIT_"):
            logger.info(f"{player_id} ignored wait key release: {key}")
            return True
        handle_key_release(key, player_id)
        return True

 # ➋  KEY_SYNC branch removed – we now ignore these packets
    # ----------------------------------------------

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
        btn = xbox_release_commands[command]
        try:
            gamepad.release_button(button=btn)
            gamepad.update()
            # clear state
            button_states.setdefault(player_id, {})[btn] = False
            logger.info(f"{player_id} Xbox button explicitly released: {command}")
        except Exception as e:
            logger.error(f"Failed to release Xbox button for {player_id}: {str(e)}")
        return True
    
    # Check if it's an Xbox button press
    if command in xbox_buttons:
        btn = xbox_buttons[command]
        try:
            # --- SAFETY: pre‑release if we think this button is still down ---
            if button_states.get(player_id, {}).get(btn, False):
                gamepad.release_button(button=btn)
                gamepad.update()
                time.sleep(0.005)  # tiny settle
                button_states[player_id][btn] = False

            # Press
            gamepad.press_button(button=btn)
            gamepad.update()
            # mark down
            button_states.setdefault(player_id, {})[btn] = True
            logger.info(f"{player_id} Xbox button pressed: {command}")

            # Only auto‑release if not a HOLD command
            if not command.endswith("_HOLD"):
                release_generation = current_release_generation(player_id)
                release_timer = None

                def do_release():
                    try:
                        if current_release_generation(player_id) != release_generation:
                            return
                        gamepad.release_button(button=btn)
                        gamepad.update()
                    except Exception as e:
                        logger.error(f"Auto‑release failed for {player_id}: {e}")
                    finally:
                        # make sure our local state is cleared
                        try:
                            button_states[player_id][btn] = False
                        except Exception:
                            pass

                        with release_state_lock:
                            pending_button_release_timers.setdefault(player_id, set()).discard(
                                release_timer
                            )

                release_timer = threading.Timer(0.1, do_release)
                release_timer.daemon = True
                with release_state_lock:
                    pending_button_release_timers.setdefault(player_id, set()).add(release_timer)
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

def process_timed_sequence(commands, player_id='player1', release_generation=None):
    """Process a sequence of commands with timing delays"""
    try:
        if release_generation is None:
            release_generation = current_release_generation(player_id)

        for cmd in commands:
            if current_release_generation(player_id) != release_generation:
                logger.info(f"Cancelled delayed command sequence for {player_id}")
                return

            cmd = cmd.strip()
            if cmd:  # Skip empty commands
                if cmd.startswith("WAIT_"):
                    try:
                        wait_seconds = max(0, int(cmd.split("_", 1)[1])) / 1000.0
                    except (ValueError, IndexError):
                        logger.warning(f"Invalid wait command from {player_id}: {cmd}")
                        continue

                    deadline = time.monotonic() + wait_seconds
                    while time.monotonic() < deadline:
                        if current_release_generation(player_id) != release_generation:
                            logger.info(f"Cancelled delayed command sequence for {player_id}")
                            return
                        time.sleep(min(0.01, max(0.0, deadline - time.monotonic())))
                    continue

                # Process command and wait for completion
                handle_button_press(cmd, player_id)
    except Exception as e:
        logger.error(f"Error in timed sequence for {player_id}: {str(e)}")

def process_command(data, addr, player_id='player1'):
    """Process incoming command from the Android app"""
    data = data.strip()
    if not data:
        return

    # Stateless discovery used by Android's USB Tether Mode. Keep this before
    # connection tracking so discovery probes do not become controller sessions.
    if data == "SIMPLE_CONTROLLER_DISCOVER":
        return f"SIMPLE_CONTROLLER_HERE:{PORT}"

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

    if data.startswith("RELEASE_ALL:"):
        parts = data.split(":", 2)
        if len(parts) != 3:
            logger.warning(f"Bad RELEASE_ALL packet: {data}")
            return None
        _, session_id, seq_text = parts
        try:
            sequence = int(seq_text)
        except ValueError:
            logger.warning(f"Bad RELEASE_ALL sequence: {data}")
            return None
        release_all_outputs(session_id, sequence)
        return f"RELEASE_ALL_ACK:{session_id}:{sequence}"

    # Generic ordered output. Ordering is used only as a RELEASE_ALL barrier;
    # ordinary commands retain their existing behavior even if UDP reorders them.
    if data.startswith("OUTPUT:"):
        parts = data.split(":", 3)
        if len(parts) != 4:
            logger.warning(f"Bad ordered output packet: {data}")
            return None
        _, session_id, seq_text, output_command = parts
        try:
            sequence = int(seq_text)
        except ValueError:
            logger.warning(f"Bad ordered output sequence: {data}")
            return None
        if not output_packet_is_current(session_id, sequence):
            logger.debug(f"Dropped pre-release output packet: {session_id}:{sequence}")
            return None
        data = output_command

    if player_id in camera_follow_last_input_at:
        camera_follow_last_input_at[player_id] = time.monotonic()
        camera_follow_input_lost[player_id] = False

    if data in ("CAMERA_FOLLOW:0", "CAMERA_FOLLOW:1"):
        set_camera_follow_enabled(player_id, data.endswith(":1"))
        return

    if data == "DISCONNECT":
        active_connections.pop(addr_key, None)
        return
        
    # 🚀 NEW: handle SCROLL packets immediately
    if data.startswith("SCROLL:"):
        handle_scroll(data)
        return                                         # ← nothing else to do    
        
         # --- LOG LEFT-CLICK PACKETS ---------------------------------
    if data.startswith("MOUSE_LEFT"):
        print("BTN", data)            # << add this line
    # ------------------------------------------------------------

    # 2️⃣  Route any movement packet straight to handle_touchpad()
    if data.startswith(("DELTA:", "TOUCHPAD:", "POS:")):
        handle_touchpad(data)
        return                             # ← prevent duplicate work later

    # 3️⃣  Everything below is for buttons / ping / register
    if data in ["MOUSE_LEFT_DOWN", "MOUSE_LEFT_UP", "MOUSE_RIGHT_DOWN", "MOUSE_RIGHT_UP", "MOUSE_MIDDLE_DOWN", "MOUSE_MIDDLE_UP",
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
        if key.startswith("WAIT_"):
            handle_wait_command(key, player_id)
            return None
        handle_key_press(key, player_id)
        return None

    if data.startswith("KEY_UP:"):
        key = data.split(":", 1)[1]
        if key.startswith("WAIT_"):
            logger.info(f"{player_id} ignored wait key release: {key}")
            return None
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
        handle_stick_input("0.0", "1.0", "RIGHT", player_id, source="macro")
        return None
    elif data == "RS_DOWN":
        handle_stick_input("0.0", "-1.0", "RIGHT", player_id, source="macro")
        return None
    elif data == "RS_LEFT":
        handle_stick_input("-1.0", "0.0", "RIGHT", player_id, source="macro")
        return None
    elif data == "RS_RIGHT":
        handle_stick_input("1.0", "0.0", "RIGHT", player_id, source="macro")
        return None
    elif data == "RS_CENTER":
        handle_stick_input("0.0", "0.0", "RIGHT", player_id, source="macro")
        return None

    # Sequenced analog stick packets
    # Format: STICK_L:session_id:sequence:x,y
    # Example: STICK_L:a3f219bc:843:0.00,0.00
    if data.startswith((
        "STICK:", "STICK_L:", "STICK_R:", "LS:", "RS:",
        "STICK_MACRO_L:", "STICK_MACRO_R:"
    )):
        parts = data.split(":", 3)

        if len(parts) == 4:
            command, session_id, seq_text, coords = parts

            try:
                seq = int(seq_text)
                x, y = coords.split(",", 1)
            except (ValueError, IndexError):
                logger.warning(f"Bad sequenced stick packet from {player_id}: {data}")
                return None

            source = "macro" if command.startswith("STICK_MACRO_") else "manual"
            if command in ("STICK", "STICK_L", "LS", "STICK_MACRO_L"):
                stick_type = "LEFT"
            elif command in ("STICK_R", "RS", "STICK_MACRO_R"):
                stick_type = "RIGHT"
            else:
                return None

            # Manual and macro packets share the Android sender's monotonic sequence.
            # Keep one freshness gate per physical stick so an older packet from one
            # source cannot surface after a newer packet from the other source.
            state_key = (player_id, stick_type)
            previous = latest_stick_packets.get(state_key)

            # Ignore old or duplicate packets from the same app session
            if not output_packet_is_current(session_id, seq):
                logger.debug(
                    f"Dropped pre-release {player_id} {stick_type} stick packet: seq={seq}"
                )
                return None

            if previous is not None and previous["session"] == session_id:
                if seq <= previous["seq"]:
                    logger.debug(
                        f"Dropped stale {player_id} {stick_type} stick packet: "
                        f"seq={seq}, newest={previous['seq']}"
                    )
                    return None

            # Remember newest accepted packet
            latest_stick_packets[state_key] = {
                "session": session_id,
                "seq": seq
            }

            handle_stick_input(x, y, stick_type, player_id, source=source)
            return None
    
    # Handle stick/touchpad input (format: "STICK:x,y" or "TOUCHPAD:x,y")
    if ":" in data:
        command, coords = data.split(":", 1)
        if "," in coords:
            x, y = coords.split(",", 1)
            if command in ("STICK", "STICK_L", "LS"):
                handle_stick_input(x, y, "LEFT", player_id)
            elif command in ("STICK_R", "RS"):
                handle_stick_input(x, y, "RIGHT", player_id)
            elif command == "STICK_MACRO_L":
                handle_stick_input(x, y, "LEFT", player_id, source="macro")
            elif command == "STICK_MACRO_R":
                handle_stick_input(x, y, "RIGHT", player_id, source="macro")
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
        release_generation = current_release_generation(player_id)
        threading.Thread(
            target=process_timed_sequence, 
            args=(commands, player_id, release_generation),
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
        player_id = active_connections[addr_key]['player_id']
        logger.info(f"Removing inactive connection: {addr_key} ({player_id})")
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
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 262144)
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

    show_connection_info()
    
    try:
        # Start the connection cleanup scheduleraaaaa
        start_cleanup_scheduler()

        # Camera Follow is isolated from command handling and updates only RS output.
        start_camera_follow_worker()
        
        # Start the UDP server
        udp_server()
        
    except KeyboardInterrupt:
        print("\nServer shutting down...")
        logger.info("Server stopping...")
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
    finally:
        stop_camera_follow_worker()
        print("Server stopped")
        logger.info("Server stopped")
        
    input("Press Enter to exit...")
