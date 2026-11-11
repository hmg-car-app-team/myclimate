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
| 날씨·대기질 | **Open-Meteo**(키 없음, HTTPS 전용): 날씨는 forecast `current`(날씨코드·강수·눈), 대기질은 air-quality `current`. **선택 지역의 대표 좌표 기준**이며 기기 위치가 아니다. 10분 주기, 백그라운드 호출, 연결 5초·읽기 15초, 재시도 없음. 우선순위: 시연 선택 > 실시간 > 마지막 성공값(메모리) > 기본 샘플. 화면 배지: ‘실시간 · Open-Meteo · 선택 지역’ / ‘마지막 값 · HH:mm’ / ‘기본 샘플’ / ‘시연 값’ |
| 시연 주입(debuggable 전용) | `adb shell am broadcast -a com.hmgcarapp.myclimate.SIM --ef outside -3 --ei hour 8 --es weather RAIN --es air BAD --es observe seat_heat:2 --ez tick true --ez smart true --ei recircAge 11 --es drive DRIVING` (DUMP 권한, release 미등록, 화면에 ‘시연 값 사용 중’) |

## 개인정보 처리

- 프로필·규칙·습관 통계(상황별로 ‘켠’ 관측 날짜 번호, 최대 14일)만 앱 샌드박스(`files/hmg-store/myclimate_state.json`)에 **평문**으로 저장. 거절한 제안에는 ‘다시 제안하지 않을 시각’(에포크 ms)이 함께 저장됩니다. 위치·음성은 저장하지 않으며 로그에도 남기지 않음. 설정 > ‘학습 기록 지우기’/‘모든 데이터 삭제’. ‘모든 데이터 삭제’는 인터넷 사용 동의·선택 지역(`myclimate_net.json`)도 처음 상태로 되돌린다: 메모리의 동의·지역·실시간 값을 즉시 비우고(진행 중 호출 결과는 세대 번호로 버림, 이후 호출 없음), 파일은 저장 직렬화 큐로 삭제하며 실패하면 빈 상태 기록으로 대체하고 그것도 실패하면 ‘다시 켜질 수 있어요’ 토스트를 띄운다. 삭제 뒤 정차 중 최초 고지 팝업이 다시 뜬다.
- **외부 전송(날씨·대기질)**: 사용자가 최초 고지에서 동의한 뒤에만 호출한다(동의 전 호출 없음, 설정에서 언제든 철회 가능, 철회하면 받은 값을 버리고 더는 호출하지 않음). 외부(Open-Meteo 서버)로 나가는 것은 **선택한 지역(서울·부산 등 10곳)의 대표 좌표와 요청 변수명뿐**이며 기기 위치·계정·기기/차량 식별자·공조 사용 기록은 보내지 않는다(서버는 접속 IP 를 볼 수 있다). 위치 권한은 쓰지 않는다. 동의·선택 지역은 프로필 저장소와 분리된 별도 파일(`myclimate_net.json`)에 저장한다.
- 매니페스트에 `INTERNET` 권한을 이 앱에만 추가했다. 통신은 HTTPS 만(cleartext 금지), API 키 없음.

## 알려진 한계

