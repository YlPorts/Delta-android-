# Visual verification, reference and limitations

Reference: original Delta tree at `c1d3d068e019e6493eed45654569db3cc5beb86a`,
Delta 2.0b5 (163); the pre-iOS-26 UIKit grid path. The same source also contains
iOS 26-specific views; those are not yet reproduced.

| Area | Verifiable original | Native adaptation |
| --- | --- | --- |
| Palette | Resources/Assets.xcassets/Colors | Exact byte/fraction/hex sRGB extraction; fail on missing assets |
| Library | GameCollectionViewController.update | Phone item width 90pt, minimum interitem spacing 12pt |
| Grid margins | GridCollectionViewLayout | floor((width - minSpacing)/(itemWidth + minSpacing)); equal outer/inner gaps |
| Covers | GameCollectionViewController.configure | 90x90 maximum, aspect fit, caption1 12pt gray, 8pt label gap |
| Library rows | Main.storyboard | 20pt vertical insets and line spacing |
| Empty/search text | GamesViewController.prepare/prepareSearchController | Original wording and FAQ action |
| Settings | Settings.storyboard | 34pt large title, 17pt rows, 51pt reference row height, inset groups/separators |
| Pause | PauseMenu.storyboard + GridMenuViewController | Original six images/order, 90pt cell, 78pt image, 16pt corner, 20pt gaps/insets |
| Pause identity | PausePresentationControllerContentView.xib | Original Pause 64x80 image, 15pt gap, 28pt game title above the menu |
| Skins | DeltaCore ControllerSkin + GameViewController | Original archives, trait fallbacks, bottom portrait controller and aspect-fit app screen |

Source points map to Android dp; default type sizes map to sp. Font rasterization,
Dynamic Type scaling and San Francisco metrics differ from Android system type.
Blur uses native RenderEffect on API 31+, with dimming on all supported versions.
A native iOS-equivalent blur fallback for API 24–30 is still pending.

The CI `android-runtime` job captures empty/populated library, settings, game,
pause and landscape screens on an actual Android 35 x86_64 AVD. It checks original
palette values, real PDF decoding and layout/input rules. The screenshots and
logcat are preserved even on failure. Inspect these to catch clipping, overlap,
wrong skin scale, opacity, blank screens and rotation defects.

**Source-based measurement tests are not an iPhone screenshot comparison.** A
matched set of verified iPhone captures (same reference version, theme, dimensions,
game artwork, text scale and orientation) is still required. Do not label current
captures “1:1”, use a hand-drawn mock as iPhone evidence, or invent pixel-diff
results. Once references are available, align safe areas and compare those
regions at the same logical resolution, masking only dynamic status/clock/game
content. Keep references, comparison settings and difference images with reports.

Known gaps: native context menus are sheets, Settings does not yet reproduce
every original option, state selection is a slot list rather than the complete
original thumbnail browser, search collapse/interactive paging/blur transitions
and iOS 26 glass effects remain incomplete. Those gaps remain on the roadmap.
