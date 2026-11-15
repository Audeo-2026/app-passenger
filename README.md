# Audeo 탑승자 앱 (Android · iOS)

현대자동차그룹 차량용 앱 개발 공모전 출품작 **Audeo**의 탑승자 앱입니다. 운전자 차(Pleos Connect)와 통화하면 AI 비서 오디가 대화를 듣고 픽업 약속(만날 시각·장소)을 정리해요. 탑승자 화면은 그 결과를 보여 주고, 차가 가까워지면 차가 있는 방향을 알려 줍니다.

Kotlin Multiplatform + Compose Multiplatform으로 Android와 iOS를 한 코드로 만듭니다. 차량 앱 레포(`Audeo-2026/app`)의 탑승자 테스트 페이지(`tools/web_passenger/index.html`)를 앱으로 옮긴 것이에요.

> **한 줄 소개:** 어디오(Audeo) 탑승자 앱 — 운전자 차와 통화하면 AI가 만날 약속을 정리하고, 차가 가까워지면 차 있는 방향을 알려 주는 동승자용 휴대폰 앱

## 제출 정보

| 항목 | 내용 |
| --- | --- |
| 앱 이름 | 어디오(Audeo) 탑승자 앱 · 패키지 `com.audeo.phone` |
| APK | `apk/Audeo_AudeoPassenger_release.apk` (release 빌드, 서버 주소 내장) |
| 함께 보는 레포 | 차량 앱 [`Audeo-2026/app`](https://github.com/Audeo-2026/app) (Pleos Connect, 주 제출물) · 서버 [`Audeo-2026/audeo-server`](https://github.com/Audeo-2026/audeo-server) |
| 서버 | `https://audeo.mosslab.dev/api/v1/` (심사 기간 동안 켜 둠) |
| 테스트 환경 | Android 14(API 34) 휴대폰 에뮬레이터 또는 실기기, 마이크·위치 권한 허용 |
| 에뮬레이터 | 이 앱은 휴대폰용이라 Pleos Connect 에뮬레이터가 아니라 일반 Android 휴대폰 에뮬레이터(예: Pixel 7, 1080×2400, API 34)에서 실행해요. 차량 쪽은 차량 앱 README의 Pleos Connect 에뮬레이터 v2.1.0을 써요 |

### APK로 바로 실행

```bash
adb install -r apk/Audeo_AudeoPassenger_release.apk
```

1. 앱을 켜고 마이크·위치 권한을 허용해요.
2. 등록 화면에 이름과 전화번호를 넣고, 초대 코드 칸에는 **아무 글자**나 넣어요(심사 기간에는 서버가 초대 코드를 확인하지 않아요).
3. 차량 앱(Pleos Connect 에뮬레이터)의 연락처에 이 사람이 나타나고, 서로 전화를 걸 수 있어요.

차량 앱만으로도 시연할 수 있어요. 서버의 시연 탑승자 **지호 (시연)**이 탑승자 역할을 대신해요. 이 앱은 실제 사람이 탑승자로 참여할 때 써요.

## 화면

탑승자 웹과 같이 네 가지만 보여 줍니다. 늦는다는 말 같은 건 따로 입력하는 화면 없이 **운전자에게 전화**해서 말해요(오디가 통화를 듣고 계획 변경을 물어요).

| 카드 | 내용 |
| --- | --- |
| 통화 | 전화를 기다리는 중 → 전화가 왔어요(**동의하고 받기** / 그냥 받기 / 거절) → 통화 중(마이크·스피커·끊기). 픽업이 있으면 **○○에게 전화** |
| 다이얼로그 | 서버가 보낸 제안. 결정자가 탑승자(`approver=passenger`)일 때만 고르는 버튼이 있고, 운전자가 정하는 제안은 내용만 보여 줘요 |
| 지도 | 만날 곳·차·내 위치를 한 화면에 맞춰 보여 줘요. 타고 오는 열차가 확인되면 열차 번호도 보여 줘요 |
| 나침반 | 차가 300m 안으로 오면 지도 대신 떠요(iPhone '나의 찾기'처럼). 화살표가 차 쪽을 가리키고, 차가 앞쪽에 오면 카드가 파래져요. GPS 오차 안쪽이면 '바로 근처' |

## 동작

- **서버**: Audeo 서버 v1(`/api/v1`)에 초대 코드로 `phone` 기기를 등록하고, 받은 기기 토큰으로 부릅니다. 실시간 이벤트(통화 상태, 제안, 픽업, 조율, 오디의 말, 통화 신호)는 WebSocket(`/ws`)으로 받아요. 앱을 켤 때는 지난 이벤트를 건너뛰어요(끝난 전화가 다시 울리지 않게). WebSocket이 두 번 넘게 끊기면 그동안 `GET /events` long-poll로 이벤트를 받아요(통화 신호는 WebSocket으로만 와요).
- **통화**: 상대가 Audeo와 같이 듣자고 건 전화만 AI 분석 동의를 묻고, AI 없이 건 전화는 일반 통화로 받아요. 운전자에게 걸 때는 지금 픽업 id(`journey_id`)를 같이 보내고, 거는 중에 **AI 없이 걸기**(`PUT /calls/{id}/consent`)로 바꿀 수 있어요. 앱이 다시 켜지면 이 기기의 통화를 `GET /calls/{id}`로 확인해서 울리는 중이면 이어 보여 주고, 이미 연결됐던 통화는 음성을 다시 이을 수 없어 끝내요.
- **통화 음성**: WebRTC(음성만)로 차와 휴대폰이 직접 연결되고, 서버는 신호(offer/answer/ice)만 전달해요. 건 쪽이 offer를 보냅니다. AI 분석에 동의하지 않아도 일반 통화는 그대로 돼요.
- **데이터 채널**: 같은 연결에 `audeo` 채널을 열어 2초마다 내 정밀 위치(`{"t":"loc"}`)를 차로 보내고, 차가 보낸 차 위치(`loc`)와 알림(`note`)을 받아요. 서버를 거치지 않습니다.
- **다이얼로그**: 새 제안 이벤트 말고도, 픽업의 `pending_proposals`에 아직 못 본 제안이 있으면 `GET /journeys/{id}/proposals`로 불러와요(앱을 늦게 켜도 떠 있는 제안이 보여요). 고를 때는 제안의 `base_version`을 같이 보내고, 그사이 계획이 바뀌어 409가 오면 새 제안을 다시 불러와요.
- **위치 공유**: 서버가 픽업에 위치 공유를 켜면(`location_sharing`) 차 위치를 `GET /journeys/{id}/car`로 5초마다 받고, 내 위치를 `POST /journeys/{id}/status`로 15초마다 보내요(서버가 조율에 탑승자 줄을 채워요). 픽업이 끝나면 서버가 409로 닫아요.
- **내 위치·방향**: Android는 LocationManager(GPS·네트워크)와 회전 벡터 센서(편각으로 진북 보정), iOS는 CLLocationManager(위치·방향).
- **지도**: 무료 래스터 타일(CARTO `light_all`/`dark_all`, OpenStreetMap 데이터)을 받아 Compose Canvas에 그립니다. 보이는 점이 바뀔 때만 화면을 다시 맞춰요.

## 개발 환경

- JDK 17 이상, Android Studio(또는 Android SDK), Xcode 16 이상, CocoaPods
- Kotlin 2.3.20 · Compose Multiplatform 1.10.3 · Ktor 3.5.2 · [webrtc-kmp](https://github.com/shepeliev/webrtc-kmp) 0.125.11 (iOS는 CocoaPods `WebRTC-SDK`)

`local.properties`(Git에 올리지 않아요)에 서버 설정을 넣습니다. 빌드할 때 `AudeoConfig`로 들어가요.

```properties
sdk.dir=/Users/<나>/Library/Android/sdk

audeo.api.baseUrl=https://audeo.mosslab.dev/api/v1/
audeo.inviteCode=<초대 코드>

# 처음 실행할 때 등록 화면에 미리 넣어 둘 값(개발용, 비워도 돼요)
audeo.passenger.name=김민지
audeo.passenger.shortName=민지
audeo.passenger.phone=010-0000-0002
```

같은 전화번호로 등록하면 서버가 같은 사람으로 묶어요. 운전자 차량 앱과 같은 초대 코드를 쓰세요.

## 실행

### Android

```bash
./gradlew :composeApp:installDebug
```

처음 켜면 마이크와 위치 권한을 물어요.

### iOS

```bash
./gradlew :composeApp:podInstall
open iosApp/iosApp.xcworkspace
```

Xcode에서 `iosApp` 스킴을 고르고 실행합니다. 실제 iPhone에 설치하려면 `iosApp/Configuration/Config.xcconfig`의 `TEAM_ID`를 채우세요. 나침반은 실제 기기에서만 동작해요.

## 코드 위치

- `composeApp/src/commonMain/kotlin/com/audeo/phone/AppController.kt`: 화면 흐름. 서버 이벤트로 통화·픽업·제안을 정하고, 지도/나침반 전환을 계산해요.
- `.../net/`: 서버 클라이언트(`AudeoApi`), 응답 해석(`Protocol`, 차량 앱과 같은 규칙), 모델
- `.../call/CallSession.kt`: WebRTC 통화 음성과 데이터 채널
- `.../ui/`: 디자인 시스템 컴포넌트(차량 앱과 같은 색·글자), 화면(`Screens.kt`), 지도(`TileMap.kt`), 나침반(`Finder.kt`)
- `.../Platform.kt` + `androidMain`/`iosMain`: 저장소, 위치·방향, 통화 음성 경로(스피커)

## 알아 둘 점

- 지금 서버(`GET /rtc/config`)는 STUN만 주고 TURN이 없어요(서버의 `AUDEO_TURN_URLS`가 있어야 TURN을 줘요). 같은 와이파이나 일반 공유기에서는 통화가 잘 되지만, 휴대폰 데이터망끼리처럼 막힌 망에서는 음성이 연결되지 않을 수 있어요.
- 푸시 알림은 아직 없어요. 서버는 `PATCH /devices/me`의 `push_token`을 받지만, 앱에 Firebase(Android)·APNs(iOS) 설정이 필요해요. 그 전에는 앱이 꺼져 있거나 iOS에서 백그라운드면 전화가 와도 알 수 없어요.
- 탑승자 화면을 최소로 두려고 '차 탔어요'(완료), 취소, 음성 명령, 값 수정 API는 일부러 쓰지 않아요. 늦어지는 일은 운전자에게 전화해서 말해요.
- 지도 타일은 CARTO 무료 베이스맵이라 지도 아래에 출처(© OpenStreetMap © CARTO)를 표시합니다.
