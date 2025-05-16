#!/usr/bin/env python3
import socket

HOST, PORT = "0.0.0.0", 9001

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
    s.bind((HOST, PORT))
    s.listen()
    print(f"Listening on {HOST}:{PORT} …")

    while True:
        conn, addr = s.accept()
        print("Client connected:", addr)
        with conn, conn.makefile("r") as reader:
            for line in reader:                # read until client closes
                print("Decoded :", line.rstrip())
        print("Client disconnected.")
