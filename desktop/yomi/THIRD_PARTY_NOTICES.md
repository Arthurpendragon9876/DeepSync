# Third-party notices

## Mangayomi (Apache License 2.0)
https://github.com/kodjodevf/mangayomi

`main/extensions/js/` reimplements the extension API that Mangayomi 0.9.95 gives its JavaScript sources and LNReader plugins, so that
existing extensions run unchanged. Parts were ported from Mangayomi's Dart and embedded JavaScript: the `MProvider`, `Client`, `Document`,
`Element` and `SharedPreferences` classes and their behaviour, the `String` helper methods, the LNReader `require()` shims (including `FilterTypes`,
`NovelStatus` and `isUrlAbsolute`), and the AES (CryptoJS-compatible) and date handling. Licensed under the Apache License, Version 2.0
(http://www.apache.org/licenses/LICENSE-2.0). Changes: rewritten in Node.js; behaviour was kept except where noted in code comments.

## LNReader plugin API (MIT)
https://github.com/LNReader/lnreader-plugins — the plugin interface and `@libs/*` module contracts.

## npm dependencies
cheerio, css-select, domhandler, domutils, htmlparser2 (MIT / BSD-2-Clause), dayjs (MIT), xpath (MIT), @xmldom/xmldom (MIT).

## Google Sans Flex (font)

`renderer/assets/fonts/GoogleSansFlex-variable.ttf`: the variable font the DeepSync Android app ships, used as the fallback when Segoe UI Variable is not available. Licensed under the SIL Open Font License 1.1 (`GoogleSansFlex-OFL.txt`).
Yomi's text normally uses the Windows system font (Segoe UI Variable), which is not bundled.
