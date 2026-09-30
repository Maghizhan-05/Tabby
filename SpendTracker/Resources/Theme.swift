import SwiftUI

/// Tabby's dark, wealth-forward design system. Gold is reserved for focus,
/// progress, and confirmation so it feels like a signal rather than decoration.
enum Theme {
    static let ink = Color(red: 0.94, green: 0.93, blue: 0.89)
    static let paper = Color(red: 0.035, green: 0.039, blue: 0.055)
    static let surface = Color(red: 0.075, green: 0.080, blue: 0.105)
    static let elevatedSurface = Color(red: 0.105, green: 0.110, blue: 0.140)
    static let subtleInk = Color(red: 0.61, green: 0.60, blue: 0.64)
    static let hairline = Color.white.opacity(0.11)

    static let accent = Color(red: 0.90, green: 0.66, blue: 0.22)
    static let accentBright = Color(red: 1.0, green: 0.84, blue: 0.43)
    static let accentDim = Color(red: 0.48, green: 0.32, blue: 0.10)
    static let accentGlow = accent.opacity(0.22)

    static let ringColors: [Color] = [
        accentBright,
        Color(red: 0.97, green: 0.50, blue: 0.20),
        Color(red: 0.67, green: 0.45, blue: 0.96),
        Color(red: 0.25, green: 0.70, blue: 0.72),
        Color(red: 0.96, green: 0.34, blue: 0.46),
        Color(red: 0.56, green: 0.73, blue: 0.35),
        Color(red: 0.77, green: 0.76, blue: 0.72),
    ]

    static let cardShape = RoundedRectangle(cornerRadius: 24, style: .continuous)
    static let controlShape = RoundedRectangle(cornerRadius: 16, style: .continuous)
}

/// A quiet grid mesh makes separate surfaces feel part of one space.
struct TabbyBackdrop: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var glowShift = false

    var body: some View {
        ZStack {
            Theme.paper

            RadialGradient(
                colors: [Theme.accentGlow, .clear],
                center: glowShift ? .topTrailing : .topLeading,
                startRadius: 24,
                endRadius: 360
            )
            .blur(radius: 18)

            TabbyGrid()
                .opacity(0.46)
        }
        .ignoresSafeArea()
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 7).repeatForever(autoreverses: true)) {
                glowShift = true
            }
        }
    }
}

private struct TabbyGrid: View {
    var body: some View {
        Canvas { context, size in
            let spacing: CGFloat = 34
            var path = Path()
            for x in stride(from: -size.height, through: size.width + size.height, by: spacing) {
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x + size.height, y: size.height))
            }
            for y in stride(from: 0, through: size.height * 2, by: spacing) {
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y - size.width))
            }
            context.stroke(path, with: .color(.white.opacity(0.045)), lineWidth: 0.6)
        }
    }
}

/// The single shared "orbit" identity used in headers, focused controls, and rings.
struct TabbyOrbit: View {
    var size: CGFloat = 34
    var lineWidth: CGFloat = 3

    var body: some View {
        ZStack {
            Circle()
                .stroke(Theme.accent.opacity(0.18), lineWidth: lineWidth)
            Circle()
                .trim(from: 0.08, to: 0.73)
                .stroke(
                    AngularGradient(
                        colors: [Theme.accentBright, Theme.accent, Theme.accent.opacity(0.25)],
                        center: .center
                    ),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
                .rotationEffect(.degrees(-88))
            Circle()
                .fill(Theme.accentBright)
                .frame(width: lineWidth + 2, height: lineWidth + 2)
                .offset(x: size * 0.32, y: -size * 0.16)
                .shadow(color: Theme.accentGlow, radius: 6)
        }
        .frame(width: size, height: size)
    }
}
