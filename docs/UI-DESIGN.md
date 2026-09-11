# LocalChat — UI/UX Design Specification

**App:** LocalChat (`fyi.amago.localchat`) — on-device LLM chat, LiteRT-LM / Gemma 4 E2B
**Target device:** Samsung Galaxy S25 Ultra (6.9", 1440 × 3120 px, ≈ 411–480 dp wide × ≈ 890–1040 dp tall depending on the display density setting)
**Stack (frozen):** Kotlin 2.4.20, AGP 8.7.3, Compose BOM `2024.12.01` → Compose UI/foundation **1.7.6**, Material3 **1.3.1**, activity 1.9.3, minSdk 28, targetSdk 35, arm64-v8a only.
**Hard constraint:** no new third-party dependencies. Everything below is Material3, foundation, the Compose UI toolkit, and platform APIs already on the classpath.

> **Status note.** This document was written against the tree at **`757431b` — "0.2.0: persist chat history + fix input hidden behind keyboard"**. That commit already landed: `enableEdgeToEdge()`, `ChatStore` history persistence, `ChatMessage.timeMs`, a first-pass `imePadding()` on the content column, and the empty-state copy. **The spec therefore reads as a delta on top of 0.2.0**, and §12.1 is the change table.
>
> On the keyboard specifically: 0.2.0 does make the input row reachable above the IME — that bug is closed. What remains is a *correctness-of-layout* refinement: the current `padding(padding).imePadding()` adds the Scaffold's navigation-bar inset on top of the IME height, so the composer hovers one navigation bar too high (~24 dp with gesture navigation, 48 dp with three-button navigation). §6.1 fixes the arithmetic; the rest of the document is the design work that has not been started.

> **How to read this.** §2 is the defect list, with the code symbol that causes each one. §4 is the token set (colour, type, spacing, motion) that every later section references. **§6.1 is the inset contract — read it before touching any layout.** §7 is the per-component spec, §8 the state matrix, §12 the copy-paste code, §13.2 the ranked order of work if you cannot do all of it.

---

## 1. Executive summary

LocalChat is functionally complete: it downloads a model, loads it on the GPU, streams tokens, and remembers the conversation. What it does not yet look like is an app someone shows to another engineer.

The design goal is narrow and achievable with the toolkit already in the project:

1. **The composer is the product.** Everything else is chrome. It must be reachable one-handed, never covered by the keyboard, and never leave the user unable to see what they typed.
2. **The answer is the product.** Message text gets the largest type on screen, the highest-contrast container, and the most screen width. Chrome gets less.
3. **Nothing moves unless the user moved it.** A streaming answer must never fight the scroll position.
4. **Expensive or destructive things get confirmed.** A 1.9 GB download and "delete my model" are dialogs; typing and sending are not.

Three changes carry most of the value (full ranking in §13):

| # | Change | Why it matters |
|---|--------|----------------|
| 1 | **Fix the inset contract** — `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` + composer pads `safeDrawing` *bottom*, which is `max(nav bar, IME)` | Removes the dead band above the keyboard and guarantees the field is visible. The current `padding(padding).imePadding()` stacks the Scaffold's nav-bar inset *on top of* the IME height, so the input row floats one nav bar too high (~24–48 dp of wasted space) — the exact complaint this task is about. |
| 2 | **Rebuild the composer** — 56 dp circular send/stop button, `ImeAction.Send` so the IME's own key sends, input stays enabled while generating, hint + model CTA when nothing is loaded | One-handed reach and "type ahead" are the two most-felt ergonomics gaps. |
| 3 | **Move the model manager into a `ModalBottomSheet`** with a download confirmation, in-sheet progress and per-file delete | Stops a 1.9 GB form from occupying the top third of the chat, and stops an accidental re-download. |

---

## 2. What is wrong today (evidence-based)

Findings are grouped by user impact. Each one names the behaviour, then the cause in the code.

### P0 — the keyboard

**F1. The input row floats one inset too high when the keyboard opens.**
`ChatScreen` is a `Scaffold` with default `contentWindowInsets` (`WindowInsets.systemBars`) and the content column does `Modifier.fillMaxSize().padding(padding).imePadding()`. `padding` already carries the navigation-bar inset, and the IME inset reported by the platform already *includes* the navigation-bar area. The two are added, so the composer sits `navBarHeight` above the keyboard — 24 dp with gesture navigation, 48 dp with 3-button navigation. On a 6.9" phone that is a visible dead band right where the eye and thumb land.
**Fix:** zero the Scaffold's window insets and let the composer pad itself with `WindowInsets.safeDrawing` (see §6.1).

**F2. The field is visible but the layout around it is not specified.** Nothing in the tree calls `Modifier.consumeWindowInsets`, so there is no single owner of the inset contract. The rule in §6.1 (Scaffold consumes nothing, each region pads exactly the sides it draws under) makes this testable in one glance.

**F3. `android:windowSoftInputMode="adjustResize"` must stay.** With targetSdk 35 the platform forces edge-to-edge and no longer resizes the window for the IME; Compose reports insets instead. `adjustResize` is still what makes IME insets arrive on API 28–29. Deleting it is a common "fix" that silently breaks the keyboard on older devices. Keep it.

### P0 — chat behaviour

**F4. Streaming steals the scroll position.** `LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) { listState.animateScrollToItem(lastIndex) }` fires on **every token**. Two consequences: the user cannot scroll up to re-read an earlier answer while one is generating, and an *animated* scroll per token is both janky and battery-cheap-looking. Fix in §7.5: follow only while the user is parked at the tail, and use a non-animated snap while streaming.

**F5. Bubbles are hard-capped at 320 dp.** `Modifier.widthIn(max = 320.dp)` on a screen that is 411–480 dp wide wastes roughly a quarter of the width and forces 14 sp text to wrap two lines earlier than it needs to. The cap should be *proportional* (86 % of the row) with a 560 dp ceiling for tablets/landscape (§7.4).

**F6. Assistant bubbles use the lowest-emphasis container in M3.** `surfaceVariant` / `onSurfaceVariant` is the "less important than body text" pair. The model's answer is the reason the app exists; it belongs on `surfaceContainerHighest` / `onSurface`.

**F7. Streaming looks like a placeholder.** A streaming bubble with no text yet renders the literal string `"…"`, and a streaming bubble with text renders no cursor at all. There is no visual difference between "the model is thinking", "the model is writing", and "the model is finished".

**F8. Timestamps are printed under every bubble.** At 11 sp `labelSmall` this is pure noise — consecutive messages from the same speaker are seconds apart. Show one timestamp per *run*, and a date separator when the conversation crosses midnight (§7.4).

**F9. The chat list has no copy affordance at the app level.** Bubbles are wrapped in `SelectionContainer` (good), but there is no way to copy the whole exchange, and no long-press menu. §7.8 adds "Copy transcript" to the overflow.

### P1 — model management

**F10. The model panel is inline and always on top.** It renders whenever a model is not `Ready`, so a first-run user gets a form occupying the top of the screen with a one-line greeting underneath. A 1.9 GB download plus a file picker and an `adb` tip do not belong in the transcript's column.

**F11. Expensive actions are unconfirmed and repeated.** `Download Gemma 4 E2B` is enabled whenever the state is not `Downloading`/`Loading` — including when a model is already `Ready`. One mis-tap re-downloads 1.9 GB. There is no "this costs 1.9 GB, use Wi-Fi" confirmation.

**F12. There is no way to delete or cancel.** No cancel for an in-flight download, no delete for an installed `.litertlm` file. The `adb push` hint (useful, developer-facing) is shown at full prominence to every user.

### P1 — shell, theming, accessibility

**F13. `Theme.LocalChat` is a Light-only platform theme.** `parent="@android:style/Theme.Material.Light.NoActionBar"` with `windowBackground #FFFBFE`. In dark mode the pre-Compose window frame is white (a visible flash on launch), and the status-bar/gesture-bar icons are styled for a light surface — dark-on-dark or light-on-light depending on the device. There is no `values-night` variant.

**F14. No typography override.** Message text is stock `bodyMedium` (14 sp). This app is read while walking, standing, and driving; the answer text should be `bodyLarge` (16 sp / 24 sp), with a one-tap **Glance mode** that takes it to 20 sp / 30 sp.

**F15. Notices are inline text.** `state.notice` renders as a `Text` above the list, which pushes the transcript down mid-stream. `vm.clearNotice()` exists but is never called, so a notice ("Importing …") stays on screen forever.

**F16. Accessibility is unimplemented.** No `contentDescription` on any icon, no live region for incoming answers, no minimum touch targets on the Send/Stop text buttons, app-bar actions are bare `TextButton("Model")` / `TextButton("New")`.

**F17. "New chat" does not actually start a new chat.** `ChatViewModel.newChat()` calls `engine.cancel()` and clears the message list, but `LlmEngine` keeps the same `Conversation` object — which owns the model's context. The next prompt is answered *with the previous conversation still in context*. The history is gone from the screen and still present in the model. Fix in §12.2 (`LlmEngine.resetConversation()`), because it is a correctness bug a UI spec cannot paper over.

---

## 3. Design principles

1. **Thread-first.** The transcript owns the screen. Model management, settings and diagnostics are all reachable but none of them take space from the conversation.
2. **One primary action per state.** Ready → Send. Generating → Stop. No model → Download/Load. Never two competing primary buttons.
3. **Thumb zone discipline.** Every frequent action lives in the bottom 25 % of the screen: send/stop, clear field, jump-to-bottom. The app bar holds only infrequent things (model status, overflow menu).
4. **Glanceable state.** Model readiness is one coloured dot and one word. Download progress is one line. A user who glances at a phone in a mount should know the state in under a second.
5. **Motion follows content, not the user.** New messages animate in. Placement animates. But nothing scrolls under the user's finger, and nothing animates while streaming.
6. **Text is the interface.** No illustration, no decorative chrome. The type scale does the work.

---

## 4. Design system

All tokens live in one file (`ui/theme/Theme.kt`) so the spec and the code cannot drift.

### 4.1 Colour roles

Dynamic colour (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) is used as-is on Android 12+ — an S25 Ultra gets Samsung's wallpaper palette, which is the correct "native Android app" signal. The fallback palette below (API < 31, or if a future setting turns dynamic colour off) is a Material 3 tonal palette derived from a **blue #0B57D0** source hue.

| Role | Light | Dark | Used for |
|---|---|---|---|
| `primary` | `#0B57D0` | `#ADC6FF` | Send button, active progress, in-flight status dot |
| `onPrimary` | `#FFFFFF` | `#002E69` | Send icon |
| `primaryContainer` / `onPrimaryContainer` | `#D8E2FF` / `#001A41` | `#1A4BA0` / `#D8E2FF` | **User** bubble, empty-state badge |
| `tertiary` | `#0F6B4F` | `#8BD6B6` | "Ready" status dot, "in use" check |
| `error` / `errorContainer` | `#BA1A1A` / `#FFDAD6` | `#FFB4AB` / `#93000A` | Failed model, inline generation errors |
| `background`, `surface` | `#FDFBFF` | `#111318` | Scaffold, app bar, window background |
| `surfaceContainerLow` | `#F7F5FA` | `#191C20` | Composer bar, bottom sheet |
| `surfaceContainer` | `#F1EFF5` | `#1D2024` | Input field (idle), chips |
| `surfaceContainerHigh` | `#EBE9EF` | `#282A2F` | Status pill, date chip, code blocks, jump-to-bottom FAB |
| `surfaceContainerHighest` | `#E6E3EA` | `#33353A` | **Assistant** bubble, input field (focused) |
| `outlineVariant` | `#C4C6D0` | `#44474F` | Composer top divider, progress track |
| `onSurfaceVariant` | `#44474F` | `#C4C6D0` | Timestamps, supporting copy, secondary labels |

Two rules that matter for legibility:

- **Never** use `surfaceVariant`/`onSurfaceVariant` for a container holding body text. Use the `surfaceContainer*` ladder; it keeps text contrast independent of elevation.
- User and assistant bubbles must differ by **both** colour and alignment — colour alone fails in greyscale, in high-contrast mode, and for colour-blind users.

### 4.2 Typography

Stock Material 3 shape, one promotion, one optional boost.

| Token | Size / line height | Used for |
|---|---|---|
| `titleLarge` (semi-bold) | 22 / 28 | App bar title, "Model" sheet title, empty-state headline |
| `titleSmall` | 14 / 20 | Sheet section headers, status card title |
| `bodyLarge` | **16 / 24** | **Message text**, empty-state body, composer input |
| `bodyMedium` | 15 / 22 | Progress captions in the empty state |
| `labelLarge` (semi-bold) | 14 / 20 | Buttons, "Load a model to start chatting" |
| `labelMedium` | 12 / 16 | Status pill, progress percentages, glance-mode timestamps |
| `labelSmall` | 11 / 16 | Timestamps, fine print, date chips |
| `bodyLarge` + 20 / 30 | — | Message text in **Glance mode** |
| Monospace 13 / 19 | — | Fenced code blocks |

The stock `Typography` is patched in `LocalChatTheme` (`bodyLarge` 16/24, `bodyMedium` 15/22, semi-bold `titleLarge`/`titleMedium`/`labelLarge`), so no call site has to remember sizes. Glance mode is a `CompositionLocal`, not a second theme.

### 4.3 Spacing, size and shape

| Token | Value | Notes |
|---|---|---|
| `screenGutter` | 16 dp | List and sheet horizontal padding |
| `bubbleGap` | 3 dp | Between two messages from the same speaker |
| `groupGap` | 14 dp | Between two different speakers |
| `bubbleWidthFraction` | 0.86 | Fraction of the row width a bubble may occupy |
| `bubbleMaxWidth` | 560 dp | Hard ceiling on tablets and landscape |
| `bubblePadding` | 14 dp horizontal / 10 dp vertical | Inside a bubble |
| `touchTarget` | 56 dp | Send, Stop, and the minimum text-field height |
| `cornerBubble` / `cornerBubbleTight` | 20 dp / 6 dp | Asymmetric "tail" corner |
| `cornerSheet` | 28 dp | Bottom-sheet top corners (Material3 default) |

Shape language: bubbles are 20 dp rounded with a 6 dp tail on the sender's bottom corner; grouped messages flatten their touching corners to 6 dp so a run reads as one block. Chips and status pills are fully rounded (`RoundedCornerShape(percent = 50)`). The input field is 24 dp — round enough to feel like a modern composer, not a form field.

### 4.4 Motion

| Element | Spec |
|---|---|
| New message appearing | `LazyItemScope.animateItem()` — default fade-in + placement. Skipped for the streaming item (its height changes on every token). |
| Streaming caret | 520 ms alpha blink, `RepeatMode.Reverse`, `LinearEasing` |
| "Thinking" indicator | 3 dots, 600 ms cycle, 160 ms stagger, alpha 0.25 → 1.0 |
| Send ⇄ Stop swap | Direct swap of the button content. Height is identical (56 dp) so nothing shifts. |
| IME | Platform animation; the composer rides it via `imePadding`-equivalent insets, no custom animation |
| Jump-to-bottom FAB | `animateScrollToItem` when tapped; the FAB fades in/out via `AnimatedVisibility` |
| Scroll while streaming | `scrollToItem` (no animation) — never `animateScrollToItem` per token |

No animation exceeds ~250 ms except the deliberate loops (caret, typing dots). Nothing bounces, nothing springs.

### 4.5 Iconography

`material-icons-core` 1.7.6 is on the classpath (transitively via Material3); **`material-icons-extended` is not, and adding it is a new dependency.** Only the 50 core icons exist. The design uses exactly six:

| Icon | Where | `contentDescription` |
|---|---|---|
| `Icons.AutoMirrored.Filled.Send` | Send button | `"Send"` |
| *(drawn)* 18 dp rounded square, 3 dp radius | Stop button | `"Stop generating"` (via `semantics`) |
| `Icons.Filled.Clear` | Clear input (trailing icon) | `"Clear the message"` |
| `Icons.Filled.KeyboardArrowDown` | Jump to latest | `"Jump to the latest message"` |
| `Icons.Filled.MoreVert` | Overflow menu | `"More options"` |
| `Icons.Filled.Lock` | Empty-state badge (privacy) | `null` (decorative) |
| `Icons.Filled.Check` / `Icons.Filled.Delete` | Sheet: active model / delete model | `null` / `"Delete <file>"` |

There is **no `Icons.Filled.Stop` and no `Icons.Filled.ContentCopy` in icons-core.** The stop glyph is therefore drawn (which is what Material 3 does anyway), and "copy" is exposed as a menu item, not an icon.

---

## 5. Information architecture

```
┌─────────────────────────────────────────────┐
│  ChatScreen (the only screen)               │
│                                             │
│  TopAppBar ── title + model status pill ────┼─► tap pill / "Models & storage"
│  │                                          │        │
│  │  [3 dp download progress bar]            │        ▼
│  │                                          │   ModelSheet (ModalBottomSheet)
│  │  EmptyState        or      Message list  │   ├─ active model card (state + progress)
│  │                                          │   ├─ Download (confirmed) / Load file…
│  │  [jump-to-bottom FAB, oldest-anchored]   │   ├─ installed models (load / delete)
│  │                                          │   └─ Advanced: adb push recipe
│  Composer (divider, field, send/stop)       │
└─────────────────────────────────────────────┘
        │
        └─► Overflow menu: New chat · Glance mode · Copy transcript · Models & storage
```

No new navigation destinations. No back-stack changes. The only new surface is the modal bottom sheet, which already has correct back/dismiss semantics from Material3.

---

## 6. Layout and the inset contract

### 6.1 The inset contract (read this before touching layout)

The rule, in one line: **the Scaffold consumes no window insets, and each region pads exactly the sides it draws under.**

```kotlin
Scaffold(
    contentWindowInsets = WindowInsets(0, 0, 0, 0),   // <— the line that fixes the keyboard
    topBar = { TopAppBar(...) },                      // pads status bar itself (TopAppBarDefaults)
    snackbarHost = { SnackbarHost(hostState) },
) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(top = innerPadding.calculateTopPadding()),   // app bar height only
    ) {
        DownloadBar(...)                              // 3 dp, only while downloading
        Box(Modifier.weight(1f)) { /* list or empty state */ }
        Composer(...)                                 // pads its own bottom by safeDrawing
    }
}
```

Why each part:

| Decision | Reason |
|---|---|
| `contentWindowInsets = WindowInsets(0, 0, 0, 0)` | With no bottom bar, the default (`systemBars`) puts the **navigation-bar inset into `innerPadding.bottom`** — the very inset the composer adds for itself. That sum is the dead band. Zeroing it makes `innerPadding` mean exactly "the height of the app bar". |
| `WindowInsets.safeDrawing.only(Bottom)` on the composer | `safeDrawing` = `systemBars ∪ displayCutout ∪ ime`, resolved as a **max**, not a sum. One call covers "above the gesture bar" and "above the keyboard" with no double counting. |
| `Horizontal` insets on the content column | Landscape cutouts and the side-edge gesture strips. The app bar keeps its own full-width background. |
| `adjustResize` stays in the manifest | Required for IME insets to arrive on API 28–29 under edge-to-edge. |
| `enableEdgeToEdge()` before `super.onCreate()` | `WindowCompat.setDecorFitsSystemWindows(window, false)` + transparent system bars, so insets are actually reported. |

Expected result on the S25 Ultra: field bottom edge sits 8 dp above the keyboard's top edge; the field's top edge is still visible below the app bar; the list shrinks and its last item stays pinned above the composer.

### 6.2 Region measurements (portrait, 411–480 dp wide)

```
 status bar            ⌐ ~24-48 dp (TopAppBar pads it; title never under the clock)
 ┌────────────────────────────────────────────────────────────┐
 │ LocalChat                            [● GPU]  [⋮]          │  TopAppBar 64 dp
 ├────────────────────────────────────────────────────────────┤  ← surface, no divider
 │ ▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌▌ 3 dp, only while downloading       │
 │                                                            │
 │   ┌──────────────────────────┐                             │  assistant: 14 dp top on
 │   │ Prose answer text, 16/24 │                             │  first of run, 3 dp between
 │   └──────────────────────────┘                             │
 │                      ┌─────────────────────────┐           │  86 % max width, cap 560 dp
 │                      │ User message, 16/24     │           │  ← primaryContainer
 │                      └─────────────────────────┘           │
 │                                           14:32            │  ← one stamp per run
 │                                                            │
 │                    [ jump ↓ ]                              │  FAB, only when scrolled up
 ├────────────────────────────────────────────────────────────┤  ← outlineVariant divider
 │ ┌──────────────────────────────────────────┐  ╭──────╮     │  field ≥ 56 dp, radius 24
 │ │ Message LocalChat…                   [x] │  │ send │     │  button 56 dp, circular
 │ └──────────────────────────────────────────┘  ╰──────╯     │
 │                      8 dp                                  │
 └────────────────────────────────────────────────────────────┘
 [ keyboard — field stays 8 dp above it ]
```

Key measures: 16 dp gutters, 12 dp composer side padding, 8 dp composer vertical padding, 8 dp gap between field and button, list content padding 16/16/8/12 dp.

### 6.3 One-handed reach (right-hand hold, 6.9" device)

The bottom ~30 % of the screen is where the thumb lives. Everything frequent is there:

| Zone | Contents | Frequency |
|---|---|---|
| Bottom 30 % | Send / Stop (56 dp), input field, clear button, jump-to-bottom FAB | constant |
| Middle 40 % | Transcript (read-only) | constant |
| Top 30 % | App bar, status pill, overflow menu | rare |

The app bar's right side holds only a status pill and a `⋮`. Long text (`Model`, `New`) is gone from the bar because it both wastes the widest hit target on the screen and reads as a jammed-together toolbar.

### 6.4 Landscape and large screens

No layout switch is required (that would need `material3-window-size-class`, a new dependency). Instead:

- Bubbles cap at 560 dp and stay centred because the row is `fillMaxWidth` with the bubble aligned to its side; extra width becomes margin, not line length.
- The content column pads `Horizontal` safe insets, so cutouts and gesture strips never clip a bubble.
- The bottom sheet is height-bounded by the platform, and its body scrolls.

Future (would need a new dependency): two-pane master/detail, or a side-anchored rail. Out of scope for this round.

---

## 7. Component specifications

### 7.1 App bar — title + model status pill

- `TopAppBar`, `containerColor = colorScheme.surface`, no divider, no shadow. The 3 dp download bar under it is the only separator while downloading.
- Title: `"LocalChat"`, `titleLarge`, semi-bold.
- **Status pill** in `actions`, before the overflow. One coloured dot (8 dp) + one word, `labelMedium`, on `surfaceContainerHighest` with a fully rounded shape, 10 dp × 6 dp padding. The whole pill is the tap target (`clickable(role = Role.Button)`) and opens the model sheet.

| `ModelState` | Dot | Label |
|---|---|---|
| `Missing` | `error` | `No model` |
| `Downloading`, total > 0 | `primary` | `37%` |
| `Downloading`, total unknown | `primary` | `Downloading` |
| `Loading` | `primary` | `Loading` |
| `Ready` | `tertiary` | `GPU` / `CPU` |
| `Failed` | `error` | `Error` |

Rationale: the backend is the single most interesting piece of information for the owner and it changes rarely; it deserves one word, not a subtitle line. The empty state and the sheet carry the explanatory prose.

- Overflow (`⋮`) menu: **New chat**, **Glance mode** (trailing check when on), **Copy transcript** (disabled when empty), divider, **Models & storage**.

### 7.2 Empty state (first run, or an emptied conversation)

Replaces the one-sentence greeting. Vertically scrollable so it survives a 2.0× font scale.

1. 64 dp circular badge, `primaryContainer`, `Icons.Filled.Lock` inside — the privacy claim, as an icon, not a paragraph.
2. Headline `"Chat that never leaves your phone"` (`titleLarge`, centred).
3. Body `"Gemma 4 E2B runs on this device. No account, no API key, no uploads."` (`bodyLarge`, `onSurfaceVariant`).
4. A state-specific block:

| Model state | Block |
|---|---|
| `Missing` | Primary `Button` "Download Gemma 4 E2B · 1.9 GB"; `OutlinedButton` "Load a .litertlm file"; if models are already installed, a `TextButton` "Choose one of your N installed models"; fine print "Apache-2.0 · stored in Android/data/…". |
| `Downloading` | 8 dp rounded `LinearProgressIndicator` + "Downloading the model — 412.3 MB of 1.9 GB". |
| `Loading` | 18 dp spinner + "Loading the model into memory…". |
| `Ready` | `"Try one of these"` + a `FlowRow` of 4 `AssistChip`s that fill the composer with a prompt (one tap to a useful answer is the single best thing an on-device model can offer). |
| `Failed` | Error headline + the message + "Try again" + "Load a .litertlm file". |

Suggested prompts (short, one-tap, genuinely useful offline):

```
"Explain how Wi-Fi calling works, briefly"
"Turn my shopping list into a meal plan"
"Draft a short, friendly reschedule email"
"Write a tiny Kotlin coroutine example"
```

### 7.3 Composer

Anatomy, top to bottom:

1. `LinearProgressIndicator` 2 dp, `primary` on transparent — **only while generating**. Doubles as the "the model is running" signal for someone watching from a mount.
2. `HorizontalDivider` (`outlineVariant`) — the only divider between transcript and composer.
3. When no model is `Ready`: an inline hint row, `labelLarge`, `onSurfaceVariant`, "Load a model to start chatting" + a `TextButton("Model")` that opens the sheet. This replaces a dead disabled field with a path forward.
4. Input row: `OutlinedTextField` (weight 1) + 56 dp circular action button, `Alignment.Bottom` so the field grows upward and the button stays put.

Text field spec:

| Property | Value | Why |
|---|---|---|
| `shape` | `RoundedCornerShape(24.dp)` | Composer, not form |
| container | `surfaceContainerHighest` focused, `surfaceContainer` idle, border transparent | Material 3 filled-composer look without a second component |
| `textStyle` | `bodyLarge` (Glance: 18/26) | Same size as the answers |
| `minHeight` | 56 dp | `touchTarget`; a 48 dp field is awkward while walking |
| `maxLines` | 6 | Grows to ~6 lines, then scrolls internally |
| `trailingIcon` | `Icons.Filled.Clear`, only when non-empty | One tap to restart a thought; `contentDescription = "Clear the message"` |
| `imeAction` | `ImeAction.Send`, `KeyboardCapitalization.Sentences` | The IME's own key sends; also Enter on a hardware keyboard |
| `keyboardActions.onSend` | send if `canSend` | Wired, not empty |
| `enabled` | `modelReady` — **not** `&& !generating` | Type-ahead while the model talks is a real chat behaviour |

Action button: **one 56 dp circular button, two jobs.**

- Idle/Ready → `FilledIconButton` with `Icons.AutoMirrored.Filled.Send`; `enabled = modelReady && !generating && value.isNotBlank()`; `contentDescription = "Send"`.
- Generating → `FilledTonalIconButton` whose content is an 18 dp rounded square (3 dp radius) in `onSecondaryContainer`; `contentDescription = "Stop generating"`. Same size and position, so the tap target never moves.
- Both fire `HapticFeedbackType.LongPress` on press (`LocalHapticFeedback`) — the only haptic in the app, reserved for "you sent/told a 2B model what to do".

**Guardrail:** the button must never be a `TextButton`/`Button` labelled "Send"/"Stop". Text buttons are ~40 dp tall and sit under the 48 dp minimum; they are also unlabelable to a screen reader in a glance.

### 7.4 Message bubbles and the transcript

Bubble anatomy: alignment (`End` for user, `Start` for assistant), container colour, container shape, and one optional timestamp.

| Aspect | User | Assistant | Failed |
|---|---|---|---|
| Alignment | `End` | `Start` | `Start` |
| Container | `primaryContainer` | `surfaceContainerHighest` | `errorContainer` |
| Content colour | `onPrimaryContainer` | `onSurface` | `onErrorContainer` |
| Max width | `min(0.86 × row, 560 dp)` | same | same |
| Shape | 20 dp, 6 dp on the bottom-end (and the group seam) | 20 dp, 6 dp on the bottom-start (and the group seam) | as assistant |

Layout rules:

- **Grouping:** two consecutive messages from the same speaker are 3 dp apart and share a flattened seam corner; a speaker change costs 14 dp. This is what makes a long answer read as one block instead of a pile of cards.
- **Width:** measured with `BoxWithConstraints`, `(maxWidth * 0.86f).coerceAtMost(560.dp)`. Never a fixed dp cap.
- **Padding inside:** 14 dp horizontal, 10 dp vertical.
- **Selection:** every finished bubble is wrapped in a `SelectionContainer`, so long-press → select → copy works like every other chat app. (Trade-off: a `SelectionContainer` swallows long-press, so there is *no* per-message long-press menu; the app-level "Copy transcript" covers the bulk case.)
- **Timestamp:** one per *run*, under the last bubble of the run, `labelSmall` (`labelMedium` in Glance), `onSurfaceVariant`, 4 dp above. Not under every bubble.
- **Date separator:** a centred pill (`surfaceContainerHigh`, fully rounded, `labelSmall`) reading `Today` / `Yesterday` / `12 Sep`, emitted when the calendar day changes between two messages.
- **Streaming, no text yet:** three dots, 7 dp, `600 ms`, `160 ms` stagger — "thinking".
- **Streaming, with text:** the text so far plus a blinking block caret (`▌`) as a `SpanStyle` whose alpha animates `1 → 0` over 520 ms, reversed. Extracted into its own composable so only that `Text` recomposes per frame.
- **Failed generation:** the VM already prefixes error text with `⚠️`; render any assistant bubble starting with that marker on `errorContainer`/`onErrorContainer`. (A proper `isError` flag on `ChatMessage` would be cleaner — see §14.)
- **Code blocks:** a ```` ``` ```` fence becomes a `surfaceContainerHigh`, 12 dp-rounded block, monospace 13/19, `softWrap = false`, horizontally scrollable. No markdown dependency; the splitter is ~20 lines (§12.5) and degrades to plain prose for unfenced text.
- **Accessibility:** finished assistant bubbles set `liveRegion = LiveRegionMode.Polite`; streaming ones set `None` (a live region that changes 30×/s is unusable with TalkBack).

### 7.5 Scroll policy

Three rules, in priority order:

1. **The user owns the position.** Auto-follow only while the user is parked at the tail. Track it from the scroll events themselves: `snapshotFlow { isScrollInProgress to canScrollForward }`, and update `followTail` only while a drag/fling is in progress. This is immune to the "content grew, therefore we are no longer at the bottom" false negative that a naive `!canScrollForward` check produces during streaming.
2. **Sending snaps; streaming does not animate.** On `messages.size` changing (a send, or a restored history) → `followTail = true` + `animateScrollToItem(lastIndex)`. On the text of the last message changing → `scrollToItem(lastIndex)`, animated position changes during a 30-token/s stream are the single biggest source of jank.
3. **Escape hatch.** When `!followTail && messages.isNotEmpty()`, a `SmallFloatingActionButton` (`surfaceContainerHigh`, `KeyboardArrowDown`) floats 12 dp above the composer's top edge inside the list's `Box`. Tapping it sets `followTail = true` and animates to the newest message. It fades in/out via `AnimatedVisibility`.

### 7.6 Model manager (`ModalBottomSheet`)

Opens from the status pill, the overflow ("Models & storage"), the composer hint, or automatically on a true first run (no model, nothing installed).

- `rememberModalBottomSheetState(skipPartiallyExpanded = true)`, `containerColor = surfaceContainerLow`, default drag handle, default content insets (the sheet handles the navigation bar itself).
- Body: `Column` + `verticalScroll`, 20 dp side padding, 28 dp bottom padding, 16 dp between sections.
- **Header:** "Model" (`titleLarge`) + "Runs entirely on this device" (`bodySmall`, `onSurfaceVariant`), `TextButton("Done")` on the right.
- **Active model card:** `surfaceContainerHigh`, 20 dp radius, 16 dp padding. 10 dp status dot + status title (`titleSmall`) + detail:
  - `Downloading` → 8 dp rounded progress bar + `"37% · 412.3 MB of 1.9 GB"`; if the total is unknown, an indeterminate bar + "Contacting the server…". A caption notes the sheet can be closed while downloading.
  - `Loading` → indeterminate bar + "Loading into memory. First load can take a few seconds."
  - `Ready` → `"gemma-4-E2B-it-gpu.litertlm · GPU backend"`.
  - `Failed` → the message in `error`.
  - `Missing` → the two-sentence explanation.
- **Actions:** primary `Button` (full width) `"Download Gemma 4 E2B · 1.9 GB"` — or `"Re-download Gemma 4 E2B"` when one is already `Ready`; secondary `OutlinedButton` `"Load a .litertlm file…"`. Both full width: in a sheet, full-width buttons are the thumb-friendly choice.
- **Download confirmation (`AlertDialog`):** "Download the model?" / "Gemma 4 E2B is about 1.9 GB. Use Wi-Fi if you can. The file is stored on this phone; nothing is uploaded anywhere." / **Download** · **Not now**.
- **Installed models:** section "On this device", then one `ListItem` per file: filename (1 line, ellipsised), supporting line `"1.9 GB · in use"` or `"1.9 GB"`, a `tertiary` check as the leading content when active, a `Delete` `IconButton` as trailing content (disabled for the active file). Tapping a row loads it; the whole row is the target (`clip(16.dp).clickable`).
- **Delete confirmation:** "Delete this model?" / "`<file>` (1.9 GB) will be removed from this device. You can download it again later." / **Delete** · **Keep**.
- **Advanced** (collapsed `TextButton`): the `adb push` recipe in monospace on `surfaceContainer`, plus the ".task files will not load" warning. This is developer-facing detail; it should be one tap away, not on the surface.

Not in scope (would need the VM): cancel an in-flight download. Flagged in §14.

### 7.7 Notices and errors

- `state.notice` becomes a `Snackbar` (`SnackbarHostState`, `withDismissAction = true`) triggered by `LaunchedEffect(state.notice)`, then **`vm.clearNotice()`** so it fires exactly once. This is the missing second half of the existing API — `clearNotice()` is already implemented and never called.
- Long-running states (importing, downloading, loading) are *not* notices; they are states, and they belong in the status pill / sheet / empty state where they persist. Notices are for transient confirmations ("Transcript copied") and one-shot failures.
- A generation failure stays inline in its bubble (it is part of the transcript and should survive a reload).

### 7.8 Dialogs and confirmations

| Trigger | Dialog | Confirm · Dismiss |
|---|---|---|
| New chat, transcript non-empty | "Start a new chat?" / "The current conversation will be cleared from this screen." | New chat · Cancel |
| Download model (any) | see §7.6 | Download · Not now |
| Delete installed model | see §7.6 | Delete · Keep |

Nothing else confirms. Sending, typing, clearing the input field, loading an installed model, and toggling Glance mode are all cheap and reversible, and a confirmation would just add friction to a chat app.

---

## 8. State matrix

The whole UI is a pure function of `ChatUiState`. This table is the contract: every row must be reachable, and every row must be visually distinct from its neighbours.

| # | `model` | `messages` | `generating` | App bar | Body | Composer |
|---|---|---|---|---|---|---|
| 1 | `Missing` | empty | false | `No model` pill (error dot) | Empty state, download CTA | Disabled field + "Load a model to start chatting" + `Model` button |
| 2 | `Downloading` | empty | false | `37%` pill + 3 dp bar under the bar | Empty state, 8 dp progress + MB readout | Disabled field + hint row |
| 3 | `Loading` | empty | false | `Loading` pill | Empty state, spinner + caption | Disabled field + hint row |
| 4 | `Failed` | empty | false | `Error` pill | Empty state, message + Try again | Disabled field + hint row |
| 5 | `Ready` | empty | false | `GPU`/`CPU` pill (tertiary dot) | Empty state + 4 prompt chips | Enabled field, disabled Send until text |
| 6 | `Ready` | history restored | false | `GPU` pill | Transcript + date separators | Enabled, Send enabled with text |
| 7 | `Ready` | any | **true** | `GPU` pill | Transcript; last bubble streaming (dots → caret); jump-to-bottom FAB if scrolled up | Enabled field (type-ahead), 2 dp activity bar, **Stop** button |
| 8 | `Downloading` | history | false | `37%` pill + bar | Transcript (the model can be replaced mid-conversation without touching the transcript) | Disabled field + hint row |
| 9 | any | generation failed | false | `GPU` pill | Error bubble on `errorContainer` | Normal |

Two invariants worth enforcing in review:

- **The transcript is never cleared by a model change.** Re-downloading or loading a different model must not touch `messages` — the current VM already honours this; keep it that way.
- **`generating` never disables typing.** Only `send` and `stop` are affected.

---

## 9. Accessibility specification

| Requirement | Implementation |
|---|---|
| Every icon has a label | `contentDescription` on all six icons (§4.5); decorative leading checks get `null` + the parent row carries the text |
| The drawn stop glyph is announced | `Modifier.semantics { contentDescription = "Stop generating" }` on the `FilledTonalIconButton` (a drawn `Box` has no accessible name of its own) |
| Status is announced as a sentence | The pill's text is already read ("GPU"); adding a `contentDescription` on the pill's parent would duplicate it, so the pill relies on its child text plus `Role.Button` |
| Answers are announced once | `liveRegion = LiveRegionMode.Polite` on finished assistant bubbles, `None` while streaming |
| Progress is announced | `LinearProgressIndicator` carries the platform progress semantics; the percentage is also plain text next to it (`"37% · 412.3 MB of 1.9 GB"`) |
| Touch targets | All interactive elements ≥ 48 dp; the send/stop button, the input field and the composer row use 56 dp |
| Font scaling | No fixed-height containers around text; the empty state scrolls; `maxLines` only where truncation is intended (pill, filename) |
| Contrast | Body text on `surfaceContainerHighest`/`primaryContainer` uses the matching `on*` role in both light and dark; `onSurfaceVariant` is only used for secondary copy (11–12 sp at 4.5:1+) |
| Colour is never the only signal | Failure has a ⚠️ marker + error container; readiness has a dot **and** a word; sender is encoded by alignment **and** colour |
| RTL | `supportsRtl="true"`; `Icons.AutoMirrored.Filled.Send` mirrors correctly; bubbles use `Alignment.End/Start`, never `Left/Right` |
| Keyboard | `ImeAction.Send` makes the action key work; `Clear` has a label; focus order is field → send → list |

Deliberately unchanged: no content descriptions on the transcript as a whole, and no custom `semantics` merge on bubbles — `SelectionContainer` already exposes text-selection semantics and a merge would break partial selection.

---

## 10. Performance notes for streaming UI

The UI must not add work proportional to the conversation on every token.

1. **`key = { _, m -> m.id }` on `itemsIndexed`** so a token update reuses the item; `ChatMessage` is a data class → stable, so unchanged bubbles skip recomposition.
2. **Skip `animateItem()` for the streaming item.** Its height grows on every token; animating placement there queues a new animation per token.
3. **`scrollToItem`, not `animateScrollToItem`, while streaming** (see §7.5).
4. **Isolate the caret.** `StreamingText` reads the infinite transition itself, so the alpha animation recomposes only that `Text`, not `MessageBubble` or the list.
5. **Don't `remember` the whole transcript.** Only the code-block splitter is memoised (`remember(text)`), which is why it is a function of the text alone.
6. **`derivedStateOf`/`snapshotFlow` for scroll state** instead of reading `listState` in composition, so scroll events do not recompose the list.
7. **One known VM-side cost, out of this spec's scope but worth fixing:** `send()` maps over `s.messages` on every token (`messages.map { if (it.id == botId) ... }`). It is O(n) per token and allocates a new list each time. When milestoning: mutate a copy only when the *streaming* message is last, or hold the streaming text in its own `StateFlow<String>` and merge at the UI. Not a UI-layer fix.

---

## 11. Implementation plan

### 11.1 File map

| File | Action | Why |
|---|---|---|
| `ui/theme/Theme.kt` | **new** | `LocalChatTheme`, `Dimens`, `LocalGlanceMode`, fallback palette, typography patch, `messageTextStyle()` |
| `ui/Format.kt` | **new** | `mb`, `pct`, `timeLabel`, `dayLabel`, `isSameDay` (the old `private` copies in `ChatScreen.kt` go away) |
| `ui/components/MessageBubble.kt` | **new** | Bubble, grouping shape, code blocks, typing dots, streaming caret, per-run timestamp |
| `ui/components/Composer.kt` | **new** | The input row and the send/stop button; owns the composer's bottom inset |
| `ui/components/ModelSheet.kt` | **new** | `ModalBottomSheet`, confirmations, installed-model list |
| `ChatScreen.kt` | **rewrite** | Shell, insets, top bar, status pill, empty state, list, scroll policy, dialogs, snackbar |
| `MainActivity.kt` | **patch** | `enableEdgeToEdge()` (present), `LocalChatTheme` (renamed), `Surface(fillMaxSize)` |
| `ChatViewModel.kt` | **patch** | `deleteModel(File)`; also call the reset in `newChat()` (§12.2) |
| `LlmEngine.kt` | **patch** | `resetConversation()` — the F17 correctness fix (§12.2) |
| `res/values/themes.xml` | **patch** | Transparent system bars, `windowLightStatusBar`, `windowBackground` → `@color/app_window_background` |
| `res/values-night/themes.xml` | **new** | Dark parent + dark window background (kills the launch flash) |
| `res/values/colors.xml` / `values-night/colors.xml` | **patch/new** | `app_window_background` |
| `AndroidManifest.xml` | **unchanged** | `adjustResize` stays (§6.1) |
| `app/build.gradle.kts` | **unchanged** | No new dependencies |

### 11.2 Suggested order of work

Each step is independently shippable and independently reviewable.

1. **Inset fix** (§6.1) — `contentWindowInsets`, composer inset ownership. ~15 lines. Fixes the complaint.
2. **Composer rebuild** (§7.3) — `ui/components/Composer.kt`. The largest single ergonomics win.
3. **`ui/theme/Theme.kt` + `values-night`** (§4, §12.3) — tokens first, then everything else can use them.
4. **Model sheet** (§7.6) — delete the inline panel.
5. **Bubbles** (§7.4) — `MessageBubble.kt`, then swap the list item in `ChatScreen`.
6. **Scroll policy + jump-to-bottom** (§7.5).
7. **Empty state + status pill + snackbar** (§7.2, §7.1, §7.7).
8. **Accessibility pass + the two VM/engine fixes** (§9, §12.2).

### 11.3 How to apply the code in §12

The files in §12 are complete and self-consistent: they replace `ChatScreen.kt` and `MainActivity.kt`, and add six new files. Package layout:

```
app/src/main/java/fyi/amago/localchat/
├── ChatScreen.kt              (rewritten)
├── ChatViewModel.kt           (+ deleteModel, newChat reset)
├── LlmEngine.kt               (+ resetConversation)
├── ModelRepository.kt         (unchanged)
├── ui/
│   ├── Format.kt
│   ├── theme/Theme.kt
│   └── components/
│       ├── Composer.kt
│       ├── MessageBubble.kt
│       └── ModelSheet.kt
```

The only *behavioural* dependencies on new VM surface are `vm.deleteModel(file)` (§12.2) and `engine.resetConversation()` (§12.2). Everything else in the UI compiles against the existing `ChatViewModel` API (`state`, `send`, `stopGenerating`, `newChat`, `downloadDefaultModel`, `importModel`, `loadModel`, `clearNotice`).

---

## 12. Ready-to-paste code

Everything below is written against **Material3 1.3.1 / Compose 1.7.6** (BOM `2024.12.01`) and uses **no dependency that is not already in `app/build.gradle.kts`**.

### 12.1 Delta against the tree as it stands

| Area | Currently in the tree | This spec | Action |
|---|---|---|---|
| Edge-to-edge | `enableEdgeToEdge()` in `MainActivity` | same | keep |
| Composer insets | `Column(Modifier.fillMaxSize().padding(padding).imePadding())` | `contentWindowInsets = WindowInsets(0,0,0,0)` + composer pads `safeDrawing.only(Bottom)` | **change** — removes the double-counted nav-bar inset |
| Empty state | one centred sentence | badge + headline + per-state block + prompt chips | replace |
| Model manager | inline `Card` in the transcript column | `ModalBottomSheet` | replace |
| Bubbles | 320 dp cap, `surfaceVariant`, `"…"` placeholder, per-bubble timestamps | proportional cap, `surfaceContainerHighest`, dots + caret, one timestamp per run, code blocks, grouping | replace |
| Scroll | `animateScrollToItem` on every token | follow-at-tail policy + `scrollToItem` + FAB | replace |
| Notices | inline `Text`, `clearNotice()` never called | `Snackbar` + `clearNotice()` | replace |
| Theme | `AppTheme` in `MainActivity`, stock typography, Light-only `themes.xml` | `LocalChatTheme` + `Dimens` + `LocalGlanceMode` + `values-night` | replace |
| Persistence | `ChatStore` + `ChatMessage.timeMs` | unchanged | keep — the spec consumes `timeMs` for the per-run stamp and the date chip |
| App bar | `TextButton("Model")`, `TextButton("New")` | status pill + `⋮` overflow | replace |
| `newChat()` | clears the list, keeps the model's context | + `engine.resetConversation()` | **fix (F17)** |

### 12.2 Required ViewModel / engine additions (two small patches)

These are the only non-UI changes the design depends on.

**`LlmEngine.kt`** — F17. `newChat()` clears the screen but the `Conversation` object keeps the model's context, so the next prompt is answered with the previous conversation still loaded. Extract the config, then add an atomic reset:

```kotlin
// 1) replace the inline ConversationConfig(...) inside load() with a call to this:
private fun newConversationConfig() = ConversationConfig(
    systemInstruction = Contents.of(SYSTEM_PROMPT),
    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 1.0, seed = 0),
)

