#!/bin/sh
# Create synthetic files only; print an operator command without launching the UI.
set -eu
umask 077
fixture_dir=$(mktemp -d /tmp/focusdeck-review-ui.XXXXXXXX)
mkdir "$fixture_dir/reports"
python3 - "$fixture_dir" <<'PY'
import datetime, json, pathlib, sys, uuid
root=pathlib.Path(sys.argv[1]); reports=root/'reports'; revision=str(uuid.uuid4())
def write(path, value): path.write_text(json.dumps(value, ensure_ascii=False)+'\n')
write(root/'session.json', {'taskTitle':'Пример учебной задачи','taskRef':'fixture_task','phase':'ready','note':''})
write(reports/'recovery.json', {'schemaVersion':1,'enabled':True,'state':'source_unavailable', 'evaluatedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(), 'context':{'taskRef':'fixture_task','sessionState':'paused'}})
write(reports/'weekly-latest.json', {'schemaVersion':1,'nextReviewDate':'2026-11-16','protectionReview':{'state':'observed_incomplete','counts':{'relief':1,'return':2,'explicitExit':0},'sourceStates':['observed','unknown'],'committedPolicyRevisionIds':[revision]}})
write(reports/'current-day.json', {'schema':1,'day':'2026-11-09','categories':{'work':{'focusHoursByEvidence':{'timer':None}},'study':{'focusHoursByEvidence':{'timer':None}}},'dailyCheck':{'state':'no_data'}})
PY
printf 'Synthetic data: %s\n' "$fixture_dir"
printf 'Launch the freshly built app binary explicitly:\nFOCUSDECK_DATA_DIR="%s" FOCUSDECK_REPORT_FILE="%s/reports/current-day.json" FOCUSDECK_REVIEW_FIXTURE_DIR="%s/reports" /absolute/path/to/FocusDeck\n' "$fixture_dir" "$fixture_dir" "$fixture_dir"
printf '%s\n' 'In the UI: open “Недельный обзор и источники”; check the test label, 1/2/0 counts, 1 of 2 sources with gaps, and unknown completeness. Check the synthetic next-step prompt and source-unavailable message. No network or installed service is needed.'
