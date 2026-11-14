# Alma(알마) 설치 및 실행

팀 With(위드)

알마는 뒷좌석 아이가 잠드는 듯할 때 휴대폰에서 재생 중인 영상의 소리를 낮추고 앱 화면을 어둡게 합니다. 운전자는 주차한 뒤 아이의 모습이 담긴 최근 카메라 화면을 확인할 수 있습니다.

| 설치 위치 | 서명 APK | 시험 환경 |
| --- | --- | --- |
| 차량 | [With_Alma_release.apk](apk/With_Alma_release.apk) | Pleos Connect Emulator 2.1.0, Android API 34, 2560 × 1440 가로 화면 |
| 뒷좌석 휴대폰 | [With_AlmaCompanion_release.apk](apk/With_AlmaCompanion_release.apk) | Android API 34, 1080 × 2400 세로 화면, 전면 카메라 |

## 두 앱 연결

두 에뮬레이터를 실행한 뒤 압축을 푼 폴더에서 아래 명령을 실행합니다. `CAR_SERIAL`과 `PHONE_SERIAL`은 `adb devices`에 나온 각 기기의 ID로 바꿉니다. 차량 앱 설치 명령의 `CAR_USER_ID`에는 바로 앞 명령으로 확인한 숫자를 넣습니다.

```text
adb devices
adb -s CAR_SERIAL shell am get-current-user
adb -s CAR_SERIAL install -r --user CAR_USER_ID apk/With_Alma_release.apk
adb -s PHONE_SERIAL install -r apk/With_AlmaCompanion_release.apk
adb -s PHONE_SERIAL forward --no-rebind tcp:18787 tcp:8787
adb -s CAR_SERIAL reverse --no-rebind tcp:8787 tcp:18787
adb -s CAR_SERIAL forward --no-rebind tcp:18088 tcp:8088
```

1. 휴대폰에서 Alma 연결을 열고 카메라 사용을 허용합니다. 재생 제어를 시험할 때는 기기에 넣어 둔 MP4를 앱에서 골라 재생합니다.
2. Pleos 기어를 P에 두고 차량 앱에서 연결 설정을 엽니다. 휴대폰 주소 칸에 `127.0.0.1`을 입력하고 Alma 연결 화면에 표시된 연결 키를 넣은 뒤 휴대폰 연결을 누릅니다.

## 주차 미리보기 확인

1. 휴대폰에서 차량에 카메라 미리보기 공유를 켭니다. 차량 앱에서 아이 화면 확인을 누르면 최근 사진 한 장이 5초간 표시됩니다. 다시 누르면 새 사진을 가져옵니다.
2. 브라우저에서 `http://127.0.0.1:18088`을 엽니다. 사진이 보일 때 Virtual Console에서 기어를 D로 바꾸면 사진과 미리보기 버튼이 함께 사라집니다.
3. P로 돌아와도 사진은 닫혀 있습니다. 아이 화면 확인을 다시 눌러 엽니다.

## 재생 제어와 상태 알림

휴대폰에서 MP4를 재생하고 차량 화면에서 음성으로 요청을 누릅니다. 멈춰줘, 영상 틀어줘를 차례로 말하고 휴대폰에서 영상이 멈췄다가 이어지는지 확인합니다.

휴대폰 전면 카메라에 얼굴을 비추면 앱이 기준 자세를 잡습니다. 자세를 바꾸고 싶을 때는 카메라 기준 자세 다시 맞추기를 누릅니다. 기준보다 고개를 크게 숙인 상태가 이어지면 자세 확인 알림이 뜹니다.

카메라 없이 수면 전환을 재현하려면 Alma 연결에서 시연 입력 사용을 켠 뒤 눈 감김 또는 눈 뜸을 선택합니다. 구토 의심 안내는 같은 메뉴에서 구토 의심 사건 넣기를 눌러 수동으로 재현합니다.

## 소스 빌드

Android Studio에서 압축을 푼 폴더를 열어 Gradle 동기화를 실행합니다. Windows 명령줄에서는 `gradlew.bat :vehicle:assembleDebug :companion:assembleDebug`로 두 앱을 빌드할 수 있습니다. Android SDK 36, Java 17, Gradle Wrapper 8.14.3을 사용합니다. Gradle은 Pleos 음성 SDK를 공식 저장소에서 받습니다.

[공식 Pleos 에뮬레이터 설치 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