/**
 * Drops the model's context. The fresh conversation is created *before* the old one is
 * closed, so a send() that races this call never sees conversation == null.
 */
suspend fun resetConversation(): Boolean = withContext(Dispatchers.IO) {
    val e = engine ?: return@withContext false
    val fresh = runCatching { e.createConversation(newConversationConfig()) }.getOrNull()
        ?: return@withContext false
    runCatching { conversation?.close() }
    conversation = fresh
    true
}
```

**`ChatViewModel.kt`** — wire the reset into `newChat()`, and add model deletion (the sheet's Delete action):

```kotlin
fun newChat() {
    engine.cancel()
    _state.update { it.copy(messages = emptyList(), notice = null) }
    viewModelScope.launch(Dispatchers.IO) { store.clear() }
    viewModelScope.launch {
        if (!engine.resetConversation()) {
            _state.update { it.copy(notice = "The model could not be reset") }
        }
    }
}

/** Removes a model file. If it was the active one, the app drops back to "no model". */
fun deleteModel(file: File) {
    viewModelScope.launch {
        if ((_state.value.model as? ModelState.Ready)?.fileName == file.name) {
            engine.cancel()
            engine.close()
            _state.update { it.copy(model = ModelState.Missing) }
        }
        val deleted = withContext(Dispatchers.IO) { runCatching { file.delete() }.getOrDefault(false) }
        refreshModels()
        if (!deleted) _state.update { it.copy(notice = "Could not delete ${file.name}") }
    }
}
```

`ModelRepository` needs no change (`file.delete()` is enough); `refreshModels()` already re-lists the folder.

### 12.3 `ui/theme/Theme.kt` (new)

```kotlin
package fyi.amago.localchat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reading preference for one-handed / in-car use: larger type, roomier bubbles.
 * Provided by ChatScreen so the switch is local to the chat UI.
 */
