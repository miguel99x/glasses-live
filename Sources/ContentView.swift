import SwiftUI

struct ContentView: View {
    @Bindable var controller: LiveController
    @State private var showSettings = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                preview
                Text(controller.statusLine)
                    .font(.headline)
                    .foregroundStyle(controller.isLive ? .red : .primary)
                if controller.isLive, !controller.audioNote.isEmpty {
                    Label(controller.audioNote, systemImage: "mic")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                controls
                Spacer(minLength: 0)
            }
            .padding()
            .navigationTitle("Glasses Live")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .disabled(controller.isLive)
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView(settings: controller.settings, controller: controller)
            }
            .alert("Something went wrong", isPresented: $controller.showError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(controller.errorMessage)
            }
            .alert("Allow camera in Meta AI?", isPresented: $controller.showPermissionPrompt) {
                Button("Open Meta AI") { Task { await controller.requestCameraPermission() } }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Meta AI will open so you can let Glasses Live use your glasses camera. Choose \"Allow always\" so you only do this once.")
            }
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { controller.settings.save() }
            }
        }
    }

    private var preview: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.black)
            if let image = controller.previewImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 16))
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "eyeglasses")
                        .font(.system(size: 44))
                    Text(controller.isBusy ? "Connecting..." : "Glasses camera is off")
                }
                .foregroundStyle(.white.opacity(0.7))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if controller.isLive {
                LiveBadge(since: controller.liveSince)
                    .padding(12)
            }
        }
        .aspectRatio(9.0 / 16.0, contentMode: .fit)
        .frame(maxHeight: 460)
    }

    @ViewBuilder
    private var controls: some View {
        if !controller.isRegistered {
            Button {
                controller.connectMetaAI()
            } label: {
                Label("Connect to Meta AI", systemImage: "link").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Text("Meta AI opens so you can approve this app. Turn on Developer Mode in Meta AI first.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        } else {
            HStack(spacing: 12) {
                Button {
                    Task {
                        if controller.isCameraOn { await controller.stopCamera() } else { await controller.startCamera() }
                    }
                } label: {
                    Label(controller.isCameraOn ? "Camera off" : "Camera on",
                          systemImage: controller.isCameraOn ? "video.slash" : "video")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .disabled(controller.isBusy || controller.isLive)

                Button {
                    Task {
                        if controller.isLive { await controller.endBroadcast() } else { await controller.goLive() }
                    }
                } label: {
                    Label(controller.isLive ? "End stream" : "Go live",
                          systemImage: controller.isLive ? "stop.circle.fill" : "dot.radiowaves.left.and.right")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .controlSize(.large)
                .disabled(!controller.isCameraOn || (controller.isBusy && !controller.isLive))
            }
            if !controller.settings.isReadyToStream {
                Button("Add your stream key in Settings") { showSettings = true }
                    .font(.footnote)
            }
        }
    }
}

private struct LiveBadge: View {
    let since: Date?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            HStack(spacing: 6) {
                Circle().fill(.white).frame(width: 8, height: 8)
                Text("LIVE")
                    .fontWeight(.bold)
                if let since {
                    Text(Self.format(context.date.timeIntervalSince(since)))
                        .monospacedDigit()
                }
            }
            .font(.caption)
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(.red, in: Capsule())
        }
    }

    private static func format(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            : String(format: "%02d:%02d", s / 60, s % 60)
    }
}

struct SettingsView: View {
    @Bindable var settings: StreamSettings
    let controller: LiveController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Site", selection: Binding(
                        get: { settings.platform },
                        set: { settings.selectPlatform($0) }
                    )) {
                        ForEach(StreamPlatform.allCases) { Text($0.label).tag($0) }
                    }
                    TextField("Server URL (rtmp:// or rtmps://)", text: $settings.server)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    SecureField("Stream key", text: $settings.streamKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Where to stream")
                } footer: {
                    Text("Copy the stream key from your site's creator dashboard. It's stored only in this phone's Keychain. For Kick, choose Custom and paste both the server URL and key from Kick's dashboard.")
                }

                Section("Video") {
                    Picker("Quality", selection: $settings.quality) {
                        ForEach(Quality.allCases) { Text($0.label).tag($0) }
                    }
                    Picker("Frame rate", selection: $settings.frameRate) {
                        ForEach([15, 24, 30], id: \.self) { Text("\($0) fps").tag($0) }
                    }
                    Picker("Layout", selection: $settings.layout) {
                        ForEach(StreamLayout.allCases) { Text($0.label).tag($0) }
                    }
                }

                Section {
                    Picker("Audio", selection: $settings.audioSource) {
                        ForEach(AudioSource.allCases) { Text($0.label).tag($0) }
                    }
                } header: {
                    Text("Audio")
                } footer: {
                    Text("Lower quality and frame rate look sharper: the glasses send video over Bluetooth, which squeezes high settings harder. Quality changes apply the next time you turn the camera on.")
                }

                Section {
                    Button("Disconnect from Meta AI", role: .destructive) {
                        controller.disconnectMetaAI()
                        dismiss()
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        settings.save()
                        dismiss()
                    }
                }
            }
        }
    }
}
