#!/usr/bin/env python3
"""
Network Manager Module

Handles UDP socket communication with client applications.
"""

import socket
import logging
import time

logger = logging.getLogger(__name__)

class NetworkManager:
    """Manages network communication with client applications"""
    def __init__(self, host='0.0.0.0', port=9001):
        self.host = host
        self.port = port
        self.socket = None
        
        # Track active connections
        self.active_connections = {}
    
    def setup_socket(self):
        """Set up the UDP socket with low-latency options"""
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.setup_low_latency_socket(self.socket)
            self.socket.bind((self.host, self.port))
            logger.info(f"UDP server started on {self.host}:{self.port}")
            return True
        except Exception as e:
            logger.error(f"Failed to set up socket: {e}")
            self.socket = None
            return False
    
    def setup_low_latency_socket(self, sock):
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
    
    def receive_data(self):
        """Receive data from clients"""
        if not self.socket:
            logger.error("Socket not initialized")
            return None, None
        
        try:
            data, addr = self.socket.recvfrom(1024)
            
            try:
                decoded_data = data.decode('utf-8').strip()
                addr_key = f"{addr[0]}:{addr[1]}"
                
                # Update connection tracking
                if addr_key not in self.active_connections:
                    self.active_connections[addr_key] = {
                        'player_id': 'player1',  # Default to player1
                        'addr': addr,
                        'last_seen': time.time()
                    }
                else:
                    self.active_connections[addr_key]['last_seen'] = time.time()
                
                # Return player ID and data
                player_id = self.active_connections[addr_key]['player_id']
                
                # Extract player ID prefix if present
                if decoded_data.startswith(("player1:", "player2:")):
                    player_id, decoded_data = decoded_data.split(":", 1)
                    # Update stored player ID
                    self.active_connections[addr_key]['player_id'] = player_id
                
                return decoded_data, addr, player_id
                
            except UnicodeDecodeError:
                logger.warning(f"Received invalid data from {addr}")
                return None, None, None
                
        except Exception as e:
            logger.error(f"Error receiving data: {e}")
            return None, None, None
    
    def send_response(self, response, addr):
        """Send a response to a client"""
        if not self.socket:
            logger.error("Socket not initialized")
            return False
        
        try:
            if response:
                self.socket.sendto(response.encode('utf-8'), addr)
            return True
        except Exception as e:
            logger.error(f"Error sending response: {e}")
            return False
    
    def register_player(self, player_id, addr):
        """Register a client with a specific player ID"""
        addr_key = f"{addr[0]}:{addr[1]}"
        if player_id in ['player1', 'player2']:
            self.active_connections[addr_key] = {
                'player_id': player_id,
                'addr': addr,
                'last_seen': time.time()
            }
            logger.info(f"Client {addr} registered as {player_id}")
            return True
        else:
            logger.warning(f"Invalid player ID: {player_id}")
            return False
    
    def clean_inactive_connections(self, timeout=30):
        """Remove connections that haven't sent data in a while"""
        now = time.time()
        
        to_remove = []
        for addr_key, conn in self.active_connections.items():
            if now - conn['last_seen'] > timeout:
                to_remove.append(addr_key)
        
        for addr_key in to_remove:
            logger.info(f"Removing inactive connection: {addr_key} ({self.active_connections[addr_key]['player_id']})")
            del self.active_connections[addr_key]
    
    def close(self):
        """Close the socket"""
        if self.socket:
            self.socket.close()
            self.socket = None
            logger.info("Socket closed")
            return True
        return False