val LocalGlanceMode = staticCompositionLocalOf { false }

/** Single source of truth for spacing. Every measurement in the spec comes from here. */
object Dimens {
    val screenGutter = 16.dp
    val bubbleGap = 3.dp          // between consecutive messages from the same sender
    val groupGap = 14.dp          // between two different senders
    val bubbleMaxWidth = 560.dp   // hard cap on tablets / landscape
    const val bubbleWidthFraction = 0.86f
    val bubblePadding = 14.dp
    val touchTarget = 56.dp       // >= 48dp, sized for a thumb in a car mount
    val cornerBubble = 20.dp
    val cornerBubbleTight = 6.dp  // the "tail" corner
    val cornerSheet = 28.dp
}

// --- Fallback brand palette: only used on API < 31, or if dynamic colour is switched off.
// Material 3 tonal palette derived from a blue (#0B57D0) source hue.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF0F6B4F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA6F2CF),
    onTertiaryContainer = Color(0xFF002116),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F5FA),
    surfaceContainer = Color(0xFFF1EFF5),
    surfaceContainerHigh = Color(0xFFEBE9EF),
    surfaceContainerHighest = Color(0xFFE6E3EA),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF1A4BA0),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFF8BD6B6),
    onTertiary = Color(0xFF003826),
    tertiaryContainer = Color(0xFF00523A),
    onTertiaryContainer = Color(0xFFA6F2CF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Stock M3 shape, with message text promoted to bodyLarge: the answer *is* the product,
 * so it gets the readable size. Everything else stays on the stock scale.
 */
val LocalChatTypography: Typography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun LocalChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colors, typography = LocalChatTypography, content = content)
}

