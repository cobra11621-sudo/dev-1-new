package com.cobra.dev1new.domain

enum class TransportKind { BUS, KTX, SUBWAY }
enum class JourneyId(val label: String, val tabLabel: String) {
    COMMUTE("출근", "출근"),
    RETURN("퇴근", "퇴근"),
    HOSPITAL_OUTBOUND("병원 가는 길", "병원"),
    HOSPITAL_RETURN("돌아오는 길", "병원")
}

data class GeoPoint(val latitude: Double, val longitude: Double)

data class TransportLeg(
    val id: String,
    val kind: TransportKind,
    val displayName: String,
    val origin: String,
    val destination: String,
    val stops: List<String>,
    val routePoints: List<GeoPoint> = emptyList(),
    val routeNumber: String? = null,
    val boardingStopId: String? = null,
    val routeId: String? = null
)

data class JourneyDefinition(
    val id: JourneyId,
    val legs: List<TransportLeg>,
    val subwayManualBoarding: Boolean = false
)

/** Immutable route metadata. Transit progress and UI never own competing copies of it. */
object RouteCatalog {
    private val ktxCommutePoints = listOf(
        GeoPoint(36.3322, 127.4341),
        GeoPoint(36.3010, 127.5680),
        GeoPoint(36.1740, 127.7860),
        GeoPoint(36.1136, 128.1815),
        GeoPoint(35.9900, 128.3970),
        GeoPoint(35.8798, 128.6283)
    )
    private val ktxStopsCommute = listOf(
        "대전역", "옥천 지역", "영동 지역", "김천·구미 지역", "칠곡 지역", "동대구역"
    )
    private val subwayCommute = listOf(
        "동대구역", "동구청역", "아양교역", "동촌역", "해안역", "방촌역",
        "용계역", "율하역", "신기역", "반야월역", "각산역", "안심역"
    )
    private val subwayReturn = subwayCommute.reversed()

    val commute = JourneyDefinition(
        id = JourneyId.COMMUTE,
        subwayManualBoarding = true,
        legs = listOf(
            TransportLeg(
                id = "commute_514", kind = TransportKind.BUS, displayName = "514번 버스",
                origin = "한밭초등학교", destination = "대전역", routeNumber = "514",
                stops = listOf(
                    "한밭초등학교", "탄방중학교", "남선공원종합체육관", "남선공원네거리",
                    "중촌초등학교", "중촌동주민센터", "세명요양병원", "대전여상",
                    "중앙로역7번출구", "으능정이거리", "대전역"
                ),
                routePoints = listOf(
                    GeoPoint(36.3551065, 127.3951275), GeoPoint(36.3502501, 127.3951063),
                    GeoPoint(36.3491050, 127.3991641), GeoPoint(36.3457884, 127.4019066),
                    GeoPoint(36.3410765, 127.4100979), GeoPoint(36.3387294, 127.4131431),
                    GeoPoint(36.3351297, 127.4176397), GeoPoint(36.3323209, 127.4211807),
                    GeoPoint(36.3301481, 127.4238703), GeoPoint(36.3294318, 127.4281952),
                    GeoPoint(36.3308245, 127.4318707)
                )
            ),
            TransportLeg(
                id = "commute_ktx", kind = TransportKind.KTX, displayName = "KTX",
                origin = "대전역", destination = "동대구역", stops = ktxStopsCommute,
                routePoints = ktxCommutePoints
            ),
            TransportLeg(
                id = "commute_subway", kind = TransportKind.SUBWAY, displayName = "지하철",
                origin = "동대구역", destination = "안심역", stops = subwayCommute
            ),
            TransportLeg(
                id = "commute_donggu4_1", kind = TransportKind.BUS, displayName = "동구4-1번 버스",
                origin = "안심역 1번 출구", destination = "한국교육학술정보원",
                routeNumber = "동구4-1", stops = listOf("안심역(1번출구)", "한국교육학술정보원"),
                routePoints = listOf(
                    GeoPoint(35.8724067, 128.7335650), GeoPoint(35.8768724, 128.7354843)
                )
            )
        )
    )

