# BMC5 위키

Better MC [NEOFORGE] BMC5 모드팩용 위키입니다. `docs/` 폴더가 그대로 GitHub Pages로 배포됩니다.

## 구조

```
docs/                  배포되는 사이트 (빌드 결과물, 직접 고치지 말 것)
  index.html           위키 첫 화면
  mobs/index.html      몹 사육 도감
  assets/fonts/        마인크래프트 글리프로 만든 픽셀 폰트
tools/
  build_site.py        페이지 조립 + 폰트 생성 → docs/
  home.html            첫 화면 원본
  mobs/                몹 사육 도감 원본과 데이터 추출 스크립트
.github/workflows/     main에 push하면 Pages로 자동 배포
```

## 빌드

Python 3만 있으면 됩니다(외부 패키지 없음). 마인크래프트 설치 폴더에서 폰트와 아이콘을 읽기 때문에
CurseForge가 설치된 이 PC에서 실행해야 합니다. 경로가 다르면 `MC_INSTALL` 환경 변수로 지정하세요.

```bash
# 모드가 바뀌었을 때만: 모드 jar에서 번식/길들이기 데이터 다시 뽑기
python tools/mobs/extract.py
python tools/mobs/build_data.py

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
