# 마이클라이밋 MyClimate

탈 때마다 공조와 시트·핸들 열선을 다시 맞춰야 합니다. 이 앱은 저장한 프로필을 정차 중에 한 번에 적용하고, 외기온과 날씨에 따른 규칙도 더합니다. Pleos Connect 앱입니다.

## 기능
- **프로필**: 최대 8개, 정차 중 선택·편집. 항목별 ‘적용’ 스위치로 건드릴 항목만 고릅니다.
- **상황별 규칙**: 외기온도·시간대·날씨에 따라 프로필 위에 덮어씁니다(폭염·한파·비·눈·심야 기본 제공, 비·눈은 날씨 조회에 동의했을 때만 맞습니다).
- **자동 적용**: 앱 실행과 정차(P) 전환 시 정차 구간당 1회. 주행 중에는 적용하지 않습니다.
- **습관 학습 제안**: 자주 켜는 조작이 쌓이면 정차 중 “자동으로 켤까요?”라고 제안합니다.
- **체감 온도 유지·스마트 내외기**(기본 꺼짐): 폭염·한파에 목표 온도를 소폭 보정하고, 대기질에 따라(날씨·대기질 조회에 동의했을 때) 내외기를 전환해 음성 한 줄로 알립니다.
- **주행 중**: 한 줄 안내만 보이고 조작은 정차 후에 합니다.

## 실행 방법
1. Pleos Connect 에뮬레이터(AVD pleos21)를 켭니다.
2. `adb install -r apk/hmg-car-app-team_MyClimate_release.apk`
3. 앱 목록에서 ‘마이클라이밋’을 실행합니다.

## 테스트 환경 / APK 위치
- Pleos Connect v2.1.0 에뮬레이터 (AVD pleos21), 2560x1440 가로
- APK: `apk/hmg-car-app-team_MyClimate_release.apk`

## 개인정보
- 프로필·규칙·습관 통계·선택 도시와 날씨 사용 동의만 기기 안에 저장합니다. 기기 위치(GPS)는 쓰지 않으며 음성은 저장하지 않습니다.
- 날씨·대기질은 옵트인(기본 꺼짐)입니다. 동의하면 선택 지역의 대략적인 좌표만 Open-Meteo로 전송하고, 설정에서 끄면 멈춥니다. 서버 로그는 90일 보관됩니다.

## 출처
- 날씨: Open-Meteo.com (CC BY 4.0, https://open-meteo.com)
- 대기질: Copernicus Atmosphere Monitoring Service(CAMS) 모델 데이터를 Open-Meteo로 받습니다.

## 소스 빌드·테스트
`app/` 폴더만으로 단독 빌드됩니다(JDK 17, Android SDK 필요, Pleos SDK 는 공개 Maven 에서 받으므로 인터넷 필요).

```bash
cd app
./gradlew :my-climate:assembleDebug :my-climate:assembleRelease
./gradlew :core:test :my-climate:testDebugUnitTest
```

서드파티 고지는 `app/NOTICE`, 폰트 라이선스는 `app/licenses/` 에 있습니다.
