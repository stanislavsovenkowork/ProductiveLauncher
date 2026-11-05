#!/usr/bin/env python3
"""Package only reviewed .app/APK and their explicit acceptance status, no runtime data."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from zoneinfo import ZoneInfo

ROOT=Path(__file__).resolve().parent.parent


def digest(path):
    h=hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b""):h.update(chunk)
    return h.hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--evidence",required=True,type=Path,help="Reviewed validation report, not fabricated readiness")
    args=p.parse_args()
    if not args.output.is_absolute() or not args.evidence.is_file():p.error("absolute output and existing evidence report required")
    app=ROOT/"macos-study/build/FocusDeck.app"
    apk=ROOT/"app/build/outputs/apk/debug/app-debug.apk"
    if not (app/"Contents/MacOS/FocusDeck").is_file() or not apk.is_file():p.error("both built applications required")
    subprocess.run(["/usr/bin/codesign","--verify","--deep","--strict",str(app)],check=True,capture_output=True)
    app_fingerprint={str(p.relative_to(app)):digest(p) for p in app.rglob("*") if p.is_file()}
    apk_fingerprint=digest(apk)
    stamp=datetime.now(ZoneInfo("Europe/Minsk")).strftime("%Y%m%d-%H%M%S")
    target=args.output/("FocusDeck-beta-"+stamp)
    target.mkdir(parents=True,exist_ok=False)
    try:
        archive=target/"FocusDeck-macOS.zip"
        subprocess.run(["/usr/bin/ditto","-c","-k","--sequesterRsrc","--keepParent",str(app),str(archive)],check=True,capture_output=True)
        android=target/"FocusDeck-Android-QA.apk";shutil.copyfile(apk,android)
        if digest(android)!=apk_fingerprint or digest(apk)!=apk_fingerprint or app_fingerprint!={str(p.relative_to(app)):digest(p) for p in app.rglob("*") if p.is_file()}:
            raise RuntimeError("Build changed during snapshot; no package published")
        evidence=target/"VALIDATION.md";shutil.copyfile(args.evidence,evidence)
        shutil.copyfile(ROOT/"DEVELOPMENT-STATUS.md",target/"DEVELOPMENT-STATUS.md")
        shutil.copyfile(ROOT/"docs/ACCEPTANCE.md",target/"ACCEPTANCE.md")
        shutil.copyfile(ROOT/"docs/INSTALL-AND-RECOVERY.md",target/"INSTALL-AND-RECOVERY.md")
        note="""# FocusDeck — beta

Это два запускаемых приложения, а не заявление о готовности всей системы. Точный проверенный охват и открытые критерии — VALIDATION.md и ACCEPTANCE.md.

Mac: распакуйте FocusDeck-macOS.zip и откройте FocusDeck.app. Это пользовательский интерфейс; лабораторный FocusDeckStudy.app сюда не включён. Данные не лежат внутри .app. Подпись ad-hoc для этой локальной разработки; не отключайте защиту macOS ради запуска. Сайты и браузеры блокирует выбранное отдельно внешнее ПО. Оно пока не подключено. Наши лабораторные сетевые/root-компоненты не требуются и не входят в основной путь запуска.

Android: APK отдельного пакета FocusDeck QA. Не удаляйте основное приложение FocusDeck и не устанавливайте этот APK поверх него. Разрешения Android включаются отдельно штатным интерфейсом; проверенный охват и обходы указаны в отчёте. Личный vault и база в поставку не входят.

Независимый локальный сбор на этом Mac управляется в интерфейсе или явными all-stop.command/start-local.command в Library/Application Support/FocusDeckHarness/control-runtime. Остановка сохраняет историю и не переустанавливает сбор автоматически. Закрытие .app само по себе не останавливает ранее установленный независимый сбор.

Это промежуточная поставка; разработка по DoD продолжается. Прирост сосредоточенных часов пока не доказан.
"""
        (target/"README.md").write_text(note)
        files={path.name:{"sha256":digest(path),"bytes":path.stat().st_size} for path in target.iterdir() if path.is_file()}
        (target/"manifest.json").write_text(json.dumps({"createdAt":datetime.now(ZoneInfo("Europe/Minsk")).isoformat(),"status":"beta-not-complete-system","macExecutableSHA256":app_fingerprint["Contents/MacOS/FocusDeck"],"androidPackage":"dev.stanislav.focusdeck.qa","files":files},indent=2)+"\n")
        print(str(target))
    except BaseException:
        # Remove only the new package directory created by this invocation.
        shutil.rmtree(target)
        raise


if __name__=="__main__":main()
