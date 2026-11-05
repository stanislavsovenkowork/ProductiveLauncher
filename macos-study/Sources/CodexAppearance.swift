import AppKit
import SwiftUI

// Local Codex appearance reference: the installed application's default dark
// theme and its bundled SVG definitions. No Codex settings or UI are modified.
struct CodexGlass: NSViewRepresentable {
    var material: NSVisualEffectView.Material = .sidebar
    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = .behindWindow
        view.state = .followsWindowActiveState
        return view
    }
    func updateNSView(_ view: NSVisualEffectView, context: Context) { view.material = material }
}

struct CodexWindowConfigurator: NSViewRepresentable {
    final class WindowView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.isOpaque = false
            window?.backgroundColor = .clear
            window?.titlebarAppearsTransparent = true
            window?.appearance = NSAppearance(named: .darkAqua)
            window?.isMovableByWindowBackground = true
        }
    }
    func makeNSView(context: Context) -> WindowView { WindowView() }
    func updateNSView(_ view: WindowView, context: Context) { }
}

@MainActor private enum CodexIconAssets {
    static let cache = NSCache<NSString, NSImage>()
    static let symbols: [String: String] = {
        guard let url = Bundle.main.url(forResource: "symbol-map", withExtension: "json", subdirectory: "CodexIcons"),
              let data = try? Data(contentsOf: url), let values = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return values
    }()
    static func image(_ symbol: String) -> NSImage? {
        let name = symbols[symbol] ?? "globe"
        if let cached = cache.object(forKey: name as NSString) { return cached }
        guard let url = Bundle.main.url(forResource: name, withExtension: "svg", subdirectory: "CodexIcons"),
              let image = NSImage(contentsOf: url) else { return nil }
        image.isTemplate = true
        cache.setObject(image, forKey: name as NSString)
        return image
    }
}

struct CodexIcon: View {
    var symbol: String
    var size: CGFloat = 16
    var body: some View {
        Group {
            if let image = CodexIconAssets.image(symbol) {
                Image(nsImage: image).renderingMode(.template).resizable().scaledToFit()
            } else { Color.clear }
        }.frame(width: size, height: size).accessibilityHidden(true)
    }
}

struct DeckLabel: View {
    var title: String
    var icon: String
    init(_ title: String, icon: String) { self.title = title; self.icon = icon }
    var body: some View { HStack(spacing: 6) { CodexIcon(symbol: icon, size: 14); Text(title) } }
}
