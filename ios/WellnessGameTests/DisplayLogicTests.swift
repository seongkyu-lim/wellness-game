import XCTest

final class CharacterXpProgressTests: XCTestCase {
    private func makeCharacter(currentXp: Int, nextLevelXp: Int) -> CharacterState {
        CharacterState(
            level: 3,
            currentXp: currentXp,
            totalXp: 500,
            nextLevelXp: nextLevelXp,
            stats: CharacterStats(str: 1, vit: 2, intStat: 3, discipline: 4, recovery: 5)
        )
    }

    func test_xpProgress_returnsFractionOfNextLevelXp() {
        let character = makeCharacter(currentXp: 50, nextLevelXp: 200)
        XCTAssertEqual(character.xpProgress, 0.25, accuracy: 0.0001)
    }

    func test_xpProgress_returnsZeroWhenNextLevelXpIsZero() {
        let character = makeCharacter(currentXp: 50, nextLevelXp: 0)
        XCTAssertEqual(character.xpProgress, 0)
    }

    func test_xpProgress_clampsToOneWhenXpExceedsNextLevel() {
        let character = makeCharacter(currentXp: 300, nextLevelXp: 200)
        XCTAssertEqual(character.xpProgress, 1.0)
    }

    func test_xpProgress_clampsToZeroForNegativeXp() {
        let character = makeCharacter(currentXp: -10, nextLevelXp: 200)
        XCTAssertEqual(character.xpProgress, 0)
    }
}

final class SleepDurationTextTests: XCTestCase {
    private func makeSnapshot(sleep: HealthActivityDTO?) -> DailyHealthSnapshot {
        DailyHealthSnapshot(date: Date(timeIntervalSince1970: 0), steps: 1000, workouts: [], sleep: sleep)
    }

    func test_sleepDurationText_formatsHoursAndMinutes() {
        let snapshot = makeSnapshot(sleep: HealthActivityDTO(type: .sleep, sleepMinutes: 450))
        XCTAssertEqual(snapshot.sleepDurationText(in: .localized("ko")), "7시간 30분")
        XCTAssertEqual(snapshot.sleepDurationText(in: .localized("en")), "7h 30m")
    }

    func test_sleepDurationText_formatsUnderOneHour() {
        let snapshot = makeSnapshot(sleep: HealthActivityDTO(type: .sleep, sleepMinutes: 45))
        XCTAssertEqual(snapshot.sleepDurationText(in: .localized("ko")), "0시간 45분")
        XCTAssertEqual(snapshot.sleepDurationText(in: .localized("en")), "0h 45m")
    }

    func test_sleepDurationText_returnsPlaceholderWhenNoSleepRecord() {
        let snapshot = makeSnapshot(sleep: nil)
        XCTAssertEqual(snapshot.sleepDurationText(in: .localized("ko")), "기록 없음")
        XCTAssertEqual(snapshot.sleepDurationText(in: .localized("en")), "No data")
    }

    func test_sleepDurationText_returnsPlaceholderWhenSleepMinutesMissing() {
        let snapshot = makeSnapshot(sleep: HealthActivityDTO(type: .sleep, sleepMinutes: nil))
        XCTAssertEqual(snapshot.sleepDurationText(in: .localized("ko")), "기록 없음")
    }
}

final class XPBarFillWidthTests: XCTestCase {
    func test_fillWidth_keepsMinimumVisibleFillAtZeroProgress() {
        XCTAssertEqual(XPBarView.fillWidth(progress: 0, totalWidth: 300), XPBarView.minimumFillWidth, accuracy: 0.0001)
    }

    func test_fillWidth_scalesMidRangeProgress() {
        XCTAssertEqual(XPBarView.fillWidth(progress: 0.5, totalWidth: 300), 150, accuracy: 0.0001)
    }

    func test_fillWidth_clampsToTrackWidthAboveFullProgress() {
        XCTAssertEqual(XPBarView.fillWidth(progress: 1.2, totalWidth: 300), 300, accuracy: 0.0001)
    }

    func test_fillWidth_neverExceedsTrackNarrowerThanMinimum() {
        XCTAssertEqual(XPBarView.fillWidth(progress: 0, totalWidth: 4), 4, accuracy: 0.0001)
    }
}

final class CharacterAvatarPathTests: XCTestCase {
    func test_svg_parsesAbsoluteLineCommandsIntoClosedShape() {
        let path = CharacterAvatarView.svg("M10 20 H40 V60 L10 60 Z")
        XCTAssertEqual(path.boundingRect, CGRect(x: 10, y: 20, width: 30, height: 40))
    }

    func test_svg_parsesQuadraticCurveEndpoints() {
        let path = CharacterAvatarView.svg("M0 10 Q5 0 10 10")
        XCTAssertEqual(path.currentPoint, CGPoint(x: 10, y: 10))
        XCTAssertEqual(path.boundingRect.maxX, 10, accuracy: 0.0001)
    }
}

final class WorkoutTypePresentationTests: XCTestCase {
    func test_displayName_mapsEveryWorkoutTypeToKorean() {
        let ko = Bundle.localized("ko")
        XCTAssertEqual(WorkoutType.swimming.displayName(in: ko), "수영")
        XCTAssertEqual(WorkoutType.running.displayName(in: ko), "달리기")
        XCTAssertEqual(WorkoutType.walking.displayName(in: ko), "걷기")
        XCTAssertEqual(WorkoutType.cycling.displayName(in: ko), "자전거")
        XCTAssertEqual(WorkoutType.strengthTraining.displayName(in: ko), "근력 운동")
        XCTAssertEqual(WorkoutType.other.displayName(in: ko), "운동")
    }

    func test_displayName_mapsEveryWorkoutTypeToEnglish() {
        let en = Bundle.localized("en")
        XCTAssertEqual(WorkoutType.swimming.displayName(in: en), "Swimming")
        XCTAssertEqual(WorkoutType.running.displayName(in: en), "Running")
        XCTAssertEqual(WorkoutType.walking.displayName(in: en), "Walking")
        XCTAssertEqual(WorkoutType.cycling.displayName(in: en), "Cycling")
        XCTAssertEqual(WorkoutType.strengthTraining.displayName(in: en), "Strength training")
        XCTAssertEqual(WorkoutType.other.displayName(in: en), "Workout")
    }

    func test_iconName_mapsEveryWorkoutTypeToValidSFSymbol() {
        let types: [WorkoutType] = [.swimming, .running, .walking, .cycling, .strengthTraining, .other]
        for type in types {
            let name = type.iconName
            XCTAssertNotNil(UIImage(systemName: name), "'\(name)' is not a valid SF Symbol for \(type)")
        }
    }
}
