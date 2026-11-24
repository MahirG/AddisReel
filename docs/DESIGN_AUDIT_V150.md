# AddisReel 1.5 — Principal UI/UX Engineering Audit

**Baseline:** signed Android 1.4.0 / Build 23, GitHub `feature/addisreel-v140-watch-integrity`. Scope is the **native Android application**, not the separate Cloudflare Studio admin website. This document records a static review of the entire Android source and resources; it is **not** a claim of screenshot or on-device validation.

## Inventory: screens and significant states

| Surface | Inspected code paths | Primary concerns | Owner |
| --- | --- | --- | --- |
| Launch/splash | `styles.xml`, launcher vector, MainActivity | Splash dark-only; Android system-bar theme mismatch | App theme |
| Watch | ExoPlayer, player overlays, swipe gesture, fullscreen, chapters | Too many visually equal controls; tiny labels; player needs fixed dark overlay semantics and screen-reader labels | Player |
| Home | Featured hero, search affordance, viewing rails, genre chips | Repeated hardcoded card colors; dense metadata; text hierarchy inconsistent | Home |
| Search/Discover | Search field, category/sort filters, empty results, media cards | Small filter controls, inconsistent semantics and helper text | Discover |
| Library | Saved/History/Completed tabs, cards, continue rows, empty states | Hardcoded color/size and compressed touch actions | Library |
| Premium/Updates | Offers + native in-app update panel | Update panel not visually unified; progress lacks spoken semantics | Premium/Updates |
| Overlays | Series details AlertDialog, Clear History AlertDialog, episode bottom sheet | Inconsistent modal surfaces, item padding and selected state | Dialogs |
| Interactions | Media retry, buffering, unavailable, cached/offline catalog, playback completed | Error/success/empty/status roles lack consistent semantic treatment | Status layer |

### Measured baseline (static source inventory)

The 122,727-character MainActivity contains **109 Text calls**, **57 explicit `fontSize=` assignments**, **32 explicit `fontWeight=` assignments**, **53 hardcoded ARGB `Color(0x...)` occurrences**, approximately **164 `.dp` literals**, **12 IconButton calls**, **5 AsyncImage calls**, and one inline **dark-only** MaterialTheme. There is **no bundled font file** and **no typography or semantic color module**. The app also declares a splash `Theme.Material.Light.NoActionBar` base while drawing a dark Compose surface.

## Root causes and remediation plan

| Severity | Inconsistency / root cause | Remediation |
| --- | --- | --- |
| Critical | Theme forced dark inline inside screen tree; dialogs above it inherit a different theme | Single top-level MaterialTheme and persistable System/Light/Dark preference |
| High | Accumulated literal hex values + gold/gray used as decoration without contrast roles | Semantic light/dark palette; retain cinematic amber brand accent |
| High | More than 50 hand-picked font sizes, tiny 8–11sp captions and mostly bold headings | Central Material typography and compact type scale with language-aware line height |
| High | No localized screen labels or Ethiopic font QA; default Android fallback used implicitly | Add native AM/OM/EN navigation strings; use Android script fallback and explicit Ethiopic metrics; verify on device before claiming quality |
| High | Non-decorative icons sometimes have null contentDescription; controls not always distinguishable | Accessible descriptions; 48dp touch targets and role-aware semantics |
| High | Arbitrary radii / widths and 8–20dp padding | Spacing/radii/icon tokens and reusable components |
| High | Media overlay styles share colors with unrelated UI surfaces | Preserve dedicated dark cinematic palette for video/gradient overlays |
| Medium | Several screens and dialogs rely on exact heights or compact rows | Adaptive padding and scrolling around font scaling |
| Medium | App update state/progress not announced clearly by TalkBack | Distinct accessible status/progress semantics in updater |
| Medium | No screenshot, TalkBack, contrast, tablet or real-device visual regression pipeline | Automated token/contrast tests and repeatable device screenshot acceptance plan |
| Medium | `MainActivity.kt` mixes navigation, player and every screen | Introduce reusable theme/components first; extract screens incrementally after a green baseline |

## Design principles and acceptance

1. Preserve the dark black-plum/amber identity and light variant, **not** borrowed brand layouts.
2. Keep artwork and full-screen video immersive but readable with a dedicated overlay palette.
3. Use semantic text, background, selected, stroke, status and media colors.
4. Material3 surfaces and text inherit the centralized system; no font binary/dependency added by default.
5. Prioritize WCAG contrast thresholds, native font scaling, 48dp interactive targets and reduced-motion preferences.
6. No loss of Watch, autoplay, subtitles, search, filters, history, premium or secure updates.
7. Do not publish to the in-app update feed without independent build/test gates and **visual screenshots and device QA**. In absence of an Android emulator or physical device, mark those gates blocked.

## Acceptance matrix (must be actually executed)

- [ ] Static audit and centralized token review
- [ ] Kotlin test suite for design token contrast and script-sensitive typography
- [ ] Backend tests and Android debug/signed builds
- [ ] Dark/light screenshot pair for every tab, dialogs and player states
- [ ] Android font scale 1.0 / 1.3 / 2.0; 320dp width and tablet; portrait/landscape
- [ ] Amharic + Afaan Oromo + English visible rendering, manual inspection
- [ ] TalkBack focus order, labels, actions and progress announcements
- [ ] Screenshot diffs manually reviewed; battery/scroll metrics from real device
- [ ] Release signing and in-app installation on physical phone

Document each actual outcome and known blocker separately from implementation claims.
