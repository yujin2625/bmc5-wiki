# BMC5 위키

Better MC [NEOFORGE] BMC5 모드팩용 위키입니다. `docs/` 폴더가 그대로 GitHub Pages로 배포됩니다.

## 구조

```
docs/                  배포되는 사이트 (빌드 결과물, 직접 고치지 말 것)
  index.html           위키 첫 화면 (도감 고르기)
  mobs/index.html      몹 사육 도감
  gear/index.html      장비·인챈트 도감
  assets/fonts/        마인크래프트 글리프로 만든 픽셀 폰트
tools/
  build_site.py        페이지 조립 + 폰트 생성 → docs/
  jdis.py              클래스 파일 디스어셈블러 (모드 코드 확인용, JDK 불필요)
  render/              몹 렌더러: 모델 코드를 실행하는 작은 JVM 해석기(jvm.py) + 소프트웨어 래스터라이저
  home.html            첫 화면 원본
  mobs/                몹 사육 도감 원본과 데이터 추출 스크립트
  gear/                장비·인챈트 도감 원본과 데이터 추출 스크립트
    stats.py           아이템 등록 코드를 실행해서 공격력·공격 속도·방어력·내구도를 읽음 (gearvm.py가 render/jvm.py를 확장)
    build_data.py      태그로 장비 분류, 레시피·전리품 테이블·인챈트 JSON을 읽어 wiki.json 생성
    ko.json            모드에 한국어가 없는 이름·설명·인챈트 설명의 번역 (직접 작성)
.github/workflows/     main에 push하면 Pages로 자동 배포
```

## 빌드

Python 3만 있으면 됩니다(외부 패키지 없음). 마인크래프트 설치 폴더에서 폰트와 아이콘을 읽기 때문에
CurseForge가 설치된 이 PC에서 실행해야 합니다. 경로가 다르면 `MC_INSTALL` 환경 변수로 지정하세요.

```bash
# 모드가 바뀌었을 때만: 모드 jar에서 번식/길들이기 데이터 다시 뽑기
python tools/mobs/extract.py
python tools/mobs/build_data.py
python tools/mobs/info.py      # 체력·공격력(속성 코드), 사는 곳(바이옴·스폰 설정), 드롭(전리품 테이블)
python tools/mobs/build_data.py   # info.json을 반영하려면 한 번 더
python tools/render/render_mobs.py   # 몹 그림: 게임 속 3D 모델+텍스처를 직접 렌더링 (out/contact.png로 한눈에 확인)

# 장비·인챈트 도감 데이터 (모드나 설정 파일이 바뀌었을 때)
python tools/gear/stats.py        # 수치: 바닐라와 각 모드의 아이템 등록 코드 실행 -> stats.json
python tools/gear/build_data.py   # 분류·제작법·전리품·인챈트 -> wiki.json, 번역이 빠진 문구는 untranslated.json에 모임

# 사이트 빌드
python tools/build_site.py
```

## 새 페이지 추가

1. `tools/` 아래에 HTML 조각을 만듭니다. `<html>`, `<head>`, `<body>` 없이 `<title>`과 `<style>`, 본문만 씁니다.
   폰트는 `@font-face{font-family:"MCPixel";src:url(__FONT_URL__) format("truetype")}`로 불러옵니다.
2. `tools/build_site.py`의 `PAGES` 목록에 `(load("파일.html"), "폴더/index.html")`을 추가합니다.
3. `tools/home.html`의 문서 목록에 링크를 추가합니다.
4. `python tools/build_site.py` 실행 후 커밋하고 push하면 배포됩니다.

폰트는 모든 페이지에 쓰인 글자만 담아서 만들기 때문에, 새 글자가 생기면 빌드를 다시 돌려야 합니다.
