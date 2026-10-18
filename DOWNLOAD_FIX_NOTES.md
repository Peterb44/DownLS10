# Download reliability update (v3.0)

Changes in this update:
- Increased HTTP read timeout to five minutes for slow connections.
- Rejects generic HTML error/login/interstitial pages returned with HTTP 200 instead of silently saving them as the requested file. Explicit `.html`, `.htm`, and `.xhtml` downloads remain allowed, as do responses marked as attachments.
- Validates `Content-Range` before appending resumed HTTP downloads, and restarts from byte zero if a server returns an invalid partial range for a resumed request.
- Keeps existing retry/resume behavior for transient network failures and existing SourceForge redirect resolution.

Limitations: no downloader can guarantee every website. Links requiring a login, CAPTCHA, expiring session, app-only API, or a host that blocks direct downloads may still need a browser-authenticated/direct link. Resume depends on server support for byte ranges. Build and device runtime tests must be completed in GitHub Actions/on-device.
