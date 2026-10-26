# Navigation maneuver and distance audit - 2026-10-21

## Diagnostics4 follow-up: verify the installed build, not an installation assumption

Full scan: 25,857 app-log lines, 6,390 frame records, 893 unique Maps inputs,
plus capture-info. All recorded frames have valid framing/checksum. The latest
October 21 sessions contain nine explicit heading decisions with the old
`suppress-compass-bearing-NOT-relative-turn` policy. Example: app.log
22604-22608 classifies southeast as 53 but sends 46; frames.log 5526 confirms.
This proves old running logic, not which artifact the rider installed.
The rider reports updating; exact phone/build comparison remains blocked
because only an emulator, not the physical phone, was attached.

Builds now expose a deterministic source-input fingerprint in About, startup,
copied diagnostics and each Maps INPUT. Diagnostic exports additionally include
installed base-APK SHA-256, version/package and install/update times. The source
fingerprint and artifact hash are deliberately separate. They allow the next
capture to identify both running code and the actual installed base artifact.

There are 13 instruction identity TXs and two identity restorations. The rider
reports no visible text. At frames.log 5525 the identity contains Head southeast;
nav refresh follows 2 ms later at 5526. Overwriting is a hypothesis, not a proven
firmware behavior. Clock/temperature fields accepting numeric values do not
establish arbitrary letter support.

Added explicit stationary test controls under Settings -> Bike -> Experimental
cluster instructions: name banner and letters in ETA, next-distance and
total-distance separately. Acknowledgement is required; no active Maps guidance
is allowed at start. Automatic display refresh is held at the writer for no more
than 8 seconds, heartbeat continues, navigation releases the hold immediately,
and expired/superseded test frames are rejected. Identity restoration and
normal-display release run on cancellation as well. The temporary name is
restored to the saved rider identity or current enabled instruction on takeover.
These are diagnostic controls, not a claim of visible text support.

Follow-up validation: **201 tests passed, no failures/errors/skips**, plus debug
APK assembly and whitespace checks. Added source/APK hash tests, bounded probe
window/field-isolation tests, existing diagnostic ZIP regressions and all-eight
heading notification -> legacy wire -> preview checks. These do not validate
the physical display or identify the old phone APK retrospectively.

## Original-APK follow-up: correction to the initial audit

The subsequent investigation of the supplied original XAPK found that the
earlier icon-reference table itself was wrong. Numeric agreement with the OEM
translation did not establish correct text-to-ID semantics. Both packaged
short-instruction dictionaries establish **sharp left/right = IDs 1/4**,
**slight left/right = 2/5**, **keep-at-fork = 19/20**, **sharp fork = 17/18**,
and **legacy directional ramp = 74/75**. This report's conclusions and code
have been corrected accordingly; the original raw log values below are retained.
The previous statement that captured slight/keep wire selection was already
semantically correct is withdrawn. See `DISCOVERIES.md` for the explicit mistake
and correction, and `mappls-id-icons.md` for hashes and REA method evidence.

All 55 packaged step vectors were decoded; all XAPK splits were inventoried for
PDF/manual payloads, with none found. The supplied XAPK SHA-256 is
`3257c15894db8c9ae390564c8e6fd3bf974c769c481e55ecb1a0715dc8a38062`;
retained base APK SHA-256 is
`3cd80aef575e61318a540b255350173429f738490452b3d6a56b1e2ef311bde1`.
Static analysis establishes the SDK dictionary and OEM translation, not physical
Access ROM rendering. No OEM artwork/code was copied into the application.

## Scope and evidence limits

All files under the supplied `C:\Dev\logs` were processed before source changes:
13 extracted text/log files and three ZIPs. All 13 ZIP members were read and
SHA-256 matched to their extracted counterparts. ZIP copies are not independent
ride evidence. No raw route logs or destination names are committed here.

| Directory | app.log lines | app.1.log lines | frames.log lines | frames.1.log lines | capture-info.txt lines |
|---|---:|---:|---:|---:|---:|
| redline-diagnostics | 8,956 | 1 | 1,288 | 15,995 | 20 |
| redline-diagnostics2 | 4,507 | 1 | 6,703 | 15,995 | 20 |
| redline-diagnostics3 | 21,523 | absent | 5,344 | absent | 20 |

