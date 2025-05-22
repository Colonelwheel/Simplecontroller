#!/usr/bin/env python3
"""
Command Processor Module

Parses and routes commands from clients to the appropriate handlers.
"""

import logging
import time
import threading

logger = logging.getLogger(__name__)

class CommandProcessor:
    """Processes commands from clients and routes them to appropriate handlers"""
    def __init__(self, mouse_handler, keyboard_handler, gamepad_handler):
        self.mouse_handler = mouse_handler
        self.keyboard_handler = keyboard_handler
        self.gamepad_handler = gamepad_handler
    
    def process_command(self, data, addr, player_id='player1'):
        """Process incoming command from the Android app"""
        data = data.strip()
        if not data:
            return None
        
        # Log mouse button events for debugging
        if data.startswith("MOUSE_LEFT"):
            print("BTN", data)
        
        # Handle different command types
        
        # Handle heartbeat messages
        if data == "PING":
            return "PONG"
        
        # Handle connection and registration messages
        if data.startswith("CONNECT:"):
            try:
                requested_id = data.split(":", 1)[1].strip()
                if requested_id in ['player1', 'player2']:
                    player_id = requested_id
                    logger.info(f"Client {addr} connected as {player_id}")
                    return f"CONNECTED:{player_id}"
                else:
                    logger.warning(f"Invalid player ID in connection request: {requested_id}")
                    return "ERROR:invalid_player_id"
            except Exception as e:
                logger.error(f"Error processing connection request: {e}")
                return "ERROR:connection_failed"
        
        # Handle player registration
        if data.startswith("REGISTER:"):
            try:
                requested_id = data.split(":", 1)[1].strip()
                if requested_id in ['player1', 'player2']:
                    player_id = requested_id
                    logger.info(f"Client {addr} registered as {player_id}")
                    return f"REGISTERED:{player_id}"
                else:
                    logger.warning(f"Invalid player ID request: {requested_id}")
                    return "ERROR:invalid_player_id"
            except Exception as e:
                logger.error(f"Error processing registration: {e}")
                return "ERROR:registration_failed"
        
        # Process movement data
        # Handle Delta movement (more efficient touchpad protocol)
        if data.startswith("DELTA:"):
            try:
                dx, dy = map(float, data[6:].split(",", 1))
                self.mouse_handler.handle_delta(dx, dy)
                return None
            except ValueError:
                logger.warning(f"Bad DELTA packet: {data}")
                return None
        
        # Handle touchpad/position data
        if data.startswith(("TOUCHPAD:", "POS:")):
            try:
                _, coords = data.split(":", 1)
                x, y = coords.split(",", 1)
                self.mouse_handler.handle_touchpad(x, y)
                return None
            except Exception as e:
                logger.error(f"Error handling touchpad input: {e}")
                return None
        
        # Handle mouse button commands
        if data in ["MOUSE_LEFT_DOWN", "MOUSE_LEFT_UP", "TOUCHPAD_END", "TOUCH_END", "MOUSE_RESET"]:
            self.mouse_handler.handle_button_command(data)
            return None
        
        # Ignore keep-alive packets
        if data.startswith("KEY_SYNC:"):
            return None
        
        # Handle key state commands
        if data.startswith("KEY_DOWN:"):
            key = data.split(":", 1)[1]
            self.keyboard_handler.handle_key_press(key, player_id)
            return None
            
        if data.startswith("KEY_UP:"):
            key = data.split(":", 1)[1]
            self.keyboard_handler.handle_key_release(key, player_id)
            return None
        
        # Handle trigger input
        if data.startswith("TRIGGER_L:"):
            value = data.split(":", 1)[1]
            self.gamepad_handler.handle_trigger_input(value, "LEFT", player_id)
            return None
            
        if data.startswith("TRIGGER_R:"):
            value = data.split(":", 1)[1]
            self.gamepad_handler.handle_trigger_input(value, "RIGHT", player_id)
            return None
            
        # Shortened trigger syntax
        if data.startswith("LT:"):
            value = data.split(":", 1)[1]
            self.gamepad_handler.handle_trigger_input(value, "LEFT", player_id)
            return None
            
        if data.startswith("RT:"):
            value = data.split(":", 1)[1]
            self.gamepad_handler.handle_trigger_input(value, "RIGHT", player_id)
            return None
        
        # Check for wait command
        if data.startswith("WAIT_"):
            self._handle_wait_command(data, player_id)
            return None
        
        # Handle stick directional shortcuts
        stick_shortcuts = {
            "LS_UP": ("0.0", "1.0", "LEFT"),
            "LS_DOWN": ("0.0", "-1.0", "LEFT"),
            "LS_LEFT": ("-1.0", "0.0", "LEFT"),
            "LS_RIGHT": ("1.0", "0.0", "LEFT"),
            "RS_UP": ("0.0", "1.0", "RIGHT"),
            "RS_DOWN": ("0.0", "-1.0", "RIGHT"),
            "RS_LEFT": ("-1.0", "0.0", "RIGHT"),
            "RS_RIGHT": ("1.0", "0.0", "RIGHT")
        }
        
        if data in stick_shortcuts:
            x, y, stick_type = stick_shortcuts[data]
            self.gamepad_handler.handle_stick_input(x, y, stick_type, player_id)
            return None
        
        # Handle stick/analog input
        if ":" in data:
            command, coords = data.split(":", 1)
            if "," in coords:
                x, y = coords.split(",", 1)
                if command == "STICK" or command == "STICK_L" or command == "LS":
                    self.gamepad_handler.handle_stick_input(x, y, "LEFT", player_id)
                    return None
                elif command == "STICK_R" or command == "RS":
                    self.gamepad_handler.handle_stick_input(x, y, "RIGHT", player_id)
                    return None
        
        # Try to handle as button release
        if self.gamepad_handler.handle_button_release(data, player_id):
            return None
        
        # Try to handle as button press
        if self.gamepad_handler.handle_button_press(data, player_id):
            return None
        
        # Handle commands with commas (sequences)
        if "," in data:
            commands = data.split(",")
            self._process_sequence(commands, player_id)
            return None
        
        # If nothing else matched, try keyboard key
        try:
            # Only player1 controls the keyboard to avoid conflicts
            if player_id == 'player1':
                # For regular keyboard presses (not through key state system)
                import keyboard
                keyboard.press_and_release(data.lower())
                logger.info(f"{player_id} Keyboard key pressed: {data}")
            return None
        except Exception as e:
            logger.error(f"Failed to process command for {player_id}: {data} - {str(e)}")
            return None
    
    def _handle_wait_command(self, command, player_id='player1'):
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
    
    def _process_sequence(self, commands, player_id='player1'):
        """Process a sequence of commands"""
        def run_sequence():
            try:
                for cmd in commands:
                    cmd = cmd.strip()
                    if cmd:  # Skip empty commands
                        # Process command
                        self.process_command(cmd, None, player_id)
            except Exception as e:
                logger.error(f"Error in command sequence for {player_id}: {str(e)}")
        
        # Run the sequence in a separate thread
        threading.Thread(target=run_sequence, daemon=True).start()
        logger.info(f"Started command sequence with {len(commands)} commands for {player_id}")
        return True