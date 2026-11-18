import MWDATCore
import SwiftUI

@main
struct GlassesLiveApp: App {
    @State private var controller: LiveController

    init() {
        do {
            try Wearables.configure()
        } catch {
            NSLog("[GlassesLive] Failed to configure Wearables SDK: \(error)")
        }
        _controller = State(wrappedValue: LiveController(wearables: Wearables.shared))
    }

    var body: some Scene {
        WindowGroup {
            ContentView(controller: controller)
                .onOpenURL { url in
                    // Meta AI calls back here after registration / permission prompts.
                    guard
                        let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                        components.queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true
                    else { return }
                    Task {
                        do {
                            _ = try await Wearables.shared.handleUrl(url)
                        } catch {
                            controller.report(error.localizedDescription)
                        }
                    }
                }
        }
    }
}