Totals: 34,988 app-log lines, 45,325 extracted frame records, 1,315 unique Maps
notification trace IDs. Exact duplicate serialized frame records across exports
reduce to 28,042 records; this is not a count of independent physical packets.
The shared rotated frame file and overlapping active files explain duplicates.
Every extracted frame was decoded and checked for length, markers and the
complemented checksum: no malformed records were found.

Of 14,370 distinct serialized a531 records, 4,352 traced navigation records have
matching retained PARSED records. All compared maneuver, distance, units, ETA,
status and continue fields agree. Another 8,608 traced navigation records have
no retained matching parser record; they cannot establish input-to-output
correctness. The remaining 1,410 a531 records have no Maps trace (idle/welcome
and other contexts). Rotated/truncated app history cannot be reconstructed from
frame bytes alone.

All 1,315 retained classifications were text fallback (1,159) or unclassified
(156); none used a matched bitmap hash. No disagreement or failed local-GATT
write was recorded in these app files. This does not prove cluster rendering
or physical reception: FEFB write-without-response and a local success callback
are not display acknowledgements.

The source references reviewed were `mappls-id-icons.md` (OEM phone resources)
and `cluster-byte-glyphs.md` (unfilled physical-glyph table), plus the existing
OEM translation implementation and tests. No booklet PDF, reference photo, or
original rendered step-icon set was found in the supplied logs/repository.
The Android resource inventory contains XML and fonts, not additional maneuver
image assets; `GixxerIcons` supplied only straight, left, right and U-turn.
The phone-resource descriptions and physical cluster ROM are **not equivalent**.
Existing contradictory roundabout rotation descriptions are explicitly flagged,
not used to invent a new wire mapping.

All historical PARSED entries say model unknown. The third export's
capture-info snapshot says Manual: Access. That snapshot does not establish
which model was selected at each historical notification. The observed
slight/keep/generic-roundabout bytes use shared OEM mappings regardless.

## A. Log analysis

Paths below are relative to `C:\Dev\logs`; line numbers refer to extracted
files, not ZIP archives. Counts cover the complete retained notification set,
not just the examples quoted.

