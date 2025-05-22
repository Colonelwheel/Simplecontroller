#!/usr/bin/env python3
"""
Simple runner for the modular controller server

This script starts the modular controller server and handles errors.
It also offers a simple way to run the server with proper imports.
"""

import sys
import os
import importlib.util
import traceback

def import_module_from_file(file_path, module_name):
    """Import a module from file path"""
    try:
        spec = importlib.util.spec_from_file_location(module_name, file_path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
        return module
    except Exception as e:
        print(f"Error importing {module_name} from {file_path}: {e}")
        traceback.print_exc()
        return None

def run_server():
    """Run the modular controller server"""
    # Get the directory of the script
    script_dir = os.path.dirname(os.path.abspath(__file__))
    
    # Import all required modules
    modules = {
        "MouseHandler": os.path.join(script_dir, "MouseHandler.py"),
        "KeyboardHandler": os.path.join(script_dir, "KeyboardHandler.py"),
        "GamepadHandler": os.path.join(script_dir, "GamepadHandler.py"),
        "NetworkManager": os.path.join(script_dir, "NetworkManager.py"),
        "CommandProcessor": os.path.join(script_dir, "CommandProcessor.py"),
        "server": os.path.join(script_dir, "server.py")
    }
    
    # Import all modules
    imported_modules = {}
    for module_name, file_path in modules.items():
        module = import_module_from_file(file_path, module_name)
        if module is None:
            print(f"Failed to import {module_name}, cannot continue")
            return
        imported_modules[module_name] = module
    
    # Run the server
    try:
        imported_modules["server"].main()
    except Exception as e:
        print(f"Error running server: {e}")
        traceback.print_exc()

if __name__ == "__main__":
    run_server()