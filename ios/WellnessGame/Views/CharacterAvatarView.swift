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
/// iOS와 웹(web/src/components/CharacterAvatar.tsx)은 같은 좌표계(SVG viewBox 120×120)와 같은 경로 문자열을 공유한다.
/// 웹 그림을 고치면 이 파일의 경로·색도 함께 고쳐야 한다.
struct CharacterAvatarView: View {
    let level: Int
    var size: CGFloat = 76
    /// 장식용으로 쓸 때 VoiceOver에서 숨긴다.
    var decorative = false

    var body: some View {
        let stage = GrowthStage.stage(for: level)
        Canvas { context, canvasSize in
            context.scaleBy(x: canvasSize.width / 120, y: canvasSize.height / 120)
            Self.draw(&context, stage: stage)
        }
        .frame(width: size, height: size)
        .accessibilityElement()
        .accessibilityLabel(String(localized: "\(stage.displayName) 단계 캐릭터"))
        .accessibilityAddTraits(.isImage)
        .accessibilityHidden(decorative)
    }

    // MARK: - 팔레트 (셀 애니메이션풍 고정색 — 다크 모드에서도 유지한다)

    private enum C {
        static let ink = Color(hex: 0x1F2340)
        static let potBody = Color(hex: 0xF4A06C)
        static let potShade = Color(hex: 0xDC7A4C)
        static let potRim = Color(hex: 0xF9BC8A)
        static let potShine = Color(hex: 0xFFE2C6)
        static let blush = Color(hex: 0xFF8FB1)
        static let mouth = Color(hex: 0xE8577F)
        static let stem = Color(hex: 0x5BB04F)
        static let leaf = Color(hex: 0x8ED65A)
        static let leafTip = Color(hex: 0xB4EA7A)
        static let canopy = Color(hex: 0x7DCB55)
        static let canopyBig = Color(hex: 0x6CC04A)
        static let canopyShade = Color(hex: 0x5AA845)
        static let canopyBigShade = Color(hex: 0x4E9E3E)
        static let canopyShine = Color(hex: 0xBDEE90)
        static let trunk = Color(hex: 0xA8744A)
        static let seed = Color(hex: 0xC8935E)
        static let petal = Color(hex: 0xFFB3CF)
        static let petalCore = Color(hex: 0xFF6FA5)
        static let eyeShine = Color(hex: 0xFFFFFF)
    }

    private enum Face { case sleep, open, happy }

    /// 단계마다 표정이 다르다: 씨앗은 잠든 얼굴, 어린나무·개화는 웃는 얼굴.
    private static func face(for stage: GrowthStage) -> Face {
        switch stage {
        case .seed: .sleep
        case .sprout, .sapling, .tree: .open
        case .young, .blossom: .happy
        }
    }

    private static func draw(_ ctx: inout GraphicsContext, stage: GrowthStage) {
        ctx.fill(ellipse(60, 112, 30, 4), with: .color(C.ink.opacity(0.18)))
        // 식물을 먼저 그려 줄기 아래쪽이 화분 테두리에 가려지게 한다
        drawPlant(&ctx, stage: stage)
        drawPot(&ctx)
        drawFace(&ctx, face(for: stage))
    }

    // MARK: - 그리기 도구 (기본 3pt 잉크 선, 둥근 이음)

    /// 채우고(fill이 있으면) 잉크 선을 긋는다. line이 nil이면 선을 생략한다(SVG stroke="none").
    private static func paint(_ ctx: inout GraphicsContext, _ path: Path, fill: Color?, line: CGFloat? = 3) {
        if let fill {
            ctx.fill(path, with: .color(fill))
        }
        if let line {
            ctx.stroke(path, with: .color(C.ink), style: StrokeStyle(lineWidth: line, lineCap: .round, lineJoin: .round))
        }
    }

