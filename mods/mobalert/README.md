# Mob Alert 모드

BMC5 서버용 클라이언트 전용 NeoForge 모드입니다. 지정한 동물이나 몹이 주변에 나타나면 알려 줍니다.
아이템, 블록, 네트워크 채널을 등록하지 않아서 서버에 이 모드가 없어도 접속할 수 있습니다.

- Minecraft 1.21.1, NeoForge 21.1.250 이상, Java 21

## 조작

- `N`: 설정 화면 (모드 목록의 Config 버튼으로도 열림)
- `/mobalert`: 클라이언트 명령어
  - `gui`, `add <엔티티>`, `remove <엔티티>`, `list`, `clear`
  - `radius <칸>`, `cooldown <초>`, `on`, `off`, `toggle ...`

## 동작

- **알림 대상 고르기**: 설정 화면에서 한글, 영어, 초성(ㅍㄷ), ID로 검색합니다.
  - 품종이 있는 생물(예: Dragon Mounts 드래곤)은 품종별로 고를 수 있습니다.
- **알림**: 대상이 반경 안에 새로 들어오면 팝업, 소리, 채팅(나에게만, 좌표 포함)으로 알립니다. 각각 켜고 끌 수 있고, 재알림 간격을 정할 수 있습니다.
- **강조 표시**
  - 발광 외곽선: 벽 너머로도 보입니다. 셰이더를 켜면 안 보입니다.
  - 화면 마커: 대상 머리 위에 `▼ 이름 거리`를 HUD로 그립니다. Iris 셰이더를 켜도 보입니다.
  - HUD 목록: 주변 대상을 방향 화살표와 거리로 보여 줍니다.
- **Xaero's Minimap 연동**: 설치돼 있으면 대상을 미니맵, 월드맵, 월드 안 웨이포인트로 표시합니다. 없거나 버전이 맞지 않으면 조용히 꺼집니다.
- **내 펫 보호**: 조준한 대상이 내 펫이거나 휩쓸기 범위에 내 펫이 있으면 공격 패킷을 보내지 않습니다. 웅크린 채로 때리면 보호하지 않습니다.

## 빌드

Xaero 연동 코드는 컴파일할 때 Xaero jar가 필요합니다. 재배포할 수 없는 파일이라 레포에 넣지 않았습니다.
아래 두 파일을 CurseForge나 Modrinth에서 받아 `libs/`에 넣으세요. 게임 jar에는 포함되지 않습니다.

- `libs/xaerominimap-neoforge-1.21.1-26.4.2.jar`
- `libs/xaerolib-neoforge-1.21.1-1.7.1.jar`

```powershell
.\gradlew.bat build
```

NeoForm 재컴파일 단계는 JRE가 아니라 **JDK 21**이 필요합니다. 마인크래프트에 들어 있는 런타임은 JRE라서 쓸 수 없습니다.
`JAVA_HOME`을 JDK 21로 지정하거나, 사용자 폴더의 `~/.gradle/gradle.properties`에 `org.gradle.java.home`을 넣으세요.

결과물은 `build/libs/mobalert-neoforge-1.21.1-<버전>.jar`이고, 인스턴스의 `mods` 폴더에 넣으면 됩니다.

`build-and-install.bat`을 실행하면 빌드부터 `mods` 폴더 복사까지 한 번에 합니다.
- 기본으로 Gradle이 받아 둔 JDK 21(`%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`)과 CurseForge의 BMC5 인스턴스 경로를 씁니다.
- 경로가 다르면 `JAVA_HOME`, `BMC5_MODS_DIR` 환경 변수로 바꾸세요.
- 게임이 켜져 있으면 jar가 잠겨서 복사가 실패하니, 게임을 끄고 실행하세요.
