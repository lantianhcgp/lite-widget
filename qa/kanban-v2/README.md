# Kanban visual v2 evidence

Scope: 0.3.10/code28, com.litewidget.app, feat/kanban-20261115.
Fixed point: 68d3aa945b44393ec1f003a96ad3a9cce6ab0c4c.

## Executed gates

- Existing Python baseline: 39 tests; test files byte-for-byte unchanged.
- Final Python: 55 tests OK (16 additions), python-all-green.log.
- Host JVM: 22 tests OK (5 additions), jvm-final-green.log.
- generator --check: generated assets exactly match source.
- precheck: syntax/conflicts PASS; Android SDK type validation remains CI's responsibility.
- Four safe sizes: 4x4 / 2x4 eight slots, 4x2 four, 2x2 two; no 4x1.
- Real installed App Renderer/Android Canvas, isolated synthetic fixture: device-canvas-fixture/*.png.
- MIUI measured design-dp bounds: 138x168, 344x160, 138x385, 285x385, PA 285x168; device-host-pixels.log, all five PASS.
- Native images personally reviewed by component. Initial 37% fill suspicion was a downscaled-image misread; enlarged image + actual pixels confirmed correct fill. No engine change.
- Repo and ~/.hermes/scripts/kanban_build.py exactly synchronized; SHA256 7cf81f7c814a09c681a823d890b919bd1b0591a094a8ce417115a582333213bb.

## RED -> GREEN

- data-display-red.log -> data-display-green.log: optional elapsed, labels, counts, unknown progress.
- template-red.log -> template-green.log: 4x4 mandatory visible status and card information.
- sizes-red.log -> sizes-green.log: all four sizes retain readable status and static progress.
- schema-red.log -> schema-green.log: optional non-negative int32 elapsed.
- palette-red.log -> palette-green.log: requested running blue instead of former green.
- partial-red.log -> partial-green.log: inactive pairs collapse with their slots.
- precise-progress-red.log -> precise-progress-green.log: 0.01% / 99.99% do not display falsely as 0.0% / 100.0%; legacy percent remains compatible.
- elapsed child evidence: elapsed-red.log -> elapsed-green.log; final 55-test integration includes elapsed.

Delivery-verifier correction evidence: expected numbers were initially typed as 28 JVM tests and 5 pixel images. The real downloaded XML declared and executed 27 tests (12+5+5+4+1), and render-manifest declares 9 images (4 canonical + 5 hosts). Initial assertions correctly blocked publication. Source-method-to-XML exact set equality, no skipped/error/failure, and full manifest-to-report exact names replaced the guessed minima. Final verification passed, saved in delivery.json; no product test/threshold was removed or weakened. This was a local fetch-verifier mistake, not an incomplete CI run.

## Reviews

SPEC initial read-only review: only blocker was new progressLabel inheriting legacy one-decimal rounding.
Fixed with precise BigDecimal plain labels; legacy field unchanged. SPEC local re-review 19:41:34 CST: blocker cleared, JVM22 and Python6 rerun, no other local blockers. Reviewer delegation deleg_2884e9a3.
QUALITY integrated read-only review 19:44:40 CST: APPROVED, zero must-fix; current source/tests/preview/host and schema checked, Python55, generator, diff executed; old test preservation and script byte equality verified. Reviewer delegation deleg_0b20f676.

## Evidence boundary

Fixture tasks are synthetic and explicitly labelled in this report as 自检; these are actual native Canvas PNGs, not a browser/Pillow recreation of the UI. Pixel montage only arranges original PNGs. Existing App 0.3.9 was used to exercise the unchanged Renderer. New package's own native Canvas and live socket tests run separately in CI. This is NOT proof of new-version desktop installation. Device installation is exclusively the requesting party's step; no install, clear-data, force-start, reload of real widget, real-board push, new timer/permission, master merge or release was performed here.

Existing task-kanban files are not overwritten by seeding on overlay upgrade. Requesting party should back them up, explicitly copy updated manifest/widget via MCP after installation, read back, then manually --push/reload and capture actual desktop.
