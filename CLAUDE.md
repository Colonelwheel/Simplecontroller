# SimpleController Project Memory

## Project Overview
- Android application that serves as a controller interface
- Contains UI components for directional control and other interactive elements
- Includes server components written in Python
- Features UDP network communication for controller data
- Supports customizable themes and connection settings

## Key Components
- MainActivity: Main entry point for the application
- RefactoredMainActivity: Improved version of the main activity
- NetworkClient: Handles TCP network communication
- UdpClient: Handles UDP network communication
- ControlView: UI component for controller interface
- DirectionalStickHandler: Handles directional input
- SwipeHandler: Processes swipe gestures
- PropertySheetBuilder: Builds property sheets for controls
- ContinuousSender: Manages continuous data sending
- ThemeManager: Handles application theming

## Server Components
- Server.py: Python server implementation
- controller_server.py: Controller server implementation
- controller_server Two Player Support.py: Server with multiplayer support

## File Structure
- UI components in com.example.simplecontroller.ui
- Models in com.example.simplecontroller.model
- IO operations in com.example.simplecontroller.io
- Server files in project root

## Recent Changes
- Added UDP client implementation
- Added theme management
- Added connection settings dialog
- Implemented Python server components
- Added menu system
- Enhanced MainActivity with new features
- Added recenter button functionality
- Refactored PropertySheetBuilder for improved maintainability

## Commands
- Build: ./gradlew build
- Run tests: ./gradlew test
- Install on device: ./gradlew installDebug