| Issue | Input and interpretation evidence | Wire evidence / conclusion |
|---|---|---|
| 1. U-turn | 66 `Make a U-turn` inputs. `redline-diagnostics2\app.log:2,4,6`, 20:23:08.939, trace `maps-1792507988939-813`: `280 m ... Make a U-turn` -> null Mappls ID -> arrow 46. | `redline-diagnostics2\frames.log:5648`: byte 2 = `2e` (46), next `0280 M`. Text deliberately rejected generic U-turns despite the existing standard U glyph. No turn side is supplied by this text. |
| 2. Roundabout clarity | 51 inputs: 11 explicit roundabout-left, 40 third-exit. `redline-diagnostics\app.log:3320,3322,3323`, 18:49:34.962, trace `maps-1792502374962-24`: roundabout-left -> Mappls 72 -> byte 45. | `redline-diagnostics\frames.1.log:15789`: byte `2d` (45). Generic mapping loses directional specificity; full instruction text was retained. A dedicated exit-number field is not established in a531. |
| 3. Slight turns | 149 slight inputs: 50 left, 99 right. `redline-diagnostics3\app.log:16858,16860,16862`, 23:15:01.920, trace `maps-1792518301920-630`: slight-left -> ID 1 -> byte 2. `redline-diagnostics\app.log:6252,6254,6256`: slight-right -> ID 4 -> byte 5. | `redline-diagnostics3\frames.log:4146` = byte `02`; `redline-diagnostics\frames.log:584` = `05`. Original APK dictionaries prove these IDs are sharp, so the text classifier WAS reversed. Five sharp-right inputs use the slight ID 5 / byte 6 (`redline-diagnostics3\app.log:12349,12351,12353`; frame line 2970). Corrected slight output is byte 3/6; sharp is 2/5. Physical ROM appearance still requires observation. |
| 4. Distance cap | `redline-diagnostics\app.log:3320-3323`: progressMax 14696 - progress 190 = 14506 m, but total becomes `9999 M`. 33 retained inputs produce capped totals; 244 distinct serialized frame records carry `9999 M` in the total field, including earlier history without parser records. | `redline-diagnostics\frames.1.log:15789`: total `39 39 39 39 4d`. Integer-metre formatting explicitly clamped to 9999 rather than converting to km. |
| 5. Arrival | 12 terminal notifications across three arrival sequences. `redline-diagnostics\app.log:8572,8574,8576`: `Arriving`, text `at [destination]`, no template, zero progress -> ARRIVED / byte 9 / status 5. Notification removed at line 8656 (18:58:48.959), about 4.6 s after first arrival. Last arrival update line 8646 was only about 0.6 s before removal. Second sequence: `redline-diagnostics3\app.log:8817-8901`, removal 22:04:48.923. Third: lines 20978-21174, cleared at original 30 s timeout. | `redline-diagnostics\frames.log:1171`: byte `09`, zero distances, status `35` (ASCII 5). Arrival IS recognized and transmitted. Preview had no flag; notification removal also cleared it immediately. No evidence that byte 9 itself needs replacement. |
| 6. Headings | 48 heading-related inputs, 24 with an explicit compass direction. `redline-diagnostics\app.log:1098,1100,1102`: Head north -> ID 50, then intentional compass suppression -> byte 46. `redline-diagnostics3\app.log:10359` includes Head northeast. | Root cause is the earlier conservative startup policy, not missing OEM compass mappings. These are absolute instructions, not the scooter's measured current heading. Head-toward-road instructions provide no compass bearing. |
| 7. Roundabout exits/angles | `redline-diagnostics3\app.log:11161,11163,11166`, 23:05:41.918, trace `maps-1792517741918-418`: third-exit instruction -> ID 72 -> byte 45. | `redline-diagnostics3\frames.log:2706` confirms `2d`. Exit ordinal is available in text; no structured angle, circulation, incoming bearing, or road geometry was recorded. Third exit is NOT assumed to mean 270 degrees. |
| 8. Ramps/flyovers | No ramp or flyover wording in any retained INPUT field. Source recognizes only some ramp phrasings, missing `Take the left/right ramp`. | Source-proven parsing gap; synthetic tests, not a reproduced road occurrence. APK dictionaries prove IDs 17/18 are sharp forks, not ramps. Legacy ramp IDs are 74/75; Access translates them to bytes 38/10. Flyover road names alone do not establish a ramp maneuver. |
| 9. T-junction | No explicit T-junction wording or junction geometry in any retained INPUT field. Source had no explicit T-junction parsing. | Explicit T-junction text can use OEM IDs 13/14. Ordinary left/right instructions cannot reveal road topology. No geometry is fabricated. |
| 10. Keep left/right | 54 keep-left inputs, no keep-right input in any retained INPUT field. `redline-diagnostics3\app.log:4661,4663,4665`, 21:58:15.922, trace `maps-1792513695922-188`: Keep left -> ID 11 -> byte 11. | `redline-diagnostics3\frames.log:1107`: `0b`. The old visual label for ID 11 was not sufficient evidence. The APK's fork keep-left/right entries use IDs 19/20 -> bytes 27/28, now selected for keep/fork text. Actual fork/lane topology is not available from notifications. Keep-right is covered synthetically, not claimed reproduced in this ride. |

## B. Issue-to-fix mapping

Test results refer to automated software behavior, not physical glyph photos.

