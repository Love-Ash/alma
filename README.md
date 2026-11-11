# 곁

뒷좌석 아이가 잠드는 듯하면 연결한 기기의 영상 소리와 밝기를 낮추고, 확인이 필요한 자세 변화를 앞좌석에 알리는 차량용 앱입니다.

## 설치와 실행

1. `apk/Gyeot_Gyeot_release.apk`는 차량 앱입니다. `apk/Gyeot_Companion_release.apk`는 전면 카메라가 있는 Android 휴대폰에 설치합니다.
2. 같은 신뢰할 수 있는 테스트 네트워크에 두 기기를 연결합니다. 휴대폰에서 곁 연결 앱을 열고 카메라를 허용한 뒤 얼굴이 화면에 들어오도록 기기를 고정합니다.
3. 휴대폰 앱에서 영상 파일을 고릅니다. `demo/sample.mp4`로 재생을 시험할 수 있습니다. 재생, 멈춤, 소리 줄이기는 이 앱에서 고른 영상에 적용됩니다.
4. 차량 앱에 휴대폰 화면의 주소와 연결 키를 입력합니다. 휴대폰 앱을 앞에 띄워 두세요. 화면을 벗어나면 관찰과 연결이 끝나고 다시 열 때 새 키가 발급됩니다.
5. 카메라 없이 시험하려면 시연 입력을 켭니다. 눈 감김, 눈 뜸, 자세 변화, 구토 의심, 얼굴 가림을 선택할 수 있습니다. 수면과 자세는 일정 시간 변화가 이어진 뒤 화면에 반영됩니다.

휴대폰에서 미리보기 공유를 켜면 차량 앱이 최근 카메라 화면을 요청할 수 있습니다. 차량 앱은 Pleos P 기어 신호와 Android Automotive 화면 제한을 함께 확인합니다. 프리뷰는 허용된 주차 상태에서만 표시합니다.

## 빌드와 테스트 환경

| 항목 | 설정 |
|---|---|
| 빌드 | Java 17 소스 호환, Android SDK 36, 최소 API 28, Gradle Wrapper 8.14.3 |
| Pleos Connect SDK | Vehicle 2.3.2, SpeechToText 및 TextToSpeech 3.3.1 |
| Pleos 검증 대상 | Connect Emulator 2.1.0, Android API 34. 전용 이미지에서 구동과 화면 규격 확인 대기 |
| 실행 확인 환경 | 일반 Android API 34 두 대. 차량 앱 1600 × 900 가로 화면, 휴대폰 앱 1080 × 2400 세로 화면 |
| 로컬 연결 | 동일 네트워크, TCP 8787, 화면에 발급되는 연결 키 |

Android Studio에서 저장소를 열고 Gradle 동기화를 실행합니다. 명령줄에서는 `gradlew.bat :vehicle:assembleDebug :companion:assembleDebug`를 사용합니다. SDK는 Gradle이 공식 저장소에서 받습니다. [Pleos 에뮬레이터 설치 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)

## 확인한 범위

- Android API 34 에뮬레이터의 합성 카메라 프레임으로 CameraX와 ML Kit 분석 경로를 확인했습니다. 실제 아동의 수면과 자세 추정 정확도는 측정 전입니다.
- 구토 의심 알림은 수동 시연 입력으로 발생합니다. 수면 시간은 얼굴을 관찰한 구간의 추정치입니다.
- 음성 인식과 안내, P 기어와 주행 제한 전환은 Pleos 전용 환경에서 추가 확인이 필요합니다.
- 미리보기 공유의 기본값은 꺼짐입니다. 휴대폰은 최근 화면을 메모리에 두며 전송에는 로컬 HTTP를 사용합니다.
