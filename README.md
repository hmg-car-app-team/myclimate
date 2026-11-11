# 마이클라이밋 MyClimate

운전자마다 다른 공조 습관(온도, 풍량, 내외기, 열선 및 통풍 시트 등)을 프로필로 저장하고 학습해, 탑승하는 순간 나에게 맞는 실내 환경을 자동으로 맞춰주는 개인화 공조 앱입니다.

> 현대자동차그룹 차량용 앱 개발 공모전 출품작. Pleos Connect(Android Automotive) 환경용 Android 앱입니다.

## 핵심 기능

- **프로필**: 최대 8개, 정차 중에 선택·편집. 항목별 ‘적용’ 스위치(꺼짐 = 건드리지 않음). 현재 차량 값으로 새 프로필 생성.
- **상황별 규칙**: 외기온도 구간·시간대(자정 넘김 지원)·날씨 조건 → 설정 패치. 프로필 위에 덮어쓰며 더 구체적인 규칙이 우선. 기본 규칙(폭염/한파/비·눈/심야) 포함, 정차 중 추가·켜기/끄기·삭제.
- **자동 적용**: 앱 실행·정차(P) 전환 시 정차 구간당 1회, 차량 연결과 capability 확인 뒤. 주행 중에는 절대 적용하지 않음.
- **습관 학습·제안**: 시트 열선/통풍·핸들 열선·에어컨·내기를 ‘직접 켠’ 날짜를 (외기온도 구간 × 시간대) 별로 집계. 14일 안에 서로 다른 3일 이상이면 정차 중 음성+팝업으로 제안, 수락하면 규칙이 됨. 거절하면 30일간 재제안 없음.
- **스마트 내외기**(설정에서 켬, 기본 꺼짐): 대기질 나쁨 → 내기, 내기 10분(대기질 나쁨 20분) 초과 → 외기, 공기 개선 시 자동 내기만 외기로 복귀, 직접 바꾸면 10분 보류. 주행 중에도 동작하며 음성 한 줄로만 안내.
- **주행 중**: 한 줄 안내만(최근 동작·내외기 상태). 모든 조작·편집은 정차 중에만(parkedOnly + DO 선언).

## 실행 방법

필요한 것: JDK 17, Android SDK(compileSdk 35). 디자인시스템·`:core`·SDK 의존성이 이 저장소에 포함되어 있어 `app/` 만으로 단독 빌드됩니다. (Pleos SDK 는 공개 Maven 저장소 `nexus-playground.pleos.ai` 에서 받으므로 빌드 시 인터넷이 필요합니다.)

```bash
export JAVA_HOME=<JDK 17 설치 경로>
export ANDROID_HOME=<Android SDK 경로>      # 또는 app/local.properties 에 sdk.dir=<Android SDK 경로>
cd app
./gradlew :my-climate:assembleDebug              # 디버그 APK: my-climate/build/outputs/apk/debug/
./gradlew :my-climate:assembleRelease            # 릴리스 APK: my-climate/build/outputs/apk/release/
```

`local.properties` 는 개인 환경 파일이라 저장소에 포함하지 않습니다(`.gitignore` 로 제외). 환경변수 `ANDROID_HOME` 을 쓰면 만들 필요가 없습니다.

### 설치·실행

```
cd apps
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ANDROID_HOME=~/Library/Android/sdk \
./gradlew :my-climate:assembleDebug :my-climate:testDebugUnitTest
adb install -r my-climate/build/outputs/apk/debug/my-climate-debug.apk
```

## 테스트 실행

```bash
cd app
./gradlew :core:test :my-climate:testDebugUnitTest
```

JVM 단위 테스트이며 에뮬레이터가 필요 없습니다. 결과 리포트는 `*/build/reports/tests/` 에 생성됩니다.

## 에뮬레이터 설정

Pleos Connect 에뮬레이터(Android Automotive 기반)에서 동작을 확인합니다. 설치 방법과 Virtual Console 사용법은 [에뮬레이터 가이드](docs/guide/03-emulator-setup.md)를 따릅니다. 첫 실행 시 Pleos 차량 정보 권한 대화상자가 뜨면 허용해야 합니다.

## APK

릴리스 APK 는 `apk/` 폴더에 있습니다(`apk/<팀명>_<앱이름>_release.apk`).

## 구조

```
app/
├── my-climate/           # 앱 모듈(:my-climate)
├── core/               # 공통 라이브러리(:core) - Pleos SDK 연결·차량 상태 어댑터
├── designsystem/       # Pleos 디자인시스템 소스 복사본(:designsystem), 라이선스 포함
├── licenses/           # 서드파티 라이선스(Asta Sans OFL 등)
└── gradle/             # 버전 카탈로그, Gradle wrapper
planning/               # 기획서 (접수양식 02장 8개 항목)
apk/                    # 릴리스 APK
docs/, submission/      # 공모전 기반 자료(원본 유지)
```

## 데이터 출처