| Issue | Root cause | File/component affected | Fix implemented | Test result |
|---|---|---|---|---|
| U-turn | Generic text deliberately unclassified | MapplsIdGuesser, ManeuverClassifier, preview | Standard U glyph for unspecified-side text; explicit right U remains separate. Standard-U presentation is excluded from automatic hash training. | Generic, explicit left/right and legacy wire cases pass |
| Roundabout clarity | Only generic byte; no exit-number metadata | ParsedNavData, MapsNotificationContent, Home, ClusterInstructionText | Preserve ordinal separately; visible exit number on Home; compact `ROUNDABOUT EXIT 3` in existing opt-in text experiment | Ordinal parsing, unknown ordinal, field length and frame tests pass |
| Slight turns | Text-to-ID table reversed sharp/slight; preview also used wrong namespace | MapplsIdGuesser, ClusterPreview, ManeuverSymbol | APK modifier constants: slight 2/5 -> bytes 3/6, sharp 1/4 -> bytes 2/5; reuse separate preview vectors | Both sides, strengths and legacy wire cases pass; physical ROM verification remains |
| Distance cap | Metre clamp instead of numeric conversion | ParsedNavData, Home, ClusterPreview | Numeric metric/imperial conversion, bounded four-character km formatting, shared readable labels | Threshold, rounding, imperial and overflow cases pass |
| Arrival | Preview fallback; notification removal clears valid arrival | MapsNavSource/MapsNavSession, NavigationFreshness, preview | Flag icon; retain confirmed arrival until original 30 s deadline; new route replaces immediately; completion history not marked stale guidance | Virtual-clock deadline, removal, new-route and wire/status tests pass |
| Compass | Intentional suppression of every heading | MapsNotificationContent, preview | Restore eight explicit absolute compass instructions; unknown head-toward remains neutral and has no fabricated next-turn distance | Eight OEM bytes, startup-to-first-turn sequence and conflicting classification tests pass |
| Roundabout angles | Notification feed lacks the geometry used by original B0.C; old visual reference was unreliable | Metadata/logging, reference docs | Confirm OEM current/next LegStep geometry override; preserve generic byte rather than derive angle from ordinal | Safe generic fallback and ordinal tests pass; exact physical exit glyph unresolved |
| Ramps | Incomplete text variants and wrong sharp-fork IDs | MapplsIdGuesser, preview | Explicit ramp/slip-road left/right -> legacy 74/75 -> Access bytes 38/10; ramp precedes strength adjective; no flyover-name heuristic | Synthetic ramp wire cases and Access/default/unknown model branches pass; no captured ramp occurrence |
| T-junction | No explicit-text handling; no geometry feed | MapplsIdGuesser, preview | Explicit T-junction/T-intersection left/right -> existing 13/14; ordinary turns unchanged | Both explicit T-junction and ordinary-turn cases pass; geometry inference unsupported |
| Keep left/right | Visual interpretation assigned unsupported normal-keep IDs; fork/merge families were swapped | MapplsIdGuesser, classifier, preview | Dictionary fork keep family 19/20 -> bytes 27/28; legacy merge family 15/16 -> 31/32; ignore road names; text precedes hash | Keep/merge/turn/slight/ramp wire distinctions and both hash-conflict directions pass |

Generic U-turn presentation is an explicit fallback policy: Maps did not specify
left/right in the 66 captured examples. The standard OEM U glyph is used, not a
claim that the road geometry proves a left U-turn. Explicit right-U text selects
the right-U glyph. A trustworthy structured side or verified icon observation is
still needed if choosing the loop side itself matters.

The hash/text precedence change is defensive: no actual hash-vs-text conflict
was observed in these logs. A perceptual match must not replace explicit
`Keep right` with a junction turn. Existing learned v1 mappings are not deleted,
but are no longer loaded: their semantics may be contaminated. A versioned v2
cache starts empty, logs the reason and persists corrected entries normally.
The disk migration/retention/reload behavior has an automated regression.

## C. Code and assets

Main source changes (under `android\app\src\main\kotlin\dev\mrwick\redline`):

- `nav\MapplsIdGuesser.kt`: Unicode-space/hyphen handling, action-clause
  isolation, generic U, APK-corrected sharp/slight/keep/merge/ramp semantics,
  explicit ramp/T-junction and ordinals.
- `nav\ManeuverMap.kt`: v2 learned-hash cache isolates old incorrect semantics;
  verified numeric OEM translation table remains unchanged.
- `nav\ManeuverClassifier.kt`: text-first conflict resolution; retain hash
  fallback and diagnostics; no standard-U self-training.
- `nav\ParsedNavData.kt`: exit metadata, shared numerical formatter and label.
- `nav\MapsNotificationContent.kt`: metadata propagation, explicit compass
  policy; arrival and separate next-turn/route-distance rules remain intact.
- `nav\GoogleMapsParser.kt`: exit/geometry availability in both parse paths.
- `nav\ClusterInstructionText.kt`: short roundabout-exit wording through the
  existing optional identity experiment; no invented navigation text field.
