#!/usr/bin/env python3
"""
Gamepad Handler Module

Handles gamepad input from the client app, including buttons,
triggers, and analog sticks.
"""

import logging
import threading
import vgamepad

logger = logging.getLogger(__name__)

class GamepadHandler:
    """Handles gamepad inputs for multiple players"""
    def __init__(self):
        # Create virtual Xbox 360 controllers - one for each player
        self.gamepads = {
            'player1': vgamepad.VX360Gamepad(),
            'player2': vgamepad.VX360Gamepad()
        }
        
        # Track button states per player
        self.button_states = {
            'player1': {},
            'player2': {}
        }
    
    def release_button(self, button, player_id='player1'):
        """Helper function to release an Xbox button"""
        try:
            if player_id in self.gamepads:
                self.gamepads[player_id].release_button(button=button)
                self.gamepads[player_id].update()
                logger.info(f"{player_id} released button: {button}")
                return True
        except Exception as e:
            logger.error(f"Failed to release button for {player_id}: {str(e)}")
            return False
    
    def handle_stick_input(self, x, y, stick_type="LEFT", player_id='player1'):
        """Handle analog stick input with improved handling"""
        try:
            x = self._normalize_value(x)
            y = self._normalize_value(y)
            
            # Apply deadzone if very close to center
            if abs(x) < 0.05 and abs(y) < 0.05:
                x, y = 0, 0
            
            if player_id not in self.gamepads:
                logger.error(f"Unknown player ID: {player_id}")
                return False
                
            gamepad = self.gamepads[player_id]
            
            if stick_type == "LEFT":
                gamepad.left_joystick_float(x_value_float=x, y_value_float=-y)  # Y is inverted for gamepad
            else:
                gamepad.right_joystick_float(x_value_float=x, y_value_float=-y)  # Y is inverted for gamepad
            
            gamepad.update()
            logger.info(f"{player_id} Stick {stick_type}: x={x:.2f}, y={y:.2f}")
            return True
        except Exception as e:
            logger.error(f"Error handling stick input for {player_id}: {str(e)}")
            return False
    
    def handle_trigger_input(self, value, trigger="LEFT", player_id='player1'):
        """Handle analog trigger input (0.0 to 1.0)"""
        try:
            value = self._normalize_value(value)
            # Ensure value is between 0 and 1 for triggers
            value = max(0.0, min(1.0, value))
            
            if player_id not in self.gamepads:
                logger.error(f"Unknown player ID: {player_id}")
                return False
                
            gamepad = self.gamepads[player_id]
            
            if trigger == "LEFT":
                gamepad.left_trigger_float(value_float=value)
                logger.info(f"{player_id} Left trigger: {value:.2f}")
            else:
                gamepad.right_trigger_float(value_float=value)
                logger.info(f"{player_id} Right trigger: {value:.2f}")
            
            gamepad.update()
            return True
        except Exception as e:
            logger.error(f"Error handling trigger input for {player_id}: {str(e)}")
            return False
    
    def handle_button_press(self, command, player_id='player1', auto_release=True):
        """Handle Xbox button press with optional auto-release"""
        if player_id not in self.gamepads:
            logger.error(f"Unknown player ID: {player_id}")
            return False
        
        # Map of button command strings to vgamepad button constants
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
        
        # Check if it's an Xbox button press
        if command in xbox_buttons:
            try:
                # Press the button
                self.gamepads[player_id].press_button(button=xbox_buttons[command])
                self.gamepads[player_id].update()
                logger.info(f"{player_id} Xbox button pressed: {command}")
                
                # Determine if auto-release should be used
                should_auto_release = auto_release and not command.endswith("_HOLD")
                
                if should_auto_release:
                    # Schedule the button release after a short delay (100ms)
                    release_timer = threading.Timer(
                        0.1, 
                        lambda: self.release_button(xbox_buttons[command], player_id)
                    )
                    release_timer.daemon = True
                    release_timer.start()
                    logger.info(f"Scheduled auto-release for {player_id} {command}")
                else:
                    logger.info(f"Hold mode - no auto-release for {player_id} {command}")
                return True
            except Exception as e:
                logger.error(f"Failed to press Xbox button for {player_id}: {str(e)}")
                return False
        
        return False
    
    def handle_button_release(self, command, player_id='player1'):
        """Handle explicit button release commands"""
        if player_id not in self.gamepads:
            logger.error(f"Unknown player ID: {player_id}")
            return False
        
        # Map of release command strings to vgamepad button constants
        xbox_release_commands = {
            "BUTTON_A_RELEASED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_A,
            "BUTTON_B_RELEASED": vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_B,
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
        
        # Handle explicit button releases
        if command in xbox_release_commands:
            try:
                self.gamepads[player_id].release_button(button=xbox_release_commands[command])
                self.gamepads[player_id].update()
                logger.info(f"{player_id} Xbox button explicitly released: {command}")
                return True
            except Exception as e:
                logger.error(f"Failed to release Xbox button for {player_id}: {str(e)}")
                return False
        
        return False
    
    @staticmethod
    def _normalize_value(value):
        """Convert string or float to normalized float (-1.0 to 1.0)"""
        try:
            return max(-1.0, min(1.0, float(value)))
        except (ValueError, TypeError):
            logger.error(f"Failed to convert value to float: {value}")
            return 0.0