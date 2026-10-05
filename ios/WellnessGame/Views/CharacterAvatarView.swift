import SwiftUI

/// 레벨 → 캐릭터 성장 단계. 웹(web/src/lib/growthStage.ts)과 반드시 동일한 구간을 유지해야 한다.
enum GrowthStage: CaseIterable {
    case seed, sprout, sapling, young, tree, blossom

    var minLevel: Int {
        switch self {
        case .seed: 1
        case .sprout: 3
        case .sapling: 6
        case .young: 10
        case .tree: 15
        case .blossom: 20
        }
    }

    var displayName: String {
        displayName(in: .main)
    }

    /// 지정한 번들(언어)로 만든 단계 이름.
    func displayName(in bundle: Bundle) -> String {
        switch self {
        case .seed: String(localized: "씨앗", bundle: bundle)
        case .sprout: String(localized: "새싹", bundle: bundle)
        case .sapling: String(localized: "줄기", bundle: bundle)
        case .young: String(localized: "어린나무", bundle: bundle)
        case .tree: String(localized: "나무", bundle: bundle)
        case .blossom: String(localized: "개화", bundle: bundle)
        }
    }

    static func stage(for level: Int) -> GrowthStage {
        allCases.last { level >= $0.minLevel } ?? .seed
    }

    /// 다음 성장 단계. 마지막 단계면 nil.
    static func next(after level: Int) -> GrowthStage? {
        allCases.first { $0.minLevel > max(level, 1) }
    }
}

/// 레벨에 따라 씨앗 → 개화로 자라는 화분 마스코트 '새싹이'.
/// 웹 CharacterAvatar.tsx의 SVG(viewBox 120×120)와 동일한 좌표계를 사용한다.
struct CharacterAvatarView: View {
    let level: Int
    var size: CGFloat = 76

    var body: some View {
        Canvas { context, canvasSize in
            context.scaleBy(x: canvasSize.width / 120, y: canvasSize.height / 120)
            Self.drawPlant(&context, stage: GrowthStage.stage(for: level))
            Self.drawPot(&context)
        }
        .frame(width: size, height: size)
        .accessibilityLabel(String(localized: "\(GrowthStage.stage(for: level).displayName) 단계 캐릭터"))
    }

    // MARK: - 팔레트 (SVG와 동일한 고정색)

    private enum Palette {
        static let potBody = Color(hex: 0xD98E63)
        static let potRim = Color(hex: 0xC97B52)
        static let soil = Color(hex: 0x7A5A40)
        static let face = Color(hex: 0x5B3A29)
        static let stem = Color(hex: 0x4C8C46)
        static let leafLight = Color(hex: 0x7CC142)
        static let leafDark = Color(hex: 0x5CA843)
        static let trunk = Color(hex: 0x8A6242)
        static let seed = Color(hex: 0xA9805B)
        static let seedLine = Color(hex: 0x8A6242)
        static let petal = Color(hex: 0xF2A5C0)
        static let petalCore = Color(hex: 0xE8709A)
    }

    // MARK: - 화분과 얼굴 (전 단계 공통)

    private static func drawPot(_ ctx: inout GraphicsContext) {
        ctx.fill(Path(ellipseIn: CGRect(x: 41, y: 70.5, width: 38, height: 9)), with: .color(Palette.soil))

        var pot = Path()
        pot.move(to: CGPoint(x: 38, y: 79))
        pot.addLine(to: CGPoint(x: 82, y: 79))
        pot.addLine(to: CGPoint(x: 77, y: 101))
        pot.addQuadCurve(to: CGPoint(x: 43, y: 101), control: CGPoint(x: 60, y: 106))
        pot.closeSubpath()
        ctx.fill(pot, with: .color(Palette.potBody))

        ctx.fill(
            Path(roundedRect: CGRect(x: 35, y: 73, width: 50, height: 9), cornerRadius: 4.5),
            with: .color(Palette.potRim)
        )

        ctx.fill(circle(x: 53, y: 91, r: 2.3), with: .color(Palette.face))
        ctx.fill(circle(x: 67, y: 91, r: 2.3), with: .color(Palette.face))
        var smile = Path()
        smile.move(to: CGPoint(x: 55, y: 96))
        smile.addQuadCurve(to: CGPoint(x: 65, y: 96), control: CGPoint(x: 60, y: 100))
        ctx.stroke(smile, with: .color(Palette.face), style: StrokeStyle(lineWidth: 2, lineCap: .round))
    }

    // MARK: - 단계별 식물

