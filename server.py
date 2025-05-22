#!/usr/bin/env python3
"""
Modular Controller Server

A server that handles both touchpad/mouse and gamepad controls.
Combines stability-focused mouse handling with full gamepad support.

Usage: python server.py

This server uses a modular architecture with separate handlers for:
- Mouse/touchpad input
- Keyboard input
- Gamepad input
- Network communication
- Command processing
"""

import logging
import os
import threading
import time
from datetime import datetime

# Import handlers
from MouseHandler import MouseHandler
from KeyboardHandler import KeyboardHandler
from GamepadHandler import GamepadHandler
from NetworkManager import NetworkManager
from CommandProcessor import CommandProcessor

# Create log directory
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

def start_cleanup_scheduler(network_manager, keyboard_handler):
    """Schedule regular cleaning of inactive connections and key states"""
    def scheduled_cleanup():
        while True:
            time.sleep(10)  # Run every 10 seconds
            network_manager.clean_inactive_connections()
            keyboard_handler.clean_key_states()  # Also check key states periodically
            
    cleanup_thread = threading.Thread(target=scheduled_cleanup, daemon=True)
    cleanup_thread.start()

def main():
    logger.info("=== Modular Controller Server ===")
    
    # Create all the handlers
    mouse_handler = MouseHandler()
    keyboard_handler = KeyboardHandler()
    gamepad_handler = GamepadHandler()
    
    # Create network manager (UDP server)
    network_manager = NetworkManager()
    if not network_manager.setup_socket():
        logger.error("Failed to set up network socket, exiting")
        return
    
    # Create command processor
    command_processor = CommandProcessor(mouse_handler, keyboard_handler, gamepad_handler)
    
    # Start cleanup scheduler
    start_cleanup_scheduler(network_manager, keyboard_handler)
    
    logger.info(f"Server started on {network_manager.host}:{network_manager.port}")
    logger.info(f"Log file: {log_file}")
    logger.info("Press Ctrl+C to exit")
    
    try:
        while True:
            # Receive data from clients
            data, addr, player_id = network_manager.receive_data()
            if data is None:
                continue
            
            # Process the command
            response = command_processor.process_command(data, addr, player_id)
            
            # Send response if needed
            if response and addr:
                network_manager.send_response(response, addr)
    
    except KeyboardInterrupt:
        print("\nServer shutting down...")
        logger.info("Server stopping...")
    
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
    
    finally:
        # Cleanup
        network_manager.close()
        keyboard_handler.release_all_keys()
        print("Server stopped")
        logger.info("Server stopped")

if __name__ == "__main__":
    main()