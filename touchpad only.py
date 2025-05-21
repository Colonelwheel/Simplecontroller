#!/usr/bin/env python3
"""
Touchpad Stability Server

A server designed to minimize jumps and jitter in touchpad movement.
Based on diagnostic logs showing oversensitivity issues.

Usage: python touchpad_stability.py
"""

import socket
import threading
import time
import logging
import os
from datetime import datetime
from collections import deque

# Import mouse control library
try:
    import mouse
except ImportError:
    print("Mouse library not found. Please install it with: pip install mouse")
    exit(1)

# Directory for logs
LOG_DIR = "touchpad_logs"
os.makedirs(LOG_DIR, exist_ok=True)

# Configure logging
timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
log_file = os.path.join(LOG_DIR, f"touchpad_stable_{timestamp}.log")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(log_file),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Server configuration
HOST = '0.0.0.0'  # Listen on all interfaces
PORT = 9001       # Default port

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

def normalize_value(value):
    """Convert string or float to normalized float (-1.0 to 1.0)"""
    try:
        return max(-1.0, min(1.0, float(value)))
    except (ValueError, TypeError):
        logger.error(f"Failed to convert value to float: {value}")
        return 0.0

def handle_touchpad(command):
    """Handle touchpad movement commands with stability focus"""
    try:
        # Parse touchpad command format
        if ":" not in command:
            return
            
        cmd_type, coords = command.split(":", 1)
        
        # Handle different command types
        if cmd_type not in ["TOUCHPAD", "POS"]:
            return
            
        if "," not in coords:
            return
            
        x, y = coords.split(",", 1)
        x_val = normalize_value(x)
        y_val = normalize_value(y)
        
        # Process with stability smoother
        dx, dy = smoother.process_movement(x_val, y_val)
        
        # Skip if no movement
        if dx == 0 and dy == 0:
            return
            
        # Move mouse with calculated delta
        mouse.move(dx, dy, absolute=False)
        
    except Exception as e:
        logger.error(f"Error handling touchpad input: {e}")

def handle_mouse_buttons(command):
    """Handle mouse button commands"""
    try:
        if command == "MOUSE_LEFT_DOWN":
            mouse.press(button="left")
            logger.info("Mouse left button pressed")
            
        elif command == "MOUSE_LEFT_UP":
            mouse.release(button="left")
            logger.info("Mouse left button released")
            
        # Handle touch events
        elif command == "TOUCHPAD_END" or command == "TOUCH_END":
            smoother.end_touch()
            
        # Explicitly ignore MOUSE_RESET to prevent jumps
        elif command == "MOUSE_RESET":
            logger.warning("MOUSE_RESET command received but ignored to prevent jumps")
            
    except Exception as e:
        logger.error(f"Error handling button command: {e}")

def process_command(data, addr):
    """Process commands from the client"""
    data = data.strip()
    
    # Skip empty data
    if not data:
        return
    
    # Handle player prefix if present
    if data.startswith("player1:") or data.startswith("player2:"):
        _, data = data.split(":", 1)
    
    # Special handling for first touch
    if (data.startswith("TOUCHPAD:") or data.startswith("POS:")) and not smoother.is_active():
        logger.info(f"New touch starting with: {data}")
        
        # Parse first position
        cmd_type, coords = data.split(":", 1)
        if "," in coords:
            x, y = coords.split(",", 1)
            x_val = normalize_value(x)
            y_val = normalize_value(y)
            
            # Store initial position and reset smoother
            smoother.update_position(x_val, y_val)
            smoother.reset()
            return
    
    # Log non-movement commands
    if not data.startswith("TOUCHPAD:") and not data.startswith("POS:"):
        logger.info(f"Command from {addr}: {data}")
    
    # Handle touchpad/position commands
    if data.startswith("TOUCHPAD:") or data.startswith("POS:"):
        handle_touchpad(data)
        
    # Handle mouse button commands
    elif data in ["MOUSE_LEFT_DOWN", "MOUSE_LEFT_UP", "TOUCHPAD_END", "TOUCH_END", "MOUSE_RESET"]:
        handle_mouse_buttons(data)
        
    # Handle ping/keepalive
    elif data == "PING":
        return "PONG"
        
    # Handle connection registration
    elif data.startswith("CONNECT:") or data.startswith("REGISTER:"):
        logger.info(f"Client {addr} registered")
        return "CONNECTED:player1"

def udp_server():
    """Run a UDP server for touchpad controls"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind((HOST, PORT))
        logger.info(f"Touchpad stability server listening on UDP {HOST}:{PORT}")
        
        while True:
            try:
                data, addr = sock.recvfrom(1024)
                
                try:
                    decoded_data = data.decode('utf-8').strip()
                    response = process_command(decoded_data, addr)
                    
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
    try:
        print(f"=== Touchpad Stability Server ===")
        print(f"Listening on {HOST}:{PORT}")
        print(f"Log file: {log_file}")
        print("Press Ctrl+C to exit")
        
        # Start the UDP server
        udp_server()
        
    except KeyboardInterrupt:
        print("\nServer shutting down...")
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
    finally:
        print("Server stopped")