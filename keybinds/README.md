# BMC5 키바인딩

BMC5 인스턴스의 키바인딩(바닐라와 모든 모드)을 저장해 두고, 다른 컴퓨터에 그대로 적용합니다.
`keybinds.txt`는 `options.txt`의 `key_`로 시작하는 줄만 모은 파일입니다.

## 다른 컴퓨터에 적용

1. 게임을 한 번 실행해서 `options.txt`가 생기게 한 뒤, 게임을 끕니다.
2. `apply-keybinds.bat`을 실행합니다.

- 키바인딩 줄만 바꾸고 화질, 소리 같은 다른 설정은 그대로 둡니다.
- 바꾸기 전에 `options.txt.bak-<날짜>`로 백업합니다.
- 게임이 켜져 있으면 멈춥니다. 켜 둔 채로 바꾸면 게임을 끌 때 `options.txt`를 다시 덮어쓰기 때문입니다.

## 이 컴퓨터의 키바인딩을 저장

게임에서 키를 바꾼 뒤 `export-keybinds.bat`을 실행하고, 바뀐 `keybinds.txt`를 커밋합니다.

## 인스턴스 경로

기본으로 `%USERPROFILE%\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5`를 씁니다.
경로가 다르면 `BMC5_DIR` 환경 변수로 바꾸세요.
