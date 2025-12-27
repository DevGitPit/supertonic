import urllib.request
import json
import sys
import base64

url = "http://127.0.0.1:8080/synthesize"
data = {
    "command": "synthesize",
    "text": "This is a test sentence for debugging.",
    "voice_style_path": "assets/voice_styles/M1.json",
    "speed": 1.0,
    "total_step": 5
}

print(f"Testing Backend at {url}")
print(f"Payload: {json.dumps(data, indent=2)}")

try:
    req = urllib.request.Request(url)
    req.add_header('Content-Type', 'application/json')
    jsondata = json.dumps(data).encode('utf-8')
    req.add_header('Content-Length', len(jsondata))
    
    print("Sending request...")
    response = urllib.request.urlopen(req, jsondata)
    res_body = response.read()
    
    print(f"HTTP Status: {response.getcode()}")
    
    try:
        json_resp = json.loads(res_body)
        if "error" in json_resp:
            print(f"BACKEND ERROR: {json_resp['error']}")
            sys.exit(1)
            
        if "audio" in json_resp:
            audio_len = len(json_resp['audio'])
            print(f"SUCCESS! Received audio data (base64 length: {audio_len})")
            
            # Verify base64
            try:
                decoded = base64.b64decode(json_resp['audio'])
                print(f"Decoded audio bytes: {len(decoded)}")
            except Exception as e:
                print(f"Base64 decode failed: {e}")
        else:
            print("Response received but missing 'audio' field.")
            print(json_resp)
            
    except json.JSONDecodeError:
        print("Failed to decode JSON response.")
        print(f"Raw body: {res_body}")

except urllib.error.URLError as e:
    print(f"Connection Failed: {e}")
    print("Ensure server.py is running: 'ps aux | grep server.py'")
except Exception as e:
    print(f"Unexpected Error: {e}")