/** Message text style. Honours the system font scale, and Glance mode on top of that. */
@Composable
fun messageTextStyle(): TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    return if (LocalGlanceMode.current) {
        base.copy(fontSize = 20.sp, lineHeight = 30.sp)
    } else {
        base
    }
}
```

### 12.4 `ui/Format.kt` (new)

```kotlin
package fyi.amago.localchat.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared formatting so the sheet, the pill and the empty state never disagree. */
internal fun mb(bytes: Long): String =
    if (bytes <= 0) "0 MB" else String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

internal fun pct(read: Long, total: Long): String =
    if (total <= 0) "" else "${read * 100 / total}%"

// java.time is safe here: minSdk is 28 (Android 8 supports it without desugaring).

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("d MMM")

internal fun timeLabel(ms: Long): String =
    if (ms <= 0L) "" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clockFormat)

internal fun dayLabel(ms: Long): String {
    if (ms <= 0L) return ""
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(dayFormat)
    }
}

internal fun isSameDay(a: Long, b: Long): Boolean {
    if (a <= 0L || b <= 0L) return false
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
```

### 12.5 `ui/components/MessageBubble.kt` (new)

```kotlin
package fyi.amago.localchat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.amago.localchat.ChatMessage
import fyi.amago.localchat.ui.timeLabel
import fyi.amago.localchat.ui.theme.Dimens
import fyi.amago.localchat.ui.theme.LocalGlanceMode
import fyi.amago.localchat.ui.theme.messageTextStyle

/**
 * One chat bubble.
 *
 * Alignment, colour and corner shape all encode the same thing (who is speaking), so the
 * bubble is readable at a glance without reading the text. Long messages are selectable:
 * long-press to copy part of an answer, like every other chat app.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    isFirstInRun: Boolean,
    isLastInRun: Boolean,
    modifier: Modifier = Modifier,
) {
    val isUser = message.fromUser
    val failed = !isUser && message.text.startsWith(ERROR_PREFIX)

    val container = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val onContainer = when {
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        failed -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = (maxWidth * Dimens.bubbleWidthFraction).coerceAtMost(Dimens.bubbleMaxWidth)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = container,
                contentColor = onContainer,
                shape = bubbleShape(isUser, isFirstInRun, isLastInRun),
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .semantics {
                        // A finished answer may be announced; a streaming one changes too fast.
                        liveRegion = if (message.streaming) LiveRegionMode.None else LiveRegionMode.Polite
                    },
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = Dimens.bubblePadding,
                        vertical = 10.dp,
                    ),
                ) {
                    when {
                        message.streaming && message.text.isEmpty() ->
                            TypingIndicator(color = onContainer)

                        message.streaming ->
                            StreamingText(text = message.text, color = onContainer)

                        else -> SelectionContainer {
                            MessageBody(text = message.text, style = messageTextStyle())
                        }
                    }
                }
            }

            // One timestamp per run, not one per bubble: the gap between two messages from
            // the same speaker is usually seconds, and repeating it is noise.
            if (isLastInRun && !message.streaming && message.timeMs > 0L) {
                Text(
                    text = timeLabel(message.timeMs),
                    modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp),
                    style = if (LocalGlanceMode.current) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val ERROR_PREFIX = "\u26A0\uFE0F"

/**
 * Prose and fenced code, without pulling in a markdown library.
 * A ``` fence becomes a monospaced, horizontally scrollable block.
 */
@Composable
private fun MessageBody(text: String, style: TextStyle) {
    val blocks = remember(text) { splitBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Prose -> Text(text = block.text, style = style)
                is Block.Code -> CodeBlock(code = block.code)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = code.trimEnd('\n'),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** The answer so far, with a block caret that blinks at 2 Hz. */
@Composable
private fun StreamingText(text: String, color: Color) {
    val style = messageTextStyle()
    val transition = rememberInfiniteTransition(label = "caret")
    val caretAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caretAlpha",
    )
    val rendered = buildAnnotatedString {
        append(text)
        withStyle(SpanStyle(color = color.copy(alpha = caretAlpha))) { append("\u258C") }
    }
    Text(text = rendered, color = color, style = style)
}

/** Shown between "send" and the first token: three dots breathing in sequence. */
@Composable
private fun TypingIndicator(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color = color.copy(alpha = alpha), shape = CircleShape),
            )
        }
    }
}