    val returning = JourneyDefinition(
        id = JourneyId.RETURN,
        subwayManualBoarding = true,
        legs = listOf(
            TransportLeg(
                id = "return_donggu4", kind = TransportKind.BUS, displayName = "동구4번 버스",
                origin = "대구경북지방병무청", destination = "송정삼거리3", routeNumber = "동구4",
                stops = listOf("대구경북지방병무청", "송정2교", "송정삼거리3"),
                routePoints = listOf(
                    GeoPoint(35.8770672, 128.7351894), GeoPoint(35.8736933, 128.7336550),
                    GeoPoint(35.8716084, 128.7324801)
                )
            ),
            TransportLeg(
                id = "return_subway", kind = TransportKind.SUBWAY, displayName = "지하철",
                origin = "안심역", destination = "동대구역", stops = subwayReturn
            ),
            TransportLeg(
                id = "return_ktx", kind = TransportKind.KTX, displayName = "KTX",
                origin = "동대구역", destination = "대전역", stops = ktxStopsCommute.reversed(),
                routePoints = ktxCommutePoints.reversed()
            ),
            TransportLeg(
                id = "return_514", kind = TransportKind.BUS, displayName = "514번 버스",
                origin = "대전역/역전시장", destination = "국화아파트", routeNumber = "514",
                stops = listOf(
                    "대전역/역전시장", "목척교", "중앙로역9번출구", "중앙로역8번출구",
                    "대전여상", "선화동천주교회", "중앙중고등학교", "중촌초등학교",
                    "남선공원네거리", "남선공원종합체육관", "문정초등학교", "국화아파트"
                ),
                routePoints = listOf(
                    GeoPoint(36.3295844, 127.4336672), GeoPoint(36.3303311, 127.4299860),
                    GeoPoint(36.3290522, 127.4266000), GeoPoint(36.3297910, 127.4246340),
                    GeoPoint(36.3323209, 127.4211807), GeoPoint(36.3356810, 127.4173546),
                    GeoPoint(36.3391610, 127.4130492), GeoPoint(36.3410765, 127.4100979),
                    GeoPoint(36.3457884, 127.4019066), GeoPoint(36.3488613, 127.3983603),
                    GeoPoint(36.3504314, 127.3953442), GeoPoint(36.3541180, 127.3953796)
                )
            )
        )
    )

    val hospitalOutbound = JourneyDefinition(
        id = JourneyId.HOSPITAL_OUTBOUND,
        legs = listOf(
            TransportLeg(
                id = "hospital_donggu4", kind = TransportKind.BUS, displayName = "동구4번 버스",
                origin = "대구경북지방병무청", destination = "동호육교1", routeNumber = "동구4",
                routeId = "4050004000", boardingStopId = "7011051600",
                stops = listOf(
                    "대구경북지방병무청", "송정2교", "송정삼거리3", "녹원맨션",
                    "신서롯데캐슬레전드서편건너", "동호육교1"
                ),
                routePoints = listOf(
                    GeoPoint(35.8770672, 128.7351894), GeoPoint(35.8736933, 128.7336550),
                    GeoPoint(35.8716084, 128.7324801), GeoPoint(35.8718151, 128.7295728),
                    GeoPoint(35.8702733, 128.7237200), GeoPoint(35.8666643, 128.7243557)
                )
            )
        )
    )

    val hospitalReturn = JourneyDefinition(
        id = JourneyId.HOSPITAL_RETURN,
        legs = listOf(
            TransportLeg(
                id = "hospital_donggu4_1", kind = TransportKind.BUS,
                displayName = "동구4-1번 버스", origin = "동호육교2",
                destination = "한국교육학술정보원앞", routeNumber = "동구4-1",
                routeId = "4050004100", boardingStopId = "7011032600",
                stops = listOf(
                    "동호육교2", "신서롯데캐슬레전드서편앞", "신서신일해피트리아파트",
                    "송정삼거리4", "안심역(1번출구)", "한국교육학술정보원앞"
                ),
                routePoints = listOf(
                    GeoPoint(35.8668332, 128.7245359), GeoPoint(35.8705650, 128.7239783),
                    GeoPoint(35.8717032, 128.7286834), GeoPoint(35.8716283, 128.7318933),
                    GeoPoint(35.8724067, 128.7335650), GeoPoint(35.8768724, 128.7354843)
                )
            )
        )
    )

    val all = listOf(commute, returning, hospitalOutbound, hospitalReturn)
    private val byId = all.associateBy { it.id }

    fun journey(id: JourneyId): JourneyDefinition = requireNotNull(byId[id])
    /** Fixed, verified transfer position. Null means that direction has no displayed cue. */
    fun subwayQuickTransferPosition(journeyId: JourneyId): String? = when (journeyId) {
        JourneyId.COMMUTE -> "3-4"
        JourneyId.RETURN -> "6-4"
        else -> null
    }
    /** Outdoor points where a recovery request should resume the subway plan. */
    fun subwayBoardingPoint(journeyId: JourneyId): GeoPoint? = when (journeyId) {
        JourneyId.COMMUTE -> ktxCommutePoints.last()
        JourneyId.RETURN -> GeoPoint(35.8724067, 128.7335650) // 안심역 1번 출구
        else -> null
    }
    fun initialManualPlanLeg(journey: JourneyDefinition): TransportLeg = journey.legs.first()
    fun allStops(journeyId: JourneyId, legIndex: Int): List<String> =
        journey(journeyId).legs[legIndex].stops
}
