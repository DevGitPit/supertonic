import SwiftUI

struct ContentView: View {
    @StateObject private var vm = TTSViewModel()

    var body: some View {
        ZStack {
            LinearGradient(gradient: Gradient(colors: [Color(.systemBackground), Color(.secondarySystemBackground)]), startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()

            VStack(spacing: 20) {
                Spacer()

                VStack(spacing: 12) {
                    Text("Supertonic 2 iOS Demo")
                        .font(.title2.weight(.semibold))
                        .foregroundColor(.primary)

                    TextEditor(text: $vm.text)
                        .frame(minHeight: 120, maxHeight: 180)
                        .padding(8)
                        .background(Color(.secondarySystemBackground))
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.secondary.opacity(0.3), lineWidth: 1)
                        )
                        .padding(.horizontal)

                    HStack(spacing: 12) {
                        Text("NFE")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Slider(value: $vm.nfe, in: 2...15, step: 1)
                        Text("\(Int(vm.nfe))")
                            .font(.subheadline.monospacedDigit())
                            .frame(width: 36)
                    }
                    .padding(.horizontal)

                    Picker("Voice", selection: $vm.voice) {
                        Text("M").tag(TTSService.Voice.male)
                        Text("F").tag(TTSService.Voice.female)
                    }
                    .pickerStyle(SegmentedPickerStyle())
                    .padding(.horizontal)
                    
                    HStack(spacing: 12) {
                        Text("Language")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Picker("Language", selection: $vm.language) {
                            ForEach(TTSService.Language.allCases, id: \.self) { lang in
                                Text(lang.displayName).tag(lang)
                            }
                        }
                        .pickerStyle(MenuPickerStyle())
                    }
                    .padding(.horizontal)
                }

                HStack(spacing: 16) {
                    Button(action: { vm.generate() }) {
                        Label(vm.isGenerating ? "Generating..." : "Generate", systemImage: vm.isGenerating ? "hourglass" : "wand.and.stars"
                        )
                        .labelStyle(.titleAndIcon)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.accentColor)
                    .disabled(vm.isGenerating)

                    Button(action: { vm.togglePlay() }) {
                        Label(vm.isPlaying ? "Stop" : "Play", systemImage: vm.isPlaying ? "stop.fill" : "play.fill")
                    }
                    .buttonStyle(.bordered)
                    .disabled(vm.audioURL == nil)
                }

                if let rtf = vm.rtfText {
                    Text(rtf)
                        .font(.footnote.monospacedDigit())
                        .foregroundColor(.secondary)
                        .padding(.top, 2)
                }

                if let error = vm.errorMessage {
                    Text(error)
                        .foregroundColor(.red)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()
            }
        }
        .onAppear { vm.startup() }
    }
}