    private static func ellipse(_ cx: CGFloat, _ cy: CGFloat, _ rx: CGFloat, _ ry: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2))
    }

    private static func rect(_ x: CGFloat, _ y: CGFloat, _ width: CGFloat, _ height: CGFloat, rx: CGFloat) -> Path {
        Path(roundedRect: CGRect(x: x, y: y, width: width, height: height), cornerRadius: rx, style: .circular)
    }

    // MARK: - 화분

    private static func drawPot(_ ctx: inout GraphicsContext) {
        paint(&ctx, svg("M38 76 H82 L77 104 Q76 109 71 109 H49 Q44 109 43 104 Z"), fill: C.potBody)
        paint(&ctx, svg("M66 77.5 H80 L75.5 103 Q74.5 107 70 107 H63 Q68 94 66 77.5 Z"), fill: C.potShade, line: nil)
        paint(&ctx, rect(33, 68, 54, 12, rx: 5), fill: C.potRim)
        paint(&ctx, rect(38, 71, 20, 3, rx: 1.5), fill: C.potShine, line: nil)
        paint(&ctx, ellipse(44, 97, 4.2, 2.3), fill: C.blush, line: nil)
        paint(&ctx, ellipse(76, 97, 4.2, 2.3), fill: C.blush, line: nil)
    }

    // MARK: - 표정

    private static func drawFace(_ ctx: inout GraphicsContext, _ face: Face) {
        switch face {
        case .sleep:
            paint(&ctx, svg("M46.5 90 Q51 93 55.5 90"), fill: nil, line: 2.4)
            paint(&ctx, svg("M64.5 90 Q69 93 73.5 90"), fill: nil, line: 2.4)
            paint(&ctx, svg("M58 98 Q60 99.5 62 98"), fill: nil, line: 2)
        case .happy:
            paint(&ctx, svg("M46.5 91 Q51 85 55.5 91"), fill: nil, line: 2.6)
            paint(&ctx, svg("M64.5 91 Q69 85 73.5 91"), fill: nil, line: 2.6)
            paint(&ctx, svg("M55.5 95.5 Q60 103 64.5 95.5 Z"), fill: C.mouth, line: 2.2)
        case .open:
            paint(&ctx, ellipse(51, 90, 4.2, 5.6), fill: C.ink, line: nil)
            paint(&ctx, ellipse(69, 90, 4.2, 5.6), fill: C.ink, line: nil)
            paint(&ctx, ellipse(52.4, 87.8, 1.7, 1.7), fill: C.eyeShine, line: nil)
            paint(&ctx, ellipse(70.4, 87.8, 1.7, 1.7), fill: C.eyeShine, line: nil)
            paint(&ctx, ellipse(49.8, 92.4, 0.8, 0.8), fill: C.eyeShine, line: nil)
            paint(&ctx, ellipse(67.8, 92.4, 0.8, 0.8), fill: C.eyeShine, line: nil)
            paint(&ctx, svg("M57 97 Q60 100.5 63 97"), fill: nil, line: 2.2)
        }
    }

    // MARK: - 단계별 식물

    private static let flowers: [(CGFloat, CGFloat)] = [(36, 30), (62, 12), (86, 30), (48, 44), (76, 44), (60, 30)]

    private static func drawPlant(_ ctx: inout GraphicsContext, stage: GrowthStage) {
        switch stage {
        case .seed:
            paint(&ctx, ellipse(60, 64, 11, 9), fill: C.seed)
            paint(&ctx, svg("M55 60 Q60 56 65 60"), fill: nil, line: 2)

        case .sprout:
            paint(&ctx, rect(57, 48, 6, 24, rx: 3), fill: C.stem)
            paint(&ctx, svg("M58 58 Q44 58 40 46 Q54 42 58 58 Z"), fill: C.leaf)
            paint(&ctx, svg("M62 54 Q74 52 80 40 Q66 38 62 54 Z"), fill: C.leaf)

        case .sapling:
            paint(&ctx, rect(57, 30, 6, 42, rx: 3), fill: C.stem)
            paint(&ctx, svg("M58 60 Q44 60 38 48 Q52 44 58 60 Z"), fill: C.leaf)
            paint(&ctx, svg("M62 50 Q76 50 84 38 Q68 34 62 50 Z"), fill: C.leaf)
            paint(&ctx, svg("M58 40 Q48 38 44 28 Q55 26 58 40 Z"), fill: C.leaf)
            paint(&ctx, svg("M60 31 Q54 22 60 12 Q66 22 60 31 Z"), fill: C.leafTip)

        case .young:
            paint(&ctx, svg("M55 72 L57 46 H63 L65 72 Z"), fill: C.trunk)
            paint(
                &ctx,
                svg("M34 46 Q26 30 42 24 Q46 10 62 12 Q78 8 82 24 Q96 30 86 46 Q80 56 60 52 Q40 56 34 46 Z"),
                fill: C.canopy
            )
            paint(&ctx, svg("M61 50.5 Q79 54 85 45 Q91 37 87.5 30 Q79 43 61 45.5 Z"), fill: C.canopyShade, line: nil)
            paint(&ctx, ellipse(47, 24, 7, 3.6), fill: C.canopyShine, line: nil)

        case .tree:
            drawBigCanopy(&ctx)

        case .blossom:
            drawBigCanopy(&ctx)
            for (cx, cy) in flowers {
                paint(&ctx, ellipse(cx, cy, 6, 6), fill: C.petal)
                paint(&ctx, ellipse(cx, cy, 2.2, 2.2), fill: C.petalCore, line: nil)
            }
        }
    }

    private static func drawBigCanopy(_ ctx: inout GraphicsContext) {
        paint(&ctx, svg("M52 72 L55 40 H65 L68 72 Z"), fill: C.trunk)
        paint(
            &ctx,
            svg("M26 44 Q16 26 34 18 Q40 2 60 4 Q80 2 86 18 Q104 26 94 44 Q86 58 60 54 Q34 58 26 44 Z"),
            fill: C.canopyBig
        )
        paint(&ctx, svg("M61 52.5 Q85 56 93 44 Q100 34 96 25 Q86 43 61 47 Z"), fill: C.canopyBigShade, line: nil)
        paint(&ctx, ellipse(42, 18, 8, 4), fill: C.canopyShine, line: nil)
    }

    // MARK: - SVG 경로 문자열

    /// 웹 SVG와 같은 경로 문자열을 그대로 쓰기 위한 최소 파서.
    /// 절대 좌표 M · L · H · V · Q · Z만 지원하며, 명령 문자와 숫자는 공백으로 구분되어 있어야 한다.
    static func svg(_ data: String) -> Path {
        var path = Path()
        var command: Character?
        var numbers: [CGFloat] = []
        var current = CGPoint.zero

        func flush() {
            guard let command else { return }
            switch command {
            case "M" where numbers.count == 2:
                current = CGPoint(x: numbers[0], y: numbers[1])
                path.move(to: current)
            case "L" where numbers.count == 2:
                current = CGPoint(x: numbers[0], y: numbers[1])
                path.addLine(to: current)
            case "H" where numbers.count == 1:
                current.x = numbers[0]
                path.addLine(to: current)
            case "V" where numbers.count == 1:
                current.y = numbers[0]
                path.addLine(to: current)
            case "Q" where numbers.count == 4:
                current = CGPoint(x: numbers[2], y: numbers[3])
                path.addQuadCurve(to: current, control: CGPoint(x: numbers[0], y: numbers[1]))
            case "Z" where numbers.isEmpty:
                path.closeSubpath()
            default:
                assertionFailure("지원하지 않는 SVG 경로: \(command) \(numbers)")
            }
        }

        for token in data.split(separator: " ") {
            if let value = Double(token) {
                numbers.append(CGFloat(value))
            } else {
                flush()
                command = token.first
                numbers = []
                if let value = Double(token.dropFirst()) {
                    numbers.append(CGFloat(value))
                }
            }
        }
        flush()
        return path
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
                Text(verbatim: "Lv.\(level)").font(.caption2)
            }
        }
    }
    .padding()
}
