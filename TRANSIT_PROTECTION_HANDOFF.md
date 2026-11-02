# 출퇴근 앱 보호 규칙 인계

목적: GPS 경로 좌표처럼 제한된 수정이 상태 전환·알림·화면 표시까지 함께 바꾸는 일을 막는다.

## 고정 범위

- JourneyReducer: 한 여정만 활성 상태를 소유한다. 버스·KTX 자동 탑승, 지하철 수동 탑승과 열차 고정, 다음 구간 전환·완료를 담당한다.
- RouteCatalog·RouteGeometry·TransitGpsRules·TransitTrackingService: 경로 좌표·GPS 정확도·속도·회랑 등 판정만 담당한다. 화면과 알림 문구를 바꾸지 않는다.
- MainActivity·TransitNotificationFactory: 탑승 예정/탑승 중의 화면·알림·Now Bar 의미를 담당한다.
- 지하철 하차 문 방향: 목표역 두 정거장 전부터 표시한다.
  - 출근: 2개/각산역/안심역/왼쪽
  - 퇴근: 2개/동구청역/동대구역/오른쪽

## 검증

- TransitNotificationFactoryTest는 버스·KTX·지하철의 표시 규칙을 검증한다.
- JourneyReducerTest는 탑승·하차·다음 구간 전환과 지하철 수동 탑승을 검증한다.
- RouteGeometryTest는 경로 좌표가 진행 위치로 바뀌는 규칙을 검증한다.
- 빌드 배치는 testDebugUnitTest가 성공한 경우에만 assembleDebug를 실행해야 한다.

## 집컴 적용

이 파일과 동일한 주석·테스트가 Git으로 내려왔는지 먼저 확인한다. 집컴의 로컬 빌드 배치도 반드시 테스트 후 APK 빌드 순서를 사용한다. 배치·SDK·Gradle 캐시·서명키·APK는 Git에 올리지 않는다.