private fun bubbleShape(isUser: Boolean, isFirstInRun: Boolean, isLastInRun: Boolean): Shape {
    val round = Dimens.cornerBubble
    val tight = Dimens.cornerBubbleTight
    return if (isUser) {
        RoundedCornerShape(
            topStart = round,
            topEnd = if (isFirstInRun) round else tight,
            bottomStart = round,
            bottomEnd = if (isLastInRun) tight else round,
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInRun) round else tight,
            topEnd = round,
            bottomStart = if (isLastInRun) tight else round,
            bottomEnd = round,
        )
    }
}

private sealed interface Block {
    data class Prose(val text: String) : Block
    data class Code(val code: String) : Block
}

private fun splitBlocks(raw: String): List<Block> {
    if (!raw.contains("```")) return listOf(Block.Prose(raw))
    val blocks = mutableListOf<Block>()
    raw.split("```").forEachIndexed { index, part ->
        if (index % 2 == 0) {
            if (part.isNotBlank()) blocks.add(Block.Prose(part.trim('\n')))
        } else {
            // Drop the language tag on the opening fence line.
            val body = part.substringAfter('\n', "")
            blocks.add(Block.Code(body.ifEmpty { part }))
        }
    }
    return blocks.ifEmpty { listOf(Block.Prose(raw)) }
}
```

### 12.6 `ui/components/Composer.kt` (new)

```kotlin
package fyi.amago.localchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.amago.localchat.ui.theme.Dimens
import fyi.amago.localchat.ui.theme.LocalGlanceMode
import fyi.amago.localchat.ui.theme.messageTextStyle

