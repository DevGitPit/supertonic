import http.server
import socketserver
import json
import subprocess
import struct
import os
import sys

PORT = 8080
HOST_PATH = "./cpp/build/start_host.sh"

class TTSHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/health':
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode('utf-8'))
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path == '/synthesize':
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            request = json.loads(post_data.decode('utf-8'))
            
            print(f"Received request: {request}")
            
            if not os.path.exists(HOST_PATH):
                self.send_error(500, f"Host script not found at {HOST_PATH}")
                return

            try:
                # Start the native host
                proc = subprocess.Popen(
                    [HOST_PATH],
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=sys.stderr
                )
                
                # Send initialize
                self._send_to_host(proc, {"command": "initialize"})
                init_resp = self._read_from_host(proc)
                print(f"Host init: {init_resp}")
                
                # Send synthesize
                self._send_to_host(proc, request)
                response = self._read_from_host(proc)
                
                # Close host
                proc.terminate()
                
                # Send response to client
                self.send_response(200)
                self.send_header('Content-type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(json.dumps(response).encode('utf-8'))
                
            except Exception as e:
                print(f"Error: {e}")
                self.send_error(500, str(e))
        else:
            self.send_error(404)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def _send_to_host(self, proc, message):
        encoded = json.dumps(message).encode('utf-8')
        length = struct.pack('I', len(encoded))
        proc.stdin.write(length)
        proc.stdin.write(encoded)
        proc.stdin.flush()

    def _read_from_host(self, proc):
        length_bytes = proc.stdout.read(4)
        if not length_bytes:
            return None
        length = struct.unpack('I', length_bytes)[0]
        content = proc.stdout.read(length)
        return json.loads(content)

print(f"Starting HTTP TTS Server on port {PORT}...")
# Bind to 127.0.0.1 explicitly for local access
with socketserver.TCPServer(("127.0.0.1", PORT), TTSHandler) as httpd:
    print("Server running on 127.0.0.1:8080. Keep this terminal open.")
    sys.stdout.flush()
    httpd.serve_forever()
