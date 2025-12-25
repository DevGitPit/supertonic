class KeepAliveProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.nextTick = 0;
    this.isExternalAudioPlaying = false;
    
    this.port.onmessage = (e) => {
      if (e.data.type === 'setPlaying') {
        this.isExternalAudioPlaying = e.data.playing;
      }
    };
  }
  
  process(inputs, outputs, parameters) {
    const output = outputs[0];
    const channel = output[0];
    
    // Only generate noise during gaps between audio
    if (!this.isExternalAudioPlaying) {
      // Fill with faint noise
      for (let i = 0; i < channel.length; i++) {
          // Reduced amplitude to 0.0002 (~-74dB)
          channel[i] = (Math.random() - 0.5) * 0.0002;
      }
    } else {
      // Silent when actual audio is playing to prevent interference
      for (let i = 0; i < channel.length; i++) {
        channel[i] = 0;
      }
    }
    
    // Send tick every ~100ms
    // currentTime is a global in AudioWorkletGlobalScope
    const now = currentTime; 
    if (now >= this.nextTick) {
        this.port.postMessage({type: 'tick', time: now});
        this.nextTick = now + 0.1; // 100ms
    }

    return true; // Keep alive
  }
}
registerProcessor('keep-alive-processor', KeepAliveProcessor);