- `nav\MapsNavSource.kt`: testable session state, bounded arrival retention.
- `nav\NavigationFreshness.kt`: completed arrival is not stale turn guidance.
- `ui\home\HomeScreen.kt`: exit number and shared human-readable distances.
- `ui\cluster\ClusterPreview.kt`: OEM-byte semantics, labels, accessible
  descriptions, no straight arrow for unknown bytes.
- `ui\cluster\ManeuverSymbol.kt` (new): pure cluster-byte-to-preview semantics.
  Ambiguous/model-dependent byte 44 is not assigned an invented glyph.
- `ui\components\GixxerIcons.kt`: reuse existing straight/turn/U assets; add
  missing slight, sharp, keep, T, ramp, merge, roundabout, arrival and compass
  variants in the same 24dp/24-unit, 2-unit rounded-stroke style. Left variants
  mirror shared vectors; compass rotations share one vector. No duplicate PNGs.

Tests changed: `MapplsIdGuesserTest`, `ManeuverMapTest`,
`MapsNotificationContentTest`, `ParsedNavToFrameTest`,
`ClusterInstructionTextTest`. New: `MapsNavSessionTest`,
`ui\cluster\ManeuverSymbolTest`.
Documentation: this report, `NOTES.md`, `DISCOVERIES.md`,
`docs\mappls-id-icons.md`, `docs\cluster-byte-glyphs.md`.

Wire layout, complemented checksum and verified Mappls-to-cluster table are
unchanged by this audit. Preview symbols are semantic illustrations, **not**
verified replicas of Access firmware glyphs.

### Distance convention

Below 1 km: metres. From 1 km to below 100 km: nearest 0.1 km. At/above the
rounding transition to 100 km: nearest whole km to fit four ASCII positions.
Labels remove zero padding/trailing `.0`; wire padding and M/K bytes remain.

| Actual input | Wire value/unit | App label |
|---|---|---|
| 450 m | `0450 M` | 450 m |
| 1,200 m | `01.2 K` | 1.2 km |
| 9,999 m | `10.0 K` | 10 km |
| 10,000 m | `10.0 K` | 10 km |
| 12,500 m | `12.5 K` | 12.5 km |
| 100,000 m | `0100 K` | 100 km |
| 9,999 km | `9999 K` | 9999 km |
| 10,000 km | blank K field + warning | unavailable |

Missing, negative, non-finite or unparseable distances are blank rather than
invented zero. Values beyond the protocol's representable km range are logged
and blank, not wrapped/clamped. Explicit terminal arrival still uses real zero.

## D. Validation and next hardware evidence

Final result after the APK correction: **193 tests passed, 0 failures, 0 errors,
0 skipped**. Debug APK
assembly succeeded, and `git diff --check` reported no whitespace errors.
The generated artifact is
`android\app\build\outputs\apk\debug\app-debug.apk`.

Run from `android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.mrwick.redline.nav.*' --tests 'dev.mrwick.redline.protocol.*' --tests 'dev.mrwick.redline.ble.*' --tests 'dev.mrwick.redline.ui.cluster.*' :app:assembleDebug --console=plain --quiet
```

Coverage includes the requested maneuver table, all compass headings, unknown
and null data, rapid maneuver/reroute transitions, route replacement after
arrival, superseded timeout protection, original 30 s expiry, arrival status 5,
legacy checksum round trips, distance thresholds/overflow, BLE/mux regressions,
versioned learned-hash disk behavior, ramp model branches, and vector
structure/style. These are JVM tests and an APK build, not device
pixel-rendering or road-validation tests.

Unresolved physical evidence: provide the navigation booklet/photos and use the
existing stationary maneuver sweep to photograph bytes 2/3/5/6, 7/39, 9,
13/14, 27/28, 31/32, 38/10, 45 and the intended directional roundabout slots. Correlate
each photo/video time with its transmitted byte and selected Access model.
Keep the experimental identity-text feature OFF for the baseline so banner
behavior cannot obscure the maneuver under test. It remains an experiment;
successful enqueue/write does not prove repeat banner rendering.

Notification input does not include route geometry, live vehicle heading,
structured ramp/junction topology or roundabout circulation. Ramps/T-junctions
without explicit text, flyover geometry and exact roundabout angle cannot be
reliably inferred from these captures. A structured navigation feed or additional
verified reference evidence is required; no substitute angle is fabricated.
