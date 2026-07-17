import XCTest

final class GrowthStageTests: XCTestCase {
    func test_stage_mapsLevelRangesToStages() {
        XCTAssertEqual(GrowthStage.stage(for: 1), .seed)
        XCTAssertEqual(GrowthStage.stage(for: 2), .seed)
        XCTAssertEqual(GrowthStage.stage(for: 3), .sprout)
        XCTAssertEqual(GrowthStage.stage(for: 5), .sprout)
        XCTAssertEqual(GrowthStage.stage(for: 6), .sapling)
        XCTAssertEqual(GrowthStage.stage(for: 9), .sapling)
        XCTAssertEqual(GrowthStage.stage(for: 10), .young)
        XCTAssertEqual(GrowthStage.stage(for: 14), .young)
        XCTAssertEqual(GrowthStage.stage(for: 15), .tree)
        XCTAssertEqual(GrowthStage.stage(for: 19), .tree)
        XCTAssertEqual(GrowthStage.stage(for: 20), .blossom)
        XCTAssertEqual(GrowthStage.stage(for: 99), .blossom)
    }

    func test_stage_clampsInvalidLevelsToSeed() {
        XCTAssertEqual(GrowthStage.stage(for: 0), .seed)
        XCTAssertEqual(GrowthStage.stage(for: -5), .seed)
    }

    func test_next_returnsUpcomingStage() {
        XCTAssertEqual(GrowthStage.next(after: 1), .sprout)
        XCTAssertEqual(GrowthStage.next(after: 3), .sapling)
        XCTAssertEqual(GrowthStage.next(after: 19), .blossom)
        XCTAssertNil(GrowthStage.next(after: 20), "마지막 단계 이후에는 다음 단계가 없어야 합니다")
        XCTAssertEqual(GrowthStage.next(after: 0), .sprout, "레벨 0 이하도 씨앗 기준으로 다음 단계를 계산해야 합니다")
    }

    func test_displayName_mapsEveryStageToKorean() {
        XCTAssertEqual(GrowthStage.seed.displayName, "씨앗")
        XCTAssertEqual(GrowthStage.sprout.displayName, "새싹")
        XCTAssertEqual(GrowthStage.sapling.displayName, "줄기")
        XCTAssertEqual(GrowthStage.young.displayName, "어린나무")
        XCTAssertEqual(GrowthStage.tree.displayName, "나무")
        XCTAssertEqual(GrowthStage.blossom.displayName, "개화")
    }
}
