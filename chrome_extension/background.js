chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === "synthesize") {
    
    // Construct path relative to assets folder
    const voicePath = "assets/voice_styles/" + request.voice_style;

    const message = {
      command: "synthesize",
      text: request.text,
      voice_style_path: voicePath,
      speed: request.speed,
      total_step: request.total_step || 5
    };

    console.log("Sending to HTTP server:", message);

    fetch('http://127.0.0.1:8080/synthesize', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(message)
    })
    .then(response => response.json())
    .then(data => {
        console.log("Received response:", data);
        sendResponse(data);
    })
    .catch(error => {
        console.error("Fetch error:", error);
        sendResponse({ error: "Failed to connect to local server (python3 server.py): " + error.message });
    });

    return true; // Indicates async response
  }
});
