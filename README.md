# MiAIPlus product site

Static GitHub Pages site: https://astohu1.github.io/MiAIPlus/

This branch contains only the public website. Android application source and its CI remain on `main`.

## Editing

Edit `index.html`, `style.css`, and `site.js` on `gh-pages`, then push that branch. GitHub Pages publishes the repository root. No build tooling, CDN, tracking, remote fonts, or API keys are required. Run `python3 -m http.server 8080` for a local preview.

## Content basis

Reviewed against main commit `a31ecdc` on 2026-10-18: README, architecture, dated validation, host compatibility, conversation limits, virtual screen settings. The site describes current source capabilities and directs readers to Release notes for the actual distributed package. Custom virtual canvas has local validation only; fixed-canvas background, IME isolation, conversation continuity, menus, mock protocol verification and deterministic device actions have different evidence scopes. No claim of universal compatibility or autonomous task success rate is made.

All screen visuals are code-drawn conceptual diagrams, explicitly labeled rather than presented as device screenshots. No third-party image or font files are bundled. The model selector is a visual explainer, not a live module connection.

## Checks

Check desktop and narrow mobile layouts, all three model explanation buttons, FAQ expansion, local resource loads, fragment anchors, reduced motion and horizontal overflow before publishing. Maintain exact host versions and background root/ROM requirements when updating copy.
