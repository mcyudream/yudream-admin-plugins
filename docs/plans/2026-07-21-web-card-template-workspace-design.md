# Web Card Template Workspace Design

## Goal

Make template authoring a focused code-and-preview workflow. Administrators can edit structured JSON or Agent-generated HTML/CSS, enter a real matching URL, and preview the current unsaved draft with parsed page text and images.

## Architecture

- Keep template CRUD as the management surface, but collapse it into a compact selectable table above the editor.
- Use a stable two-column workspace: code editor on the left and URL-driven rendered preview on the right. Collapse to one column on narrow screens.
- Add a CodeMirror-based local editor for JSON, HTML, and CSS. Structured mode edits layout JSON; advanced mode exposes HTML and CSS tabs.
- Move version history into a wide modal so it no longer stretches the main page.
- Add an admin-only draft preview endpoint. It accepts a site ID, URL, and transient template version, parses the page with the saved site rules/headers, proxies allowed images, and renders without persisting the draft.

## Data Flow

1. User selects a template and edits its current draft.
2. User enters a matching detail URL and requests preview.
3. Frontend sends URL plus the unsaved template payload.
4. Backend validates the template belongs to the selected site, fetches and parses the URL, then renders the transient template.
5. Frontend shows the card image and parsed field data; saving creates the immutable draft version as before.

## Errors And Validation

- Reject blank URLs, mismatched domains/path rules, templates belonging to another site, invalid JSON, and missing parse rules with explicit feedback.
- Preserve the last successful preview while displaying a new preview error.
- Disable duplicate save/preview/publish actions while requests are in flight.

## Verification

- Backend contract and service tests for transient URL preview.
- Frontend API and editor-state tests.
- Frontend typecheck, unit tests, production build, backend tests, package, and JAR entry verification.