- **실데이터 연동 한계**: Open-Meteo 무료 API 는 비상업 이용 범위다(상용화 시 유료 플랜 또는 공공데이터로 교체 필요). 대기질은 약 45km 격자 모델 추정값이라 실제 측정소 값과 다를 수 있어 ‘참고용 추정’으로만 표기한다. 값은 선택 지역 기준이며 현재 위치 기준이 아니다. 날씨 응답을 해석할 수 없는 경우(알 수 없는 코드)는 실패로 보고 마지막 성공값을 유지한다. 한쪽만 성공하면 배지를 ‘날씨 실시간 · 대기질 샘플’처럼 항목별로 표기한다. 기본 샘플(실측·시연 아님)은 내기순환 자동 전환의 근거로 쓰지 않고, 시연 값만 전환을 시연한다. 두 호출 사이에 철회·지역 변경을 다시 확인해 두 번째 호출을 막는다(이미 나간 첫 호출은 취소할 수 없다). 요청은 고정 User-Agent(MyClimate)와 Accept 만 보낸다. 동의 저장은 한 번에 하나씩 마지막 요청만 쓰고, 철회 저장 실패는 파일 삭제로 폴백하며 둘 다 실패하면 토스트로 알린다. 저장된 설정을 읽기 전에는 동의·지역 변경을 무시한다.
- 알려진 한계(미조치): 동의 철회 직후 CLOSE_WAIT 소켓이 2개 남을 수 있다. 체감 유지와 시연 값을 동시에 적용하면 logcat 문구에 시연 표기가 빠질 수 있다. 벽시계가 거꾸로 가면 값 신선도 판단이 어긋날 수 있다. 날씨+대기질 전체 마감 시간이 없다(호출별 5초·15초만). 지역을 연타하면 호출이 중복될 수 있다. 정차 판단(parkedOnly) 기준이 화면 표시와 일치하지 않을 수 있다. PM10·PM2.5 중 한쪽이 없으면 있는 값만으로 등급을 낸다.
- **실호출 미검증**: 파싱·등급 매핑·폴백·동의 게이트는 가짜 HTTP 클라이언트로 JVM 단위 테스트에서 확인했다. 에뮬레이터에서의 실제 네트워크 호출과 동의 팝업 화면은 이 작업에서 실행해 확인하지 않았다.
- 체감 온도 유지는 ‘외기온 기반 보정’이다(설정에서 켬, 기본 꺼짐). 외기온 30℃ 이상이면 목표 온도 -1℃(35℃ 이상 -2℃), 0℃ 이하면 +1℃(-10℃ 이하 +2℃)로 보정하고 차량 허용 범위로 맞춘다. 일사량·주행시간·실제 체감 센서는 쓰지 않으며 개인별 학습 보정값도 없다. 외기온을 모르거나 범위 밖이면 보정하지 않는다.
- 체감 유지가 켜진 채 ‘현재 값으로 프로필 추가’를 하면, 마지막 적용에서 들어간 보정량만큼 되돌린 온도를 기준으로 저장한다(적용 뒤 사용자가 온도를 바꿨다면 그 값 그대로). 시연 날씨·대기질로 적용된 ‘최근’ 기록에는 ‘(시연 값)’이 붙는다.
- 시연 날씨·대기질 선택은 주행으로 바뀐 뒤에도 유지되며, 외기온은 값의 신선도(오래됨) 검사 없이 마지막 값을 쓴다.
- 차량 공조 서비스가 초기화되기 전(부팅 직후·앱 첫 실행 직후 최대 수 분)에는 SDK 가 기본값을 돌려줄 수 있어 에어컨·내기 표시가 실제와 다를 수 있다(온도·풍량은 범위 밖이면 표시하지 않음).
- 차량 서비스가 비정상 종료되면 앱도 종료될 수 있다(Car 라이프사이클 리스너 미등록, 재실행 필요).
- 첫 실행에서 차속 권한 허용 전에는 차속 읽기 경고가 반복되며 재시작 후 해소된다.
- 자동 적용은 앱 실행당·정차 구간당 1회입니다(앱을 닫았다 같은 정차 구간에 다시 열면 한 번 더 적용될 수 있음).
- 값 범위: 저장할 때는 기본 범위(DEFAULT: 온도 16~32℃·풍량 1~8·시트/핸들 0~3)로 정리하고, 적용할 때는 차량 capability 범위로 다시 맞춘다. 차량이 지원하지 않는 항목(예: 송풍 방향)은 적용되지 않고 ‘적용될 설정’ 표시에서도 빠진다. 송풍 방향은 쓰기만 하며 현재 값 읽기·변경 감지는 하지 않는다(리스너 없음).
- 운전자 자동 인식 불가(프로필 ID API 없음) → 정차 중 직접 선택. 시동 감지 없음 → 앱 실행/정차 전환 때 적용.
- 운전석(1열 좌)만 제어. 단계/범위는 capability 로 읽은 값, 못 읽으면 기본 가정(시트·핸들 0~3, 풍량 1~8, 온도 16~32℃).
- 프로필 이름 편집 불가(자동 이름, 키보드 입력 컴포넌트 미사용). 터널 진입 연동 제외(경로 형상 API 없음).
- 에뮬레이터에서 HVAC 리스너가 값을 내는지는 환경 의존.
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