/**
 * The input row. This is the piece that has to survive the keyboard.
 *
 * Bottom insets: `safeDrawing` is the union of the navigation bar and the IME, so one
 * `windowInsetsPadding` call keeps the field visible above the keyboard and above the
 * gesture bar, without ever double-counting the two.
 *
 * The model panel stays reachable from here when nothing is loaded, so a first-run user is
 * never dropped into a dead text field.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    generating: Boolean,
    modelReady: Boolean,
    onOpenModelManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val glance = LocalGlanceMode.current
    val canSend = modelReady && !generating && value.isNotBlank()

    val textStyle = if (glance) {
        messageTextStyle().copy(fontSize = 18.sp, lineHeight = 26.sp)
    } else {
        messageTextStyle()
    }

    val clearButton: @Composable (() -> Unit)? = if (value.isNotEmpty()) {
        {
            IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Filled.Clear, contentDescription = "Clear the message")
            }
        }
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        if (generating) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (!modelReady) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Load a model to start chatting",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenModelManager) { Text("Model") }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = Dimens.touchTarget),
                enabled = modelReady,           // stays enabled while generating: type ahead
                textStyle = textStyle,
                placeholder = {
                    Text(
                        text = if (modelReady) "Message LocalChat\u2026" else "Waiting for a model\u2026",
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = clearButton,
                shape = RoundedCornerShape(24.dp),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSend) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSend()
                        }
                    },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )

            // One button, two jobs: send while idle, stop while generating.
            if (generating) {
                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStop()
                    },
                    modifier = Modifier
                        .size(Dimens.touchTarget)
                        .semantics { contentDescription = "Stop generating" },
                    shape = CircleShape,
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = RoundedCornerShape(3.dp),
                            ),
                    )
                }
            } else {
                FilledIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend()
                    },
                    enabled = canSend,
                    modifier = Modifier.size(Dimens.touchTarget),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
```

### 12.7 `ui/components/ModelSheet.kt` (new)

```kotlin
package fyi.amago.localchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fyi.amago.localchat.ChatUiState
import fyi.amago.localchat.ModelRepository
import fyi.amago.localchat.ModelState
import fyi.amago.localchat.ui.mb
import fyi.amago.localchat.ui.pct
import java.io.File

/**
 * Model manager. Lives in a modal bottom sheet rather than inline in the transcript, so the
 * chat is never pushed off screen by a 1.9 GB download.
 *
 * Every destructive or expensive action is confirmed: the download is measured in gigabytes
 * and the model list is the only place a user can lose a file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onLoadFile: () -> Unit,
    onLoad: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDownload by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Model", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Runs entirely on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            ActiveModelCard(state.model)

            when (state.model) {
                is ModelState.Downloading ->
                    Text(
                        text = "You can close this sheet \u2014 the download keeps running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { confirmDownload = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.model is ModelState.Ready) "Re-download Gemma 4 E2B"
                            else "Download Gemma 4 E2B \u00B7 ${ModelRepository.DEFAULT_MODEL_SIZE_HINT}",
                        )
                    }
                    OutlinedButton(onClick = onLoadFile, modifier = Modifier.fillMaxWidth()) {
                        Text("Load a .litertlm file\u2026")
                    }
                }
            }

            if (state.models.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("On this device", style = MaterialTheme.typography.titleSmall)
                    state.models.forEach { file ->
                        val active = (state.model as? ModelState.Ready)?.fileName == file.name
                        ListItem(
                            headlineContent = {
                                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(if (active) "${mb(file.length())} \u00B7 in use" else mb(file.length()))
                            },
                            leadingContent = {
                                if (active) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { pendingDelete = file }, enabled = !active) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete ${file.name}",
                                    )
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = !active, onClick = { onLoad(file) }),
                        )
                    }
                }
            }

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced")
            }
            if (showAdvanced) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Side-load a model", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "adb push your-model.litertlm " +
                                "/sdcard/Android/data/fyi.amago.localchat/files/models/",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = "Only .litertlm files work. Google AI Edge Gallery's .task models " +
                                "use a different runtime and will not load.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirmDownload) {
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Download the model?") },
            text = {
                Text(
                    "Gemma 4 E2B is about ${ModelRepository.DEFAULT_MODEL_SIZE_HINT}. " +
                        "Use Wi-Fi if you can. The file is stored on this phone; " +
                        "nothing is uploaded anywhere.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDownload = false; onDownload() }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = false }) { Text("Not now") }
            },
        )
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this model?") },
            text = {
                Text(
                    "${file.name} (${mb(file.length())}) will be removed from this device. " +
                        "You can download it again later.",
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(file) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun ActiveModelCard(model: ModelState) {
    val tint = when (model) {
        ModelState.Missing -> MaterialTheme.colorScheme.error
        is ModelState.Downloading -> MaterialTheme.colorScheme.primary
        ModelState.Loading -> MaterialTheme.colorScheme.primary
        is ModelState.Ready -> MaterialTheme.colorScheme.tertiary
        is ModelState.Failed -> MaterialTheme.colorScheme.error
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(color = tint, shape = CircleShape))
                Spacer(Modifier.size(10.dp))
                Text(
                    text = statusTitle(model),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }

            when (model) {
                is ModelState.Downloading -> {
                    if (model.total > 0) {
                        LinearProgressIndicator(
                            progress = { model.bytes.toFloat() / model.total.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            text = "${pct(model.bytes, model.total)} \u00B7 ${mb(model.bytes)} of ${mb(model.total)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            text = "Contacting the server\u2026",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ModelState.Loading -> {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Text(
                        text = "Loading into memory. First load can take a few seconds.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ModelState.Ready -> Text(
                    text = "${model.fileName} \u00B7 ${model.backend} backend",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is ModelState.Failed -> Text(
                    text = model.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                ModelState.Missing -> Text(
                    text = "No model on this device yet. Download Gemma 4 E2B, or load a " +
                        ".litertlm file you already have.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusTitle(model: ModelState): String = when (model) {
    ModelState.Missing -> "No model loaded"
    is ModelState.Downloading -> "Downloading the model"
    ModelState.Loading -> "Loading the model"
    is ModelState.Ready -> "Ready"
    is ModelState.Failed -> "Something went wrong"
}
```

### 12.8 `ChatScreen.kt` (rewrite)

```kotlin
package fyi.amago.localchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fyi.amago.localchat.ui.components.Composer
import fyi.amago.localchat.ui.components.MessageBubble
import fyi.amago.localchat.ui.components.ModelSheet
import fyi.amago.localchat.ui.dayLabel
import fyi.amago.localchat.ui.isSameDay
import fyi.amago.localchat.ui.mb
import fyi.amago.localchat.ui.pct
import fyi.amago.localchat.ui.theme.Dimens
import fyi.amago.localchat.ui.theme.LocalGlanceMode
import kotlinx.coroutines.launch

/**
 * The whole app.
 *
 * Insets are the contract that matters here, so the design is stated once, up front:
 *
 *  - `enableEdgeToEdge()` in MainActivity + `contentWindowInsets = WindowInsets(0, 0, 0, 0)`
 *    means Scaffold reports only the app bar in `innerPadding`; nothing else is consumed.
 *  - the list and the composer draw edge to edge horizontally (status bar, gesture bar, IME).
 *  - the composer pads itself by `WindowInsets.safeDrawing` bottom = max(navigation bar, IME),
 *    which is what keeps the text field above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var input by rememberSaveable { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showOverflow by rememberSaveable { mutableStateOf(false) }
    var glance by rememberSaveable { mutableStateOf(false) }
    var confirmNewChat by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(context, uri)
    }

    val listState = rememberLazyListState()
    val messages = state.messages
    var followTail by remember { mutableStateOf(true) }

    // First run: no model anywhere -> open the manager so the user is never stuck.
    LaunchedEffect(Unit) {
        if (state.model is ModelState.Missing && state.models.isEmpty()) showModels = true
    }

    // One-shot notices become snackbars instead of shoving the transcript around.
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message = notice, withDismissAction = true)
        vm.clearNotice()
    }

    // The user owns the scroll position: auto-follow only while they are at the tail.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) followTail = !canScrollForward
            }
    }

    // Sending is an explicit action: snap to the new message.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            followTail = true
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // Streaming tokens: cheap scroll, and only while the user is still at the tail.
    LaunchedEffect(messages.lastOrNull()?.text) {
        if (followTail && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    CompositionLocalProvider(LocalGlanceMode provides glance) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("LocalChat", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        ModelStatusPill(model = state.model) { showModels = true }

                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("New chat") },
                                    onClick = {
                                        showOverflow = false
                                        if (messages.isEmpty()) vm.newChat() else confirmNewChat = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Glance mode") },
                                    trailingIcon = {
                                        if (glance) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        showOverflow = false
                                        glance = !glance
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy transcript") },
                                    enabled = messages.isNotEmpty(),
                                    onClick = {
                                        showOverflow = false
                                        clipboard.setText(
                                            AnnotatedString(
                                                messages.joinToString("\n\n") { m ->
                                                    val who = if (m.fromUser) "You" else "LocalChat"
                                                    "$who: ${m.text}"
                                                },
                                            ),
                                        )
                                        scope.launch { snackbarHost.showSnackbar("Transcript copied") }
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Models & storage") },
                                    onClick = {
                                        showOverflow = false
                                        showModels = true
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHost) },
            // All insets are handled explicitly below; Scaffold must not consume any.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                DownloadBar(state.model)

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        EmptyState(
                            state = state,
                            onDownload = { vm.downloadDefaultModel() },
                            onPickFile = { picker.launch(arrayOf("*/*")) },
                            onOpenManager = { showModels = true },
                            onSuggestion = { input = it },
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = Dimens.screenGutter,
                                end = Dimens.screenGutter,
                                top = 8.dp,
                                bottom = 12.dp,
                            ),
                        ) {
                            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                                val previous = messages.getOrNull(index - 1)
                                val next = messages.getOrNull(index + 1)
                                val firstInRun = previous == null || previous.fromUser != message.fromUser
                                val lastInRun = next == null || next.fromUser != message.fromUser
                                val showDay = message.timeMs > 0L &&
                                    (previous == null || !isSameDay(previous.timeMs, message.timeMs))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            top = when {
                                                index == 0 -> 0.dp
                                                showDay || firstInRun -> Dimens.groupGap
                                                else -> Dimens.bubbleGap
                                            },
                                        )
                                        // Placement animation is skipped while streaming: the
                                        // bubble changes size on every token.
                                        .then(if (message.streaming) Modifier else Modifier.animateItem()),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        if (showDay) DaySeparator(dayLabel(message.timeMs))
                                        MessageBubble(
                                            message = message,
                                            isFirstInRun = firstInRun,
                                            isLastInRun = lastInRun,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = !followTail && messages.isNotEmpty(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                followTail = true
                                scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Jump to the latest message",
                            )
                        }
                    }
                }

                Composer(
                    value = input,
                    onValueChange = { input = it },
                    onSend = {
                        val text = input
                        input = ""
                        vm.send(text)
                    },
                    onStop = { vm.stopGenerating() },
                    generating = state.generating,
                    modelReady = state.model is ModelState.Ready,
                    onOpenModelManager = { showModels = true },
                )
            }
        }

        if (showModels) {
            ModelSheet(
                state = state,
                onDismiss = { showModels = false },
                onDownload = { vm.downloadDefaultModel() },
                onLoadFile = { picker.launch(arrayOf("*/*")) },
                onLoad = { vm.loadModel(it) },
                onDelete = { vm.deleteModel(it) },
            )
        }

        if (confirmNewChat) {
            AlertDialog(
                onDismissRequest = { confirmNewChat = false },
                title = { Text("Start a new chat?") },
                text = { Text("The current conversation will be cleared from this screen.") },
                confirmButton = {
                    TextButton(onClick = { confirmNewChat = false; vm.newChat() }) { Text("New chat") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmNewChat = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/** Centered date chip when the conversation crosses midnight. */
@Composable
private fun DaySeparator(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(percent = 50),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** A 3dp line under the app bar: the download stays visible while the sheet is closed. */
@Composable
private fun DownloadBar(model: ModelState) {
    if (model is ModelState.Downloading) {
        if (model.total > 0) {
            LinearProgressIndicator(
                progress = { model.bytes.toFloat() / model.total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

/** App-bar chip: one colour + one word tells the whole model story. Tapping opens the sheet. */
@Composable
private fun ModelStatusPill(model: ModelState, onClick: () -> Unit) {
    val dot = when (model) {
        ModelState.Missing -> MaterialTheme.colorScheme.error
        is ModelState.Downloading -> MaterialTheme.colorScheme.primary
        ModelState.Loading -> MaterialTheme.colorScheme.primary
        is ModelState.Ready -> MaterialTheme.colorScheme.tertiary
        is ModelState.Failed -> MaterialTheme.colorScheme.error
    }
    val label = when (model) {
        ModelState.Missing -> "No model"
        is ModelState.Downloading -> if (model.total > 0) pct(model.bytes, model.total) else "Downloading"
        ModelState.Loading -> "Loading"
        is ModelState.Ready -> model.backend
        is ModelState.Failed -> "Error"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(percent = 50),
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).background(color = dot, shape = CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    state: ChatUiState,
    onDownload: () -> Unit,
    onPickFile: () -> Unit,
    onOpenManager: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Text(
                text = "Chat that never leaves your phone",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Gemma 4 E2B runs on this device. No account, no API key, no uploads.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            when (val model = state.model) {
                is ModelState.Ready -> {
                    Text(
                        text = "Try one of these",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SUGGESTIONS.forEach { suggestion ->
                            AssistChip(
                                onClick = { onSuggestion(suggestion) },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }

                is ModelState.Downloading -> {
                    if (model.total > 0) {
                        LinearProgressIndicator(
                            progress = { model.bytes.toFloat() / model.total.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Text(
                            text = "Downloading the model \u2014 ${mb(model.bytes)} of ${mb(model.total)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Downloading the model\u2026", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                ModelState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Loading the model into memory\u2026")
                }

                is ModelState.Failed -> {
                    Text(
                        text = "The model could not be prepared",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = model.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onDownload) { Text("Try again") }
                    TextButton(onClick = onPickFile) { Text("Load a .litertlm file") }
                }

                ModelState.Missing -> {
                    Button(onClick = onDownload) {
                        Text("Download Gemma 4 E2B \u00B7 ${ModelRepository.DEFAULT_MODEL_SIZE_HINT}")
                    }
                    OutlinedButton(onClick = onPickFile) { Text("Load a .litertlm file") }
                    if (state.models.isNotEmpty()) {
                        TextButton(onClick = onOpenManager) {
                            Text("Choose one of your ${state.models.size} installed models")
                        }
                    }
                    Text(
                        text = "Apache-2.0 \u00B7 stored in Android/data/fyi.amago.localchat/files/models",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private val SUGGESTIONS = listOf(
    "Explain how Wi-Fi calling works, briefly",
    "Turn my shopping list into a meal plan",
    "Draft a short, friendly reschedule email",
    "Write a tiny Kotlin coroutine example",
)
```

### 12.9 `MainActivity.kt` (patch) + resources

```kotlin
package fyi.amago.localchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import fyi.amago.localchat.ui.theme.LocalChatTheme

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status bar, the gesture bar and the keyboard. Without this the
        // window still resizes for the IME on some OEM builds and the composer gets covered.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LocalChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ChatScreen(vm)
                }
            }
        }
    }
}
```

`res/values/colors.xml`:

```xml
<resources>
    <color name="ic_launcher_background">#0F1720</color>
    <!-- Pre-Compose window frame. Matches the light surface so launch has no flash. -->
    <color name="app_window_background">#FFFBFE</color>
</resources>
```

`res/values-night/colors.xml` (new):

```xml
<resources>
    <color name="app_window_background">#111318</color>
</resources>
```

`res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.LocalChat" parent="@android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/app_window_background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowLightNavigationBar">true</item>
    </style>
</resources>
```

`res/values-night/themes.xml` (new — this is what kills the white launch flash in dark mode):

```xml
<resources>
    <style name="Theme.LocalChat" parent="@android:style/Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/app_window_background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
    </style>
</resources>
```

The style name must stay `Theme.LocalChat` — the manifest references it. `windowLightNavigationBar` is API 27+, so it is safe in the unqualified `values/` folder at `minSdk 28`. The activity's `android:windowSoftInputMode="adjustResize"` must remain for IME insets on API 28–29.

---

## 13. Verification status, residual risks, and the ordered change list

### 13.1 How the API surface was verified

There is no JDK or Android SDK on the machine this document was written on, so the code in §12 could **not** be compiled here. Instead, every API it uses was verified against the actual resolved artifacts in the Gradle cache (`compose-bom 2024.12.01`):

| Checked | Result |
|---|---|
| `androidx.compose.material3:material3` version | `1.3.1` |
| `androidx.compose.foundation:foundation` / `foundation-layout` / `ui` | `1.7.6` |
| `androidx.activity:activity` / `activity-compose` | `1.9.3` — `EdgeToEdgeKt` present, `enableEdgeToEdge()` available |
| `material-icons-core` contents | exactly 50 icons per style; **`Send` exists, `Stop` and `ContentCopy` do not**; `Icons.AutoMirrored.Filled.Send` present |
| `ColorScheme` + `lightColorScheme`/`darkColorScheme` | `surfaceContainer`, `…Low`, `…High`, `…Highest`, `…Lowest` all present |
| `WindowInsets` constructors (`(Int,Int,Int,Int)` and `(Dp×4)`), `WindowInsets.Companion.ime`/`safeDrawing`/`systemBars`, `Modifier.imePadding` | present |
| `Scaffold(contentWindowInsets = …)`, `ModalBottomSheet`, `rememberModalBottomSheetState`, `ListItem`, `SnackbarHost`, `HorizontalDivider`, `AssistChip`, `FlowRow`, `LazyItemScope.animateItem` | present |
| `OutlinedTextField` params `textStyle`/`maxLines`/`shape`/`trailingIcon`/`keyboardOptions`/`keyboardActions`/`colors` | present |
| `KeyboardActions.onSend`, `ImeAction`, `HapticFeedbackType.LongPress`, `LiveRegionMode`, `LocalClipboardManager` | present |

**Not covered by that verification** (compile-visible in minutes, worth a `gradle compileDebugKotlin` before committing):

1. Individual `OutlinedTextFieldDefaults.colors(...)` parameter names in 1.3.1. If one is rejected, delete that single line — the layout is unaffected.
2. `rememberModalBottomSheetState(skipPartiallyExpanded = true)` — stable Material3 API, but the parameter name was not machine-verified.
3. `Modifier.animateItem()` resolves only inside a `LazyItemScope`; the call site in `ChatScreen.kt` is inside `itemsIndexed { … }`, which provides it.
4. `@OptIn(ExperimentalMaterial3Api::class)` / `ExperimentalLayoutApi::class` are included on every composable that needs them; an *unnecessary* opt-in is a warning, not an error.

**Runtime-only risks**, in order of likelihood:

1. **`ModalBottomSheet` containing a `verticalScroll` column.** In Material3 1.3 the sheet implements nested scrolling; the inner scrollable consumes first and the sheet takes over at its bounds. If a drag feels wrong, replace the single `verticalScroll` column with a fixed-height `LazyColumn` (`heightIn(max = 520.dp)`).
2. **IME insets on API 28–29.** `enableEdgeToEdge()` + `adjustResize` is the documented combination; verify once on an API 28/29 device or emulator that a keyboard opening moves the composer.
3. **`WindowInsets.safeDrawing` bottom while the IME animates.** Expect a smooth ride (the platform animates the inset); if it looks steppy on an OEM skin, swap `windowInsetsPadding` for `Modifier.imePadding().navigationBarsPadding()` guarded by `WindowInsets.ime.getBottom(density) == 0`.

### 13.2 Highest-impact changes if only some are implemented

Ranked by user-visible value per line of diff. Everything in tier 1 is worth doing before anything in tier 2.

| Rank | Change | Touches | Effort | Why it ranks here |
|---|---|---|---|---|
| **1** | **Fix the inset contract**: `contentWindowInsets = WindowInsets(0,0,0,0)` on the Scaffold + composer pads `safeDrawing.only(Bottom)` | `ChatScreen.kt`, `Composer.kt` | ~15 lines | Directly fixes the reported defect (dead band above the keyboard / not seeing what you type) and makes the whole layout deterministic. Largest value-per-line in the document. |
| **2** | **Rebuild the composer**: 56 dp circular send/stop, `ImeAction.Send`, type-ahead while generating, clear button, hint + model CTA when nothing is loaded | new `Composer.kt` (~200 lines) + 4 lines in `ChatScreen.kt` | ~1 hour | The composer is used on every single interaction. It is also the only place where one-handed reach and driver safety actually matter. |
| **3** | **Model manager as a `ModalBottomSheet`**, with a download confirmation and delete | new `ModelSheet.kt` (~300 lines) + `deleteModel` (~12 lines) + ~10 lines in `ChatScreen.kt` | ~1.5 hours | Reclaims the top of the screen, stops accidental 1.9 GB re-downloads, and gives a first-run user an obvious path. |
| **4** | **Transcript rebuild**: proportional bubble width, `surfaceContainerHighest` for answers, run grouping, typing dots + streaming caret, one timestamp per run, code blocks | new `MessageBubble.kt` (~260 lines) + list item rewrite | ~2 hours | This is what makes the app stop looking like a demo. The 320 dp cap and the `"…"` placeholder are the two most visible giveaways. |
| **5** | **Scroll policy** (follow-at-tail, `scrollToItem` while streaming, jump-to-bottom FAB) | ~25 lines in `ChatScreen.kt` | ~30 min | Removes the "the app fights me while it answers" feeling and a per-token animation. Cheap and additive. |
| **6** | **Theme tokens + `values-night`**: `LocalChatTheme`, typography patch, Glance mode, dark window background | new `Theme.kt` (~150 lines), `MainActivity` patch, 2 resource files | ~45 min | Kills the white launch flash in dark mode, fixes status-bar icon polarity, promotes message text to 16 sp, and gives every later change a token set instead of magic numbers. |
| **7** | **Empty state + status pill + snackbar notices** | ~200 lines inside `ChatScreen.kt`, ~15 lines in the VM call | ~1 hour | First-run guidance and glanceable model state; removes the "notice stuck on screen forever" bug (`clearNotice()` is never called today). |
| **8** | **`newChat()` context reset** (`LlmEngine.resetConversation()`) | ~15 lines | ~20 min | Not cosmetic: today "New chat" clears the screen while the model keeps the old conversation in context. Do it even if nothing else from this document ships. |

Tier 2 (polish, in order): date separators per run → accessibility pass → haptics → per-message copy affordances → read-aloud (TTS) for driving.

### 13.3 On-device QA checklist

Run each of these on the S25 Ultra before calling the redesign done.

**Keyboard (the reported defect)**
- [ ] Portrait, Gboard, gesture navigation: field bottom sits ~8 dp above the keyboard; no dead band.
- [ ] Portrait, 3-button navigation: same, and the gap does **not** grow by the navigation-bar height (this is the regression being fixed).
- [ ] Landscape with the keyboard open: field visible, list scrolled to the last message, app bar intact.
- [ ] Samsung keyboard: IME action key reads "Send" and sends.
- [ ] Type-ahead: start a 200-token answer, type a full sentence, verify the field keeps the text and the model is not interrupted.
- [ ] Cleartext: with the keyboard open and 6 lines of text, the field grows upward and stays above the keyboard.

**Transcript**
- [ ] 5 consecutive user messages: 3 dp gaps, flattened seam corners, one timestamp at the end of the run.
- [ ] Long answer (> 4000 chars): scrolls, does not clip, no visible jank while streaming.
- [ ] `` ``` `` fenced Kotlin block: monospace, rounded, horizontally scrollable, text not wrapped.
- [ ] Generation failure: bubble turns `errorContainer` with the ⚠️ marker and survives a restart.
- [ ] Scroll up while streaming: position holds, FAB appears, tapping it animates to the newest token.
- [ ] Restored history after a cold start: date chip shows "Today"/"Yesterday" correctly, no 1970 timestamps.

**Model + states**
- [ ] Fresh install (clear app data): sheet opens automatically, download is confirmed, progress is visible with the sheet closed (3 dp bar) and open (8 dp bar + MB).
- [ ] Re-download button is available but requires confirmation.
- [ ] Delete the active model: state returns to "No model", empty state offers the CTA, the file is gone from `Android/data/…/models/`.
- [ ] "New chat" confirmation appears only when the transcript is non-empty.
- [ ] After "New chat", the next answer does **not** continue the previous thread (F17 regression test).

**Shell / a11y / i18n**
- [ ] Cold start in dark mode: no white flash.
- [ ] Dynamic colour on and off (Developer options → "Override wallpaper colours" off) with both themes.
- [ ] TalkBack: send, stop, pill, and a finished answer announced once (not per token).
- [ ] Font scale 1.3 and 2.0: empty state scrolls, nothing clipped, send button still reachable.
- [ ] Forced RTL layout: bubbles aligned correctly, send icon mirrored, no overflow.
- [ ] Rotate mid-download and mid-generation: state survives, progress continues.

---

## 14. Known gaps and future work

Deliberately **not** in this specification, with the reason:

| Gap | Why it is deferred | What it needs |
|---|---|---|
| Cancel an in-flight download | `ModelRepository.download` has no cancellation path | an `AtomicBoolean` checked inside the read loop + a VM flag; ~25 lines |
| `ChatMessage.isError` instead of the ⚠️ prefix | Touches the state contract, which this spec deliberately freezes | one field + two call sites |
| Per-message copy / regenerate | Conflicts with `SelectionContainer` long-press; regenerate needs a VM entry point | `regenerate(lastUserPrompt)` + a long-press menu that replaces selection |
| Two-pane landscape layout | Needs `material3-window-size-class` (new dependency) or a hand-rolled `BoxWithConstraints` switch | a decision, not code volume |
| Read-aloud (TTS) while driving | Genuinely attractive for the stated use case, but it is a feature, not a visual spec | `android.speech.tts.TextToSpeech` (platform, no dependency) + a toggle in the overflow |
| Keep the screen on while generating | Cheap and useful in a car mount | `Modifier` + `LocalView` → `FLAG_KEEP_SCREEN_ON`, or `disposableEffect` around `generating` |
| Streaming cost in the VM (`messages.map` per token) | Out of the UI layer's remit | hold streaming text in its own `StateFlow<String>` and merge in the UI |
| String externalisation (localisation) | Every new string in this spec is inline English, matching the current code | move to `strings.xml` when a second locale appears |
| Multiple conversations / history browser | A new navigation surface | a `ChatStore` per-conversation layout + a drawer |

The design leaves room for all of these: the transcript is the only scrollable surface, the composer owns the bottom, and the sheet is a single dismissible layer.
