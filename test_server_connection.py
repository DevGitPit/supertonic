import urllib.request
import json
import sys

url = "http://127.0.0.1:8080/synthesize"
data = {
    "command": "synthesize",
    "text": "Testing connection.",
    "voice_style_path": "assets/voice_styles/M1.json",
    "speed": 1.0,
    "total_step": 5
}

try:
    req = urllib.request.Request(url)
    req.add_header('Content-Type', 'application/json')
    jsondata = json.dumps(data).encode('utf-8')
    req.add_header('Content-Length', len(jsondata))
    
    print(f"Sending request to {url}...")
    response = urllib.request.urlopen(req, jsondata)
    res_body = response.read()
    
    print("Response received!")
    print(f"Status Code: {response.getcode()}")
    try:
        json_resp = json.loads(res_body)
        if "audio" in json_resp:
            print(f"Success! Audio data length: {len(json_resp['audio'])}")
        else:
            print(f"Response JSON (no audio): {json_resp}")
    except:
        print(f"Raw response: {res_body}")

except Exception as e:
    print(f"Connection failed: {e}")
    sys.exit(1)
