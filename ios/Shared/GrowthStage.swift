import Foundation

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

    /// 지정한 번들(언어)로 만든 단계 이름. 문구는 iOS·Watch·위젯이 함께 쓰는 `Shared` 테이블에 있다.
    func displayName(in bundle: Bundle) -> String {
        switch self {
        case .seed: String(localized: "씨앗", table: "Shared", bundle: bundle)
        case .sprout: String(localized: "새싹", table: "Shared", bundle: bundle)
        case .sapling: String(localized: "줄기", table: "Shared", bundle: bundle)
        case .young: String(localized: "어린나무", table: "Shared", bundle: bundle)
        case .tree: String(localized: "나무", table: "Shared", bundle: bundle)
        case .blossom: String(localized: "개화", table: "Shared", bundle: bundle)
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