    private static func drawPlant(_ ctx: inout GraphicsContext, stage: GrowthStage) {
        switch stage {
        case .seed:
            ctx.fill(Path(ellipseIn: CGRect(x: 52.5, y: 56.5, width: 15, height: 19)), with: .color(Palette.seed))
            var crack = Path()
            crack.move(to: CGPoint(x: 60, y: 58))
            crack.addQuadCurve(to: CGPoint(x: 60, y: 70), control: CGPoint(x: 60, y: 62))
            ctx.stroke(crack, with: .color(Palette.seedLine), style: StrokeStyle(lineWidth: 1.6))
            var tip = Path()
            tip.move(to: CGPoint(x: 60, y: 58))
            tip.addQuadCurve(to: CGPoint(x: 62, y: 47), control: CGPoint(x: 57, y: 51))
            ctx.stroke(tip, with: .color(Palette.stem), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))

        case .sprout:
            drawStem(&ctx, from: 76, to: 56, width: 3.5)
            drawLeafPair(&ctx, baseY: 60, tipY: 48, spreadX: 17, tipInnerY: 46, returnY: 58)

        case .sapling:
            drawStem(&ctx, from: 76, to: 40, width: 3.8)
            drawLeafPair(&ctx, baseY: 64, tipY: 54, spreadX: 16, tipInnerY: 52, returnY: 62)
            drawLeafPair(&ctx, baseY: 48, tipY: 40, spreadX: 13, tipInnerY: 38, returnY: 46)

        case .young:
            ctx.fill(Path(CGRect(x: 58, y: 50, width: 4, height: 26)), with: .color(Palette.trunk))
            ctx.fill(circle(x: 60, y: 40, r: 17), with: .color(Palette.leafDark))
            ctx.fill(circle(x: 49, y: 47, r: 10), with: .color(Palette.leafLight))
            ctx.fill(circle(x: 71, y: 46, r: 9), with: .color(Palette.leafLight))

        case .tree:
            drawTreeBody(&ctx)

        case .blossom:
            drawTreeBody(&ctx)
            for point in [(50, 30), (68, 24), (78, 38), (42, 46), (62, 42)] {
                drawFlower(&ctx, x: CGFloat(point.0), y: CGFloat(point.1))
            }
        }
    }

    private static func drawTreeBody(_ ctx: inout GraphicsContext) {
        ctx.fill(Path(CGRect(x: 57, y: 44, width: 6, height: 32)), with: .color(Palette.trunk))
        var branch = Path()
        branch.move(to: CGPoint(x: 57, y: 56))
        branch.addLine(to: CGPoint(x: 48, y: 48))
        ctx.stroke(branch, with: .color(Palette.trunk), style: StrokeStyle(lineWidth: 3, lineCap: .round))
        ctx.fill(circle(x: 60, y: 32, r: 19), with: .color(Palette.leafDark))
        ctx.fill(circle(x: 44, y: 42, r: 13), with: .color(Palette.leafLight))
        ctx.fill(circle(x: 76, y: 41, r: 13), with: .color(Palette.leafLight))
    }

    private static func drawStem(_ ctx: inout GraphicsContext, from bottomY: CGFloat, to topY: CGFloat, width: CGFloat) {
        var stem = Path()
        stem.move(to: CGPoint(x: 60, y: bottomY))
        stem.addQuadCurve(to: CGPoint(x: 60, y: topY), control: CGPoint(x: 60, y: (bottomY + topY) / 2))
        ctx.stroke(stem, with: .color(Palette.stem), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }

    /// 줄기 좌우로 잎 한 쌍을 그린다. (왼쪽 밝은 잎, 오른쪽 어두운 잎)
    private static func drawLeafPair(
        _ ctx: inout GraphicsContext,
        baseY: CGFloat,
        tipY: CGFloat,
        spreadX: CGFloat,
        tipInnerY: CGFloat,
        returnY: CGFloat
    ) {
        var left = Path()
        left.move(to: CGPoint(x: 60, y: baseY))
        left.addQuadCurve(to: CGPoint(x: 60 - spreadX, y: tipY), control: CGPoint(x: 60 - spreadX * 0.75, y: baseY + 1))
        left.addQuadCurve(to: CGPoint(x: 60, y: returnY), control: CGPoint(x: 57, y: tipInnerY))
        left.closeSubpath()
        ctx.fill(left, with: .color(Palette.leafLight))

        var right = Path()
        right.move(to: CGPoint(x: 60, y: baseY))
        right.addQuadCurve(to: CGPoint(x: 60 + spreadX, y: tipY), control: CGPoint(x: 60 + spreadX * 0.75, y: baseY + 1))
        right.addQuadCurve(to: CGPoint(x: 60, y: returnY), control: CGPoint(x: 63, y: tipInnerY))
        right.closeSubpath()
        ctx.fill(right, with: .color(Palette.leafDark))
    }

    private static func drawFlower(_ ctx: inout GraphicsContext, x: CGFloat, y: CGFloat) {
        for offset in [(-3, 0), (3, 0), (0, -3), (0, 3)] {
            ctx.fill(circle(x: x + CGFloat(offset.0), y: y + CGFloat(offset.1), r: 2.6), with: .color(Palette.petal))
        }
        ctx.fill(circle(x: x, y: y, r: 2), with: .color(Palette.petalCore))
    }

    private static func circle(x: CGFloat, y: CGFloat, r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2))
    }
}

private extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

#Preview {
    HStack(spacing: 8) {
        ForEach([1, 3, 6, 10, 15, 20], id: \.self) { level in
            VStack {
                CharacterAvatarView(level: level, size: 60)
                Text("Lv.\(level)").font(.caption2)
            }
        }
    }
    .padding()
}
