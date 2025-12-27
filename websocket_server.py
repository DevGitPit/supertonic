import asyncio
import websockets
import json
import subprocess
import struct
import os
import sys

# Path to the native host script
HOST_PATH = "./cpp/build/start_host.sh"

class NativeHostProxy:
    def __init__(self):
        self.proc = None

    def start(self):
        if not os.path.exists(HOST_PATH):
            print(f"Error: Host script not found at {HOST_PATH}")
            return False
            
        print(f"Starting Native Host: {HOST_PATH}")
        try:
            self.proc = subprocess.Popen(
                [HOST_PATH],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=sys.stderr  # Pass stderr through to console
            )
            # Send initialization immediately
            self.send_message({"command": "initialize"})
            # Read response
            resp = self.read_message()
            print(f"Host Initialized: {resp}")
            return True
        except Exception as e:
            print(f"Failed to start host: {e}")
            return False

    def send_message(self, message):
        if not self.proc: return
        encoded = json.dumps(message).encode('utf-8')
        length = struct.pack('I', len(encoded))
        self.proc.stdin.write(length)
        self.proc.stdin.write(encoded)
        self.proc.stdin.flush()

    def read_message(self):
        if not self.proc: return None
        length_bytes = self.proc.stdout.read(4)
        if not length_bytes:
            return None
        length = struct.unpack('I', length_bytes)[0]
        content = self.proc.stdout.read(length)
        return json.loads(content)

    def stop(self):
        if self.proc:
            self.proc.terminate()
            self.proc = None

proxy = NativeHostProxy()

async def handler(websocket):
    print("Client connected")
    try:
        async for message in websocket:
            data = json.loads(message)
            print(f"Received command: {data.get('command')}")
            
            # Forward to native host
            proxy.send_message(data)
            
            # Read response
            response = proxy.read_message()
            
            # Send back to client
            await websocket.send(json.dumps(response))
            
    except websockets.exceptions.ConnectionClosed:
        print("Client disconnected")
    except Exception as e:
        print(f"Error handling message: {e}")
        await websocket.close()

async def main():
    if not proxy.start():
        return

    print("WebSocket server starting on ws://localhost:8080")
    async with websockets.serve(handler, "localhost", 8080):
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nStopping...")
        proxy.stop()