| 항목 | 출처 |
|---|---|
| 공조·시트·핸들 열선 | Pleos Vehicle SDK 2.3.2 (HVAC/Seat/SteeringWheel), capability 체크 후 쓰기 |
| 외기온도 | core CarPropertyManager(ENV_OUTSIDE_TEMPERATURE) 15초 폴링 |
| 날씨·대기질 | **가공 샘플(Fake)** 기본. Remote 는 골격(엔드포인트 미확정, HTTPS 전용) — 화면에 ‘가공 샘플’ 표시 |
| 시연 주입(debuggable 전용) | `adb shell am broadcast -a com.hmgcarapp.myclimate.SIM --ef outside -3 --ei hour 8 --es weather RAIN --es air BAD --es observe seat_heat:2 --ez tick true --ez smart true --ei recircAge 11 --es drive DRIVING` (DUMP 권한, release 미등록, 화면에 ‘시연 값 사용 중’) |

## 개인정보 처리

- 프로필·규칙·습관 통계(상황별로 ‘켠’ 관측 날짜 번호, 최대 14일)만 앱 샌드박스(`files/hmg-store/myclimate_state.json`)에 **평문**으로 저장. 거절한 제안에는 ‘다시 제안하지 않을 시각’(에포크 ms)이 함께 저장됩니다. 위치·음성은 저장하지 않으며 로그에도 남기지 않음. 설정 > ‘학습 기록 지우기’/‘모든 데이터 삭제’.
- API 키는 코드에 없음(`myclimate.apiKey` 로컬 설정, debug 빌드에만 주입; release 는 항상 키 없음 = Fake). 통신은 HTTPS 만.

## 알려진 한계

- 차량 공조 서비스가 초기화되기 전(부팅 직후·앱 첫 실행 직후 최대 수 분)에는 SDK 가 기본값을 돌려줄 수 있어 에어컨·내기 표시가 실제와 다를 수 있다(온도·풍량은 범위 밖이면 표시하지 않음).
- 차량 서비스가 비정상 종료되면 앱도 종료될 수 있다(Car 라이프사이클 리스너 미등록, 재실행 필요).
- 첫 실행에서 차속 권한 허용 전에는 차속 읽기 경고가 반복되며 재시작 후 해소된다.
- 자동 적용은 앱 실행당·정차 구간당 1회입니다(앱을 닫았다 같은 정차 구간에 다시 열면 한 번 더 적용될 수 있음).
- 값 범위: 저장할 때는 기본 범위(DEFAULT: 온도 16~32℃·풍량 1~8·시트/핸들 0~3)로 정리하고, 적용할 때는 차량 capability 범위로 다시 맞춘다. 차량이 지원하지 않는 항목(예: 송풍 방향)은 적용되지 않고 ‘적용될 설정’ 표시에서도 빠진다. 송풍 방향은 쓰기만 하며 현재 값 읽기·변경 감지는 하지 않는다(리스너 없음).
- 운전자 자동 인식 불가(프로필 ID API 없음) → 정차 중 직접 선택. 시동 감지 없음 → 앱 실행/정차 전환 때 적용.
- 운전석(1열 좌)만 제어. 단계/범위는 capability 로 읽은 값, 못 읽으면 기본 가정(시트·핸들 0~3, 풍량 1~8, 온도 16~32℃).
- 프로필 이름 편집 불가(자동 이름, 키보드 입력 컴포넌트 미사용). 터널 진입 연동 제외(경로 형상 API 없음).
- 날씨·대기질 실데이터 미연동(Fake). 에뮬레이터에서 HVAC 리스너가 값을 내는지는 환경 의존.
- 공조 전원이 꺼져 있으면 풍량 등 일부 쓰기가 거부될 수 있음(‘최근 적용’ 줄에 항목별로 표시). 앱은 공조 전원을 임의로 켜지 않는다.
- 자동 복구는 core ManagedConnection 의 연결 교체뿐(앱 자체 재읽기·병합 없음).

## 라이선스·고지

`app/designsystem/` 은 Pleos 디자인시스템(0.2.0)의 소스 복사본이며, 번들 폰트 Asta Sans 는 SIL OFL 1.1 입니다. 전문은 `app/licenses/AstaSans-OFL.txt`, 요약은 `app/NOTICE` 에 있습니다.

## 기반 자료

이 저장소는 공모전 기반 자료 리포(`org-basic`)를 fork 해 만들었습니다. 아래 자료는 그대로 유지합니다.

| 경로 | 내용 |
|---|---|
| [docs/links.md](docs/links.md) | 설명회 영상, Pleos 개발 문서, 에뮬레이터 가이드, 샘플 앱 GitHub 등 링크 모음 |
| [docs/guide/](docs/guide/README.md) | 문서별 개발 가이드: 공모전 규정, 설계 제약, 에뮬레이터, Vehicle SDK 데이터 사전, NaviHelper, 위치·음성 SDK |
| [docs/pleos-sdk-data.md](docs/pleos-sdk-data.md) | Vehicle SDK, NaviHelper, 위치·음성 SDK, Vehicle Data API로 쓸 수 있는 데이터와 미확인 사항 |
| [docs/ideas.md](docs/ideas.md) | 팀 앱 아이디어 백로그 |
| [docs/third-party-ideas.md](docs/third-party-ideas.md) | 서드파티·특허 아이디어 (기반 데이터로 보관만) |
| [submission/](submission/) | 공식 접수양식 docx와 제출 정보·기획서 항목 요약 |

설명회 자막, 공모전 사이트·Pleos 문서 원문, SDK 라이브러리(aar) 같은 원본 자료는 [hmg-car-app-team/hmg-car-app-contest](https://github.com/hmg-car-app-team/hmg-car-app-contest)에 있습니다